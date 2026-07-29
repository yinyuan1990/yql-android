package com.fz.yqlandroid.manager

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import org.webrtc.*

/**
 * P2P/WebRTC 直连管理类（对照 iOS P2PManager.swift 移植）。
 *
 * - 与 SRS 模式互斥：connect_mode == "p2p" 时由 WebRTCManager 启动本类，SRS 推流不启用。
 * - 多观看端：每个观看 PC 一个独立 PeerConnection（Offerer）。
 * - 链路顺序：P2P 直连(host/srflx) → TURN 中继(relay)；不回退 SRS（静态连接方式）。
 * - 信令走 WebSocketManager 的 /app/webrtc/signal（收 /topic/device/{id}/webrtc），
 *   协议与 iOS 完全一致，PC 端零改动。
 *
 * ⚠️ 移植时吸取的 iOS 坑点（详见文档《项目路径总览-各端速查.md》§21.5）：
 * 1. degradationPreference 必须 MAINTAIN_RESOLUTION（maintainFramerate 会导致弱网分辨率乱串）。
 * 2. 码率与帧率分开写 RtpParameters（一次全刷会互相牵连，「调码率改了 fps」）。
 * 3. 所有编码参数操作做成「AllSessions」遍历，绝不依赖单一 videoSender（iOS 断链半年的教训）。
 * 4. 不加任何周期强制 IDR（刚摘掉的攒帧卡顿根因）；关键帧纯按需（PLI/WS request_keyframe/切档）。
 */
class P2PManager(private val context: Context) {

    companion object {
        private const val TAG = "P2PManager"
        /** 当前 P2P 观看端数（供心跳上报，WebSocketManager 读取） */
        @Volatile var currentViewerCount: Int = 0
        private const val MAX_ICE_RETRIES = 2
    }

    /** 数据源：由 WebRTCManager 提供工厂、视频轨与当前编码参数 */
    interface DataSource {
        val p2pFactory: PeerConnectionFactory?
        val p2pLocalVideoTrack: VideoTrack?
        /** 当前码率区间（kbps）：Pair(min, max) */
        fun p2pBitrateRangeKbps(): Pair<Int, Int>
        /** 当前目标推送 FPS */
        fun p2pTargetFps(): Int
        /** 当前分辨率缩放比 */
        fun p2pScaleDown(): Double
    }

    var dataSource: DataSource? = null

    @Volatile var isActive = false
        private set
    /** 是否就绪接收观看请求（采集/视频轨已就绪） */
    @Volatile var isReadyForViewers = false

    // 每个观看 PC 一个独立会话（主线程访问）
    private val viewerSessions = LinkedHashMap<String, PeerConnection>()
    private val viewerSenders = LinkedHashMap<String, RtpSender>()
    private val pendingRemoteIce = HashMap<String, MutableList<IceCandidate>>()
    private val pendingIceRestart = HashSet<String>()
    private val iceRetryCount = HashMap<String, Int>()
    private val forceRelayPeerIds = HashSet<String>()      // ICE 失败黑名单 → 重建时强制 relay
    // ⭐ §25.7b：链路择优的 relay 钉住集合。与 forceRelayPeerIds 的区别：**跨会话拆建存活**
    //（removeViewerSession 不清除，仅 closeAllViewerSessions 清），因为硬切中继 = 拆会话让 PC
    // 重新 REQUEST，重建时必须还记得「这个 PC 要走 relay」。
    private val qualityRelayPeerIds = HashSet<String>()
    private val peerNetworkType = HashMap<String, String>() // pcDeviceId → "cellular"/"wifi"/...
    // ⭐ 会话创建时间：① WEBRTC_REQUEST 去重只在 3s 内生效（防切网后卡死的 CONNECTING 僵尸会话
    //   永远吞掉 PC 的重连请求）；② 创建后 1s 内到达的 WEBRTC_HANGUP 视为旧会话残留信令，忽略
    //   （§23.2 首开 17s 竞态：PC 先 HANGUP 旧会话再 REQUEST，两条消息乱序到达会把新会话干掉）。
    private val sessionCreatedAt = HashMap<String, Long>()
    // ⭐ §53.3①：PC 带来的 requestId（每次 connectP2P/重发递增）。变了就必须拆旧建新，
    //   不能再按"会话建立中"忽略——否则新登录的 PC 会被上一轮的幽灵会话吞掉请求。
    private val lastRequestId = HashMap<String, Long>()

    private val gson = Gson()
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 本机网络状态（蜂窝→强制 relay）。§21.27：由 WebRTCManager 的统一网络监听喂入，本类不再自注册回调
    private var isOnCellular = false

    private val prefs get() = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
    private val maxViewers: Int get() = prefs.getInt("max_p2p_viewers", 4).let { if (it > 0) it else 4 }
    // 后端下发的强制中继开关。§52.6 的「非同 WiFi 退登录页」在此开关打开时不干预，故需对 WebRTCManager 可见。
    val forceRelay: Boolean get() = prefs.getBoolean("force_relay", false)

    /** 已连接（ICE connected/completed）的观看会话，供 WebRTCManager 采集码率/网络 stats。 */
    val connectedViewerPeerConnections: List<PeerConnection>
        get() = synchronized(viewerSessions) {
            // ⭐ 按 pcId 排序保证稳定顺序（iOS 曾因 Dictionary 无序导致多观看端统计基线来回跳 →
            //   假 PLI 风暴/假丢包，引发攒帧卡顿。LinkedHashMap 本身有序，排序双保险：
            //   首路观看端断开重连后插入顺序变化时基线也不跳）。
            viewerSessions.entries
                .sortedBy { it.key }
                .map { it.value }
                .filter {
                    val s = it.iceConnectionState()
                    s == PeerConnection.IceConnectionState.CONNECTED ||
                            s == PeerConnection.IceConnectionState.COMPLETED
                }
        }

    val viewerCount: Int get() = synchronized(viewerSessions) { viewerSessions.size }

    /** ⭐ [meidui 诊断] 各观看会话 ICE 状态一览（P2P 推送 fps=0 排查用） */
    fun sessionStatesSummary(): String = synchronized(viewerSessions) {
        if (viewerSessions.isEmpty()) "无观看会话(等PC发WEBRTC_REQUEST)"
        else viewerSessions.entries.joinToString(", ") { "${it.key}=${it.value.iceConnectionState()}" }
    }

    /** ⭐ [meidui 诊断] 首个已连接会话的视频 Sender（P2P 模式读编码器帧率上限/active 用，与统计源同序） */
    val firstConnectedSender: RtpSender?
        get() = synchronized(viewerSessions) {
            viewerSessions.entries.sortedBy { it.key }
                .firstOrNull {
                    val s = it.value.iceConnectionState()
                    s == PeerConnection.IceConnectionState.CONNECTED ||
                            s == PeerConnection.IceConnectionState.COMPLETED
                }?.let { viewerSenders[it.key] }
        }

    // MARK: - 生命周期

    fun start() {
        if (isActive) return
        isActive = true
        isReadyForViewers = true
        // §21.27：网络监听已统一收口到 WebRTCManager（P2P/SRS 共用），这里不再自注册
        Log.d(TAG, "✅ P2PManager 启动，maxViewers=$maxViewers, forceRelay=$forceRelay")
    }

    fun stop() {
        isReadyForViewers = false
        closeAllViewerSessions(notifyPC = true)
        isActive = false
        Log.d(TAG, "🛑 P2PManager 停止")
    }

    // MARK: - 网络事件（§21.27 统一入口在 WebRTCManager，这里只留出口）

    /** §21.27 由 WebRTCManager 的统一网络监听喂入蜂窝状态（relay 策略用）。返回 true=类型变化（切网事件） */
    fun updateCellularState(cellular: Boolean): Boolean {
        val changed = cellular != isOnCellular
        isOnCellular = cellular
        return changed
    }

    /** §21.27 统一出口：WS 重连成功 / 切网（经 WebRTCManager.publishHealthCheck）→ 全部会话 ICE Restart */
    fun restartAllSessions(source: String) {
        if (viewerCount == 0) {
            Log.d(TAG, "📶 [$source] 无观看会话，等 PC 发 WEBRTC_REQUEST")
            return
        }
        Log.d(TAG, "🔌 [$source] 重连所有 P2P 会话")
        mainScope.launch { restartAllIceForNetworkSwitch() }
    }

    /** 兼容旧调用点 */
    fun onWebSocketReconnected() = restartAllSessions("WS重连")

    private fun restartAllIceForNetworkSwitch() {
        // ⭐ 切网重连关键修复：切网瞬间旧 WS 多半已死，此时发 ICE Restart Offer = 发进黑洞
        //   （PC 永远收不到），且会话被标 pendingIceRestart 卡住。改为等 WS 重连成功后
        //   （onWebSocketReconnected 会再次调本方法）统一重连，信令必达。
        if (!WebSocketManager.instance.isConnected) {
            Log.w(TAG, "📶 切网但 WS 未连接，ICE Restart 推迟到 WS 重连后统一执行")
            Log.d("meidui", "📶 [P2P切网] WS断开中，Offer 不发（防黑洞），等 WS 重连后统一 ICE Restart")
            return
        }
        // ⭐ 2026-07-09 修「切网后必须手动重登 PC 才出画面」根因（对标 iOS）：
        //   观看端恒为 PC GStreamer，其 webrtcbin 不支持在旧实例上 ICE Restart（收新 ufrag 的
        //   re-offer 不会重启 libnice/重新收集候选 → 卡死）。切网【不再尝试 ICE Restart】，
        //   一律拆会话 + HANGUP(network_switch_reconnect)，由 PC 整体重建 pipeline + 重发 REQUEST。
        val sessions = synchronized(viewerSessions) { viewerSessions.toMap() }
        Log.d("meidui", "📶 [P2P切网] 拆除并让 PC 重连 ${sessions.size} 个会话(不做ICE Restart): ${sessionStatesSummary()}")
        for ((pcId, _) in sessions) {
            iceRetryCount[pcId] = 0
            if (isOnCellular) forceRelayPeerIds.add(pcId)   // 蜂窝：PC 重建后手机新 Offer 走 relay
            removeViewerSession(pcId, notifyPC = false)
            WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_HANGUP", "network_switch_reconnect", pcId)
        }
        onNetworkSwitchReconnect?.invoke()   // 通知上层置"重连中"（StreamingScreen 左上角显示）
    }

    /** ⭐ 切网触发重连回调（WebRTCManager 用于置"重连中"给 UI，PC 心跳恢复后清除） */
    var onNetworkSwitchReconnect: (() -> Unit)? = null

    // MARK: - 传输策略

    private fun effectiveForceRelay(pcId: String): Boolean {
        if (forceRelay) return true
        return isOnCellular || peerNetworkType[pcId] == "cellular" || forceRelayPeerIds.contains(pcId) ||
                qualityRelayPeerIds.contains(pcId)
    }

    // MARK: - §25.7e 线路预判定（建会话前经 WebSocket 信令定直连/中继）

    /** 本机全部 IPv4（WiFi/热点/有线，排除回环与链路本地 169.254.*） */
    private fun localIPv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return result
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("169.254.")) result.add(ip)
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }

    /** 同网段判定（/24）：双方任意一对 IPv4 前三段相同 = 同一局域网（同 WiFi） */
    private fun sharesSubnet(peerIps: List<String>): Boolean {
        fun prefix24(ip: String): String? {
            val parts = ip.split(".")
            return if (parts.size == 4) parts.subList(0, 3).joinToString(".") else null
        }
        val myPrefixes = localIPv4Addresses().mapNotNull(::prefix24).toSet()
        return peerIps.any { prefix24(it)?.let(myPrefixes::contains) == true }
    }

    /**
     * ⭐ §25.7e：建会话前预判线路。PC 的 WEBRTC_REQUEST 带 localIps（逗号分隔的局域网 IPv4），
     * 与本机比网段：非同网段 = 非同 WiFi → pcId 钉进 qualityRelayPeerIds → 会话从创建起
     * relay-only，一次 ICE 定终身，不再有「直连先通→ICE 换车→软切/硬切」的中途折腾。
     * 同网段/字段缺失（旧版 PC）→ 保持直连优先，host↔host stats 判定兜底。
     * 只进不出：不因后续 REQUEST 判同网段而摘除钉住（防两个不同网络恰好同网段号 → 死循环回直连）。
     */
    private fun applyLanPrecheck(pcId: String, message: Map<String, Any>) {
        val ipsStr = message["localIps"] as? String
        if (ipsStr.isNullOrEmpty()) {
            Log.d(TAG, "🛣 [P2P线路预判] $pcId REQUEST 未带 localIps（旧版PC）→ 直连优先+stats兜底")
            return
        }
        val peerIps = ipsStr.split(",").filter { it.isNotBlank() }
        if (sharesSubnet(peerIps)) {
            Log.d(TAG, "🛣 [P2P线路预判] $pcId 同网段(同WiFi) → 直连优先 peer=$peerIps")
        } else {
            qualityRelayPeerIds.add(pcId)
            Log.d(TAG, "🛣 [P2P线路预判] $pcId 非同网段(非同WiFi) → 建会话即中继 peer=$peerIps 本机=${localIPv4Addresses()}")
        }
    }

    private fun loadIceServers(): List<PeerConnection.IceServer> {
        val json = prefs.getString("ice_servers_json", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<com.fz.yqlandroid.network.IceServer>>() {}.type
            val servers: List<com.fz.yqlandroid.network.IceServer> = gson.fromJson(json, type)
            servers.mapNotNull { s ->
                if (s.urls.isEmpty()) return@mapNotNull null
                val builder = PeerConnection.IceServer.builder(s.urls)
                if (!s.username.isNullOrEmpty() && !s.credential.isNullOrEmpty()) {
                    builder.setUsername(s.username).setPassword(s.credential)
                }
                builder.createIceServer()
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析 iceServers 失败: ${e.message}")
            emptyList()
        }
    }

    // MARK: - 信令处理（由 WebSocketManager 收 /topic/device/{id}/webrtc 后转发进来）

    fun handleSignaling(message: Map<String, Any>) {
        val type = message["type"] as? String ?: return
        val fromDevice = message["fromDevice"] as? String ?: ""

        // ⭐ 崩溃修复（跨线程 use-after-free）：本方法由 WebSocketManager 在 OkHttp 读线程调用，
        //   而所有 PeerConnection 的创建/访问/释放本类都约定「主线程访问」（见 viewerSessions 声明处）。
        //   旧代码直接在 WS 线程里 createViewerSession / setRemoteDescription / pc.close()，
        //   同一批 pc 又被 Observer 里的 mainScope.launch{ addIceCandidate / iceConnectionState / retryIce }
        //   在主线程并发触碰 → PC 切网/重启发来 HANGUP 时 WS 线程 close() 释放 native 对象，主线程排队的
        //   ICE 回调打到已释放 pc → SIGSEGV（libjingle_peerconnection_so.so）。
        //   统一 hop 到 mainScope，让信令与 ICE/Observer 处理在同一线程串行，彻底消除 native 竞态。
        mainScope.launch {
            when (type) {
                "VIEWER_CONNECTED" -> Log.d(TAG, "✅ PC $fromDevice 已收到画面")
                // ⭐ §53.3①：真的拆会话（以前只打日志）。PC 退出时发的这条通知白发 → 会话留在
                //   CONNECTING 变"幽灵会话"，把 PC 下次登录的请求吞掉（iOS 侧同款问题更严重）。
                "VIEWER_DISCONNECTED" -> {
                    val had = synchronized(viewerSessions) { viewerSessions.containsKey(fromDevice) }
                    if (had) {
                        Log.d(TAG, "🔌 PC $fromDevice 断开 → 拆会话（防幽灵会话吞掉下次 WEBRTC_REQUEST）")
                        removeViewerSession(fromDevice, notifyPC = false)
                    } else {
                        Log.d(TAG, "🔌 PC $fromDevice 断开（无活动会话）")
                    }
                }
                "WEBRTC_REQUEST" -> {
                    peerNetworkType[fromDevice] = (message["networkType"] as? String) ?: "unknown"
                    applyLanPrecheck(fromDevice, message)   // §25.7e：建会话前定直连/中继
                    if (!isReadyForViewers) {
                        WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_REJECT", "not_ready", fromDevice)
                        return@launch
                    }
                    // requestId：PC 每次 connectP2P/重发递增；旧版 PC 不带（null）→ 只靠时间窗去重
                    val reqId = (message["requestId"] as? Number)?.toLong()
                    createViewerSession(fromDevice, reqId)
                }
                "WEBRTC_SDP" -> {
                    val sdpType = message["sdpType"] as? String ?: ""
                    val sdp = message["sdp"] as? String ?: ""
                    if (sdpType == "answer") handleRemoteAnswer(sdp, fromDevice)
                }
                "WEBRTC_ICE" -> handleRemoteIce(message, fromDevice)
                "WEBRTC_HANGUP" -> {
                    // ⭐ §23.2 竞态保护：会话刚创建（<1s）就收到 HANGUP = PC 针对「上一个会话」的挂断
                    //   乱序迟到（PC 先 HANGUP 再 REQUEST，服务器转发顺序不保证）。无条件移除会把
                    //   正在 createOffer 的新会话干掉 → Offer 发到死会话 → 首开白等 15s+。
                    val createdAt = synchronized(viewerSessions) { sessionCreatedAt[fromDevice] } ?: 0L
                    val ageMs = System.currentTimeMillis() - createdAt
                    if (createdAt > 0 && ageMs < 1000) {
                        Log.w(TAG, "🛡️ 忽略 HANGUP($fromDevice)：会话仅 ${ageMs}ms 前创建，判定为旧会话残留信令")
                        Log.d("meidui", "🛡️ [P2P] 忽略旧会话残留 HANGUP(from=$fromDevice, 会话年龄=${ageMs}ms)")
                    } else {
                        removeViewerSession(fromDevice, notifyPC = false)
                    }
                }
            }
        }
    }

    private fun handleRemoteAnswer(sdp: String, pcId: String) {
        val pc = synchronized(viewerSessions) { viewerSessions[pcId] } ?: return
        // ⭐ [meidui 诊断] 打出 PC Answer 实际协商到的视频 codec（应为 H264；出现 VP8/VP9 = munge 没生效）
        run {
            val codecs = Regex("a=rtpmap:\\d+\\s+([A-Za-z0-9]+)/90000").findAll(sdp)
                .map { it.groupValues[1] }.filter { !it.equals("rtx", true) }.distinct().toList()
            Log.d("meidui", "P2P Answer($pcId) 协商视频codec=${codecs.joinToString("/")}")
        }
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(s: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetSuccess() {
                mainScope.launch {
                    pendingIceRestart.remove(pcId)
                    // flush 缓冲的远端 ICE
                    pendingRemoteIce.remove(pcId)?.forEach { pc.addIceCandidate(it) }
                    Log.d(TAG, "✅ 收到 PC $pcId Answer，会话建立中")
                }
            }
            override fun onSetFailure(e: String?) {
                Log.e(TAG, "❌ setRemoteDescription 失败($pcId): $e")
            }
        }, answer)
    }

    private fun handleRemoteIce(message: Map<String, Any>, pcId: String) {
        val pc = synchronized(viewerSessions) { viewerSessions[pcId] } ?: return
        val candidate = message["candidate"] as? String ?: ""
        if (candidate.isEmpty()) return   // 忽略 end-of-candidates
        val mid = message["sdpMid"] as? String ?: "0"
        val mline = (message["sdpMLineIndex"] as? Number)?.toInt() ?: 0
        val ice = IceCandidate(mid, mline, candidate)
        mainScope.launch {
            if (pc.remoteDescription == null || pendingIceRestart.contains(pcId)) {
                pendingRemoteIce.getOrPut(pcId) { mutableListOf() }.add(ice)
            } else {
                pc.addIceCandidate(ice)
            }
        }
    }

    // MARK: - 会话管理

    fun createViewerSession(pcId: String, requestId: Long? = null) {
        val ds = dataSource ?: run { Log.e(TAG, "❌ dataSource 为空"); return }
        val factory = ds.p2pFactory ?: run { Log.e(TAG, "❌ factory 为空"); return }

        synchronized(viewerSessions) {
            viewerSessions[pcId]?.let { existing ->
                val s = existing.connectionState()
                val ageMs = System.currentTimeMillis() - (sessionCreatedAt[pcId] ?: 0L)
                // ⭐ §53.3① / §53.16：去重窗口 3000→2000ms，与 PC 的重发间隔 1.5s、iOS 同款窗口对齐。
                //   ⚠️ §53.16 回归修复：**不要拿 requestId 判断"是不是同一轮请求"**。
                //   PC 侧 requestId 是逐条消息生成的毫秒时间戳，连它自己 1.5s 一次的重发都换新值——
                //   一旦把"id 变了"当成"新一轮"，这个去重窗就等于没有：每次重试都拆掉刚建好的会话，
                //   PC 拿着旧 Offer 回的 Answer 落到新会话上、SDP 对不上 → 永远连不通（iOS 上实测：
                //   采集正常但推送=0fps、PC 不出画面）。requestId 只留作日志关联。
                if ((s == PeerConnection.PeerConnectionState.NEW ||
                     s == PeerConnection.PeerConnectionState.CONNECTING) && ageMs < 2000) {
                    Log.w(TAG, "⚠️ PC $pcId 会话建立中(${ageMs}ms, reqId=$requestId)，忽略重复请求")
                    return
                }
                // ⭐ 切网重连关键修复：超过 3s 仍停在 NEW/CONNECTING = 僵尸会话（切网后 ICE 永远
                //   连不上/Offer 曾发进死 WS）。旧逻辑无条件忽略 → PC 每次超时重发 REQUEST 都被
                //   吞掉 → 永远连不上。现在拆掉重建，PC 的重连请求必须赢。
                Log.w(TAG, "🔁 PC $pcId 旧会话状态=$s(${ageMs}ms)，拆掉重建响应新 REQUEST")
                Log.d("meidui", "🔁 [P2P] REQUEST 触发旧会话重建: state=$s age=${ageMs}ms")
            }
        }
        // 已存在旧会话（非建立中）→ 拆掉重建
        if (synchronized(viewerSessions) { viewerSessions.containsKey(pcId) }) {
            removeViewerSession(pcId, notifyPC = false)
        }
        if (requestId != null) lastRequestId[pcId] = requestId

        if (viewerCount >= maxViewers) {
            Log.e(TAG, "❌ 已达最大观看人数($maxViewers)，拒绝 $pcId")
            WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_REJECT", "max_viewers_reached", pcId)
            return
        }

        val videoTrack = ds.p2pLocalVideoTrack ?: run {
            Log.e(TAG, "❌ 视频轨未就绪，无法创建会话")
            return
        }

        // ⭐⭐ §53.19（用户拍板，与 iOS 一致）：P2P **只做局域网直连**——去掉 TURN 中继与 STUN 打洞。
        //   传空 iceServers → 只产生 host 候选：同 WiFi 秒连；不在同 WiFi 无 srflx/relay → ICE 失败回落。
        //   从 ICE 层根断"非局域网还假装 P2P（实走中继）"。loadIceServers/effectiveForceRelay/relay 相关保留但不生效。
        val servers = emptyList<PeerConnection.IceServer>()
        Log.d(TAG, "🔔 P2P 局域网直连(host-only，无 TURN/STUN)")

        val cfg = PeerConnection.RTCConfiguration(servers)
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        cfg.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        cfg.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        cfg.iceCandidatePoolSize = 2
        // P0-2 对齐 iOS：ICE 稳定性参数（8s 无收包才判 disconnected，弱网更耐抖）
        cfg.iceConnectionReceivingTimeout = 8000
        cfg.iceBackupCandidatePairPingInterval = 2000
        // ⭐ §53.19：无 STUN/TURN，ALL 实际只剩 host 候选（=局域网直连）
        cfg.iceTransportsType = PeerConnection.IceTransportsType.ALL

        val observer = createObserver(pcId)
        val newPC = factory.createPeerConnection(cfg, observer) ?: run {
            Log.e(TAG, "❌ 创建 PeerConnection 失败 $pcId")
            return
        }

        val sender = newPC.addTrack(videoTrack, listOf("s0"))
        synchronized(viewerSessions) {
            viewerSessions[pcId] = newPC
            viewerSenders[pcId] = sender
            sessionCreatedAt[pcId] = System.currentTimeMillis()
            currentViewerCount = viewerSessions.size
        }
        // 会话创建时初始化整组参数（码率 + 帧率），后续走拆分后的独立方法
        applyBitrate(sender)
        applyFramerate(sender)

        Log.d(TAG, "✅ 会话创建成功 $pcId，当前观看 $viewerCount/$maxViewers")

        val cons = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        newPC.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                // ⭐ H264 限定（对齐 iOS preferredCodec=H264）：Android DefaultVideoEncoderFactory
                //   的 codec 顺序是软件编码器(VP8/VP9/AV1)在前、H264 在后 → Offer 首选 VP8；
                //   PC GStreamer 是 Answerer 且解码链路写死 rtph264depay(只解 H264)，
                //   协商成 VP8 = ICE 连上但画面永远出不来（SRS 不受影响：SRS Answer 只回 H264）。
                val munged = SessionDescription(sdp.type, mungeOfferForCodec(sdp.description))
                newPC.setLocalDescription(SilentSdpObserver, munged)
                WebSocketManager.instance.sendWebRTCSignalingSDP("offer", munged.description, pcId)
                Log.d(TAG, "📤 已发送 Offer 给 $pcId（${H265Support.codecLabel()} 限定）")
            }
            override fun onCreateFailure(e: String?) { Log.e(TAG, "❌ 创建 Offer 失败 $pcId: $e") }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }, cons)
    }

    fun removeViewerSession(pcId: String, notifyPC: Boolean) {
        if (notifyPC) {
            WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_HANGUP", "android_close", pcId)
        }
        val pc = synchronized(viewerSessions) {
            val removed = viewerSessions.remove(pcId)
            viewerSenders.remove(pcId)
            sessionCreatedAt.remove(pcId)
            currentViewerCount = viewerSessions.size
            removed
        }
        try { pc?.close() } catch (_: Exception) {}
        pendingRemoteIce.remove(pcId)
        pendingIceRestart.remove(pcId)
        iceRetryCount.remove(pcId)
        forceRelayPeerIds.remove(pcId)
        peerNetworkType.remove(pcId)
        lastRequestId.remove(pcId)
        Log.d(TAG, "🔌 移除会话 $pcId，剩余 $viewerCount")
    }

    fun closeAllViewerSessions(notifyPC: Boolean) {
        val sessions = synchronized(viewerSessions) {
            val copy = viewerSessions.toMap()
            viewerSessions.clear()
            viewerSenders.clear()
            sessionCreatedAt.clear()
            currentViewerCount = 0
            copy
        }
        for ((pcId, pc) in sessions) {
            if (notifyPC) {
                WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_HANGUP", "android_stop_publish", pcId)
            }
            try { pc.close() } catch (_: Exception) {}
        }
        pendingRemoteIce.clear()
        pendingIceRestart.clear()
        iceRetryCount.clear()
        forceRelayPeerIds.clear()
        qualityRelayPeerIds.clear()   // §25.7b：整体停止才清 relay 钉住（单会话拆建不清）
        peerNetworkType.clear()
    }

    // MARK: - PeerConnection Observer

    private fun createObserver(pcId: String) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            WebSocketManager.instance.sendWebRTCSignalingICE(
                candidate.sdp, candidate.sdpMid ?: "0", candidate.sdpMLineIndex, pcId)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "📡 [P2P] $pcId ICE状态: $state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    mainScope.launch { iceRetryCount.remove(pcId) }
                    Log.d(TAG, "✅ $pcId ICE 已连接")
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    Log.e(TAG, "❌ $pcId ICE 失败，重连")
                    mainScope.launch {
                        synchronized(viewerSessions) { viewerSessions[pcId] }
                            ?.let { retryIceConnection(pcId, it) }
                    }
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    Log.w(TAG, "⚠️ $pcId ICE 断开，15s 后检查")
                    mainScope.launch {
                        delay(15000)
                        val pc = synchronized(viewerSessions) { viewerSessions[pcId] } ?: return@launch
                        val s = pc.iceConnectionState()
                        if (s == PeerConnection.IceConnectionState.DISCONNECTED ||
                            s == PeerConnection.IceConnectionState.FAILED) {
                            retryIceConnection(pcId, pc)
                        }
                    }
                }
                else -> {}
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
    }

    // MARK: - ICE 重连（P2P/TURN 内部，不回退 SRS）

    private fun retryIceConnection(pcId: String, pc: PeerConnection) {
        val cur = iceRetryCount[pcId] ?: 0
        if (cur < MAX_ICE_RETRIES) {
            iceRetryCount[pcId] = cur + 1
            forceRelayPeerIds.add(pcId)   // 失败后下次重建走 relay
            pendingIceRestart.add(pcId)
            val cons = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (sdp == null) return
                    // ⭐ ICE Restart 的 Offer 同样做 codec 限定（与首次 Offer 一致，防重协商时倒回 VP8）
                    val munged = SessionDescription(sdp.type, mungeOfferForCodec(sdp.description))
                    pc.setLocalDescription(SilentSdpObserver, munged)
                    WebSocketManager.instance.sendWebRTCSignalingSDP("offer", munged.description, pcId)
                    Log.d(TAG, "🔄 ICE Restart Offer 已发送 $pcId (${cur + 1}/$MAX_ICE_RETRIES)")
                }
                override fun onCreateFailure(e: String?) { Log.e(TAG, "ICE Restart Offer 失败 $pcId: $e") }
                override fun onSetSuccess() {}
                override fun onSetFailure(e: String?) {}
            }, cons)
        } else {
            // ⭐ §53.19：P2P 已是纯局域网直连（无 TURN/STUN），ICE 重试耗尽 = 确认不在局域网
            //   → 回落 SRS（原"不回退 SRS"是连接方式静态时代的口径，现在会永远黑屏）。
            Log.e(TAG, "❌ $pcId ICE 重试耗尽（无中继=确认非局域网）→ 回落 SRS")
            iceRetryCount.remove(pcId)
            removeViewerSession(pcId, notifyPC = false)
            SessionPolicy.forceSrsForSession("P2P ICE 失败重试耗尽($pcId)，无中继=确认非局域网")
            WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_HANGUP", "ice_failed", pcId)
        }
    }

    // MARK: - 链路择优（§25.7：直连质量差 → 主动切中继，对照 iOS switchAllSessionsToRelay 移植）

    /**
     * 直连路径质量持续差（由 WebRTCManager stats 判定：ICE RTT >300ms 持续 10s）时，
     * 把所有会话切到 TURN 中继。
     * 两级策略（§25.7b，2026-07-03 iOS 实测日志定型）：
     * - **第一次触发 = 软切**：setConfiguration(RELAY) + ICE Restart Offer（不整拆会话，旧路径
     *   持续出画面直到新 relay 路径 nominated）。Chromium 内核（网页内核）走这条即可完成切换。
     * - **第二次触发（10s 后路径仍是直连）= 硬切**：实测 GStreamer webrtcbin 收到新 ufrag 的
     *   Offer 不重启 libnice、不重新收集候选（回了 Answer 但零新增本地候选），软切必然失效。
     *   升级为：pcId 钉进 qualityRelayPeerIds（跨会话存活）→ 拆会话 → 发
     *   WEBRTC_HANGUP(network_switch_reconnect)（PC 已有处理：不拆 pipeline，自动重发
     *   WEBRTC_REQUEST）→ 重建的会话 effectiveForceRelay=true，从建会话起就只走 TURN。
     *   网页内核第一次软切就生效、到不了第二次，不受硬切 HANGUP（网页内核收 HANGUP 停播不重连）影响。
     */
    fun switchAllSessionsToRelay(reason: String) {
        mainScope.launch {
            val servers = loadIceServers()
            // 无 TURN 服务器时强制 relay = 零候选必死，直接放弃
            val hasTurn = servers.any { s ->
                s.urls.any { it.startsWith("turn:") || it.startsWith("turns:") }
            }
            if (!hasTurn) {
                Log.w(TAG, "⚠️ 切中继请求被忽略（无 TURN 服务器配置）reason=$reason")
                return@launch
            }
            val sessions = synchronized(viewerSessions) { viewerSessions.toMap() }
            for ((pcId, pc) in sessions) {
                if (forceRelayPeerIds.contains(pcId)) {
                    // 已软切过仍被再次触发 = 路径还是直连，对端不支持 ICE Restart（GStreamer）→ 硬切
                    if (qualityRelayPeerIds.contains(pcId)) continue   // 硬切也做过 = 等重建，别重复拆
                    qualityRelayPeerIds.add(pcId)
                    Log.w(TAG, "🔨 软切中继未生效(路径仍直连，对端不支持 ICE Restart) → 硬切重建 $pcId reason=$reason")
                    Log.d("meidui", "🔨 [P2P线路] 软切未生效 → 硬切重建(拆会话+network_switch_reconnect) $pcId")
                    removeViewerSession(pcId, notifyPC = false)
                    WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_HANGUP", "network_switch_reconnect", pcId)
                    continue
                }
                forceRelayPeerIds.add(pcId)
                try {
                    // Android 无 pc.configuration 读取器 → 按 createViewerSession 同参重建配置，仅传输策略改 RELAY
                    val cfg = PeerConnection.RTCConfiguration(servers)
                    cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                    cfg.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    cfg.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                    cfg.iceCandidatePoolSize = 2
                    cfg.iceConnectionReceivingTimeout = 8000
                    cfg.iceBackupCandidatePairPingInterval = 2000
                    cfg.iceTransportsType = PeerConnection.IceTransportsType.RELAY
                    pc.setConfiguration(cfg)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 切中继 setConfiguration 失败 $pcId: ${e.message}")
                    continue
                }
                pendingIceRestart.add(pcId)
                val cons = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }
                pc.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (sdp == null) return
                        val munged = SessionDescription(sdp.type, mungeOfferForCodec(sdp.description))
                        pc.setLocalDescription(SilentSdpObserver, munged)
                        WebSocketManager.instance.sendWebRTCSignalingSDP("offer", munged.description, pcId)
                        Log.d(TAG, "🔀 已切中继并发送 ICE Restart Offer → $pcId reason=$reason")
                        Log.d("meidui", "🔀 [P2P线路] 已切中继+ICE Restart → $pcId reason=$reason")
                    }
                    override fun onCreateFailure(e: String?) { Log.e(TAG, "❌ 切中继 Offer 创建失败 $pcId: $e") }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(e: String?) {}
                }, cons)
            }
        }
    }

    // MARK: - 编码参数（码率与帧率解耦，AllSessions 遍历 —— iOS §21.5 教训）

    /** 仅同步「码率」到所有直连会话（不改 maxFramerate）。 */
    fun applyBitrateToAllSessions() {
        forEachSender { applyBitrate(it) }
    }

    /** 仅同步「帧率」到所有直连会话（不改码率）。 */
    fun applyFramerateToAllSessions() {
        forEachSender { applyFramerate(it) }
    }

    /**
     * P2P 本地强制关键帧（作用于所有直连会话）。
     * 与 WebRTCManager.forceKeyframe 相同的码率微调 trick（libwebrtc 未暴露 generateKeyFrame），
     * +1kbps 触发编码器重配出 IDR，20ms 后原样恢复（含 null，不篡改码率配置）。
     */
    fun forceKeyframeAllSessions() {
        val senders = synchronized(viewerSessions) { viewerSenders.toMap() }
        if (senders.isEmpty()) return
        for ((pcId, sender) in senders) {
            try {
                val params = sender.parameters
                if (params.encodings.isEmpty()) continue
                val originalMax = params.encodings[0].maxBitrateBps
                val base = originalMax ?: 3_000_000
                params.encodings[0].maxBitrateBps = base + 1000
                sender.parameters = params
                mainScope.launch {
                    delay(20)
                    val s = synchronized(viewerSessions) { viewerSenders[pcId] } ?: return@launch
                    try {
                        val p2 = s.parameters
                        if (p2.encodings.isNotEmpty()) {
                            p2.encodings[0].maxBitrateBps = originalMax
                            s.parameters = p2
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "forceKeyframe($pcId) 失败: ${e.message}")
            }
        }
        Log.d(TAG, "🔑 forceKeyframe → ${senders.size} 个直连会话")
    }

    private fun forEachSender(block: (RtpSender) -> Unit) {
        val senders = synchronized(viewerSessions) { viewerSenders.values.toList() }
        for (s in senders) {
            try { block(s) } catch (e: Exception) { Log.e(TAG, "sender 参数写入失败: ${e.message}") }
        }
    }

    private fun applyBitrate(sender: RtpSender) {
        val ds = dataSource ?: return
        val params = sender.parameters
        if (params.encodings.isEmpty()) return
        val (minK, maxK) = ds.p2pBitrateRangeKbps()
        params.encodings[0].minBitrateBps = minK * 1000
        params.encodings[0].maxBitrateBps = maxK * 1000
        params.encodings[0].scaleResolutionDownBy = ds.p2pScaleDown()
        // ⭐ 必须 MAINTAIN_RESOLUTION：弱网只降帧/降码率，分辨率锁死=档位预设。
        //   （iOS 曾漏成 maintainFramerate → P2P 弱网分辨率乱串，§21.5 的坑）
        params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        sender.parameters = params
    }

    private fun applyFramerate(sender: RtpSender) {
        val ds = dataSource ?: return
        val params = sender.parameters
        if (params.encodings.isEmpty()) return
        params.encodings[0].maxFramerate = ds.p2pTargetFps()
        sender.parameters = params
    }

    // MARK: - SDP codec 限定（对齐 iOS preferredCodec=H264，见 createViewerSession 处注释）

    /**
     * ⭐ H265：三处 Offer（首发/ICE重试/切中继）统一走这里按会话编码分派。
     *   H264 会话 → 原 forceH264InVideoSection 不动；H265 会话 → H265Support.mungeOfferH265
     *   （限定 H265，Offer 无 H265 时自动回落 H264，逻辑全在 H265Support.kt）。
     */
    private fun mungeOfferForCodec(sdp: String): String =
        if (H265Support.isH265Session()) H265Support.mungeOfferH265(sdp)
        else forceH264InVideoSection(sdp)

    /**
     * 把 m=video 段限定为 H264（含其关联 RTX）：
     * - 重写 m=video 行的 payload 列表（H264 pt 在前、RTX pt 在后，其余剔除）；
     * - 删除被剔除 pt 的 a=rtpmap / a=rtcp-fb / a=fmtp 行；
     * - 找不到 H264（个别设备无 H264 硬编）或解析异常时原样返回，绝不因 munge 弄坏协商。
     */
    internal fun forceH264InVideoSection(sdp: String): String {
        return try {
            val lines = sdp.split("\r\n").toMutableList()
            val mVideoIdx = lines.indexOfFirst { it.startsWith("m=video ") }
            if (mVideoIdx < 0) return sdp
            var sectionEnd = lines.size
            for (i in mVideoIdx + 1 until lines.size) {
                if (lines[i].startsWith("m=")) { sectionEnd = i; break }
            }

            val rtpmapRe = Regex("^a=rtpmap:(\\d+)\\s+([^/]+)/")
            val fmtpAptRe = Regex("^a=fmtp:(\\d+)\\s+.*apt=(\\d+)")
            val h264Pts = LinkedHashSet<String>()
            for (i in mVideoIdx + 1 until sectionEnd) {
                val m = rtpmapRe.find(lines[i]) ?: continue
                if (m.groupValues[2].equals("H264", ignoreCase = true)) h264Pts.add(m.groupValues[1])
            }
            if (h264Pts.isEmpty()) {
                Log.w(TAG, "⚠️ Offer 无 H264（设备无 H264 编码器？），SDP 原样发出")
                return sdp
            }
            val rtxPts = LinkedHashSet<String>()
            for (i in mVideoIdx + 1 until sectionEnd) {
                val m = fmtpAptRe.find(lines[i]) ?: continue
                if (m.groupValues[2] in h264Pts) rtxPts.add(m.groupValues[1])
            }
            val keep = h264Pts + rtxPts

            // 重写 m=video 行：m=video <port> <proto> <pt...>
            val mTokens = lines[mVideoIdx].split(" ")
            if (mTokens.size <= 3) return sdp
            lines[mVideoIdx] = (mTokens.take(3) + h264Pts + rtxPts).joinToString(" ")

            // 剔除非 H264 pt 的属性行（倒序删避免索引错位）
            val ptAttrRe = Regex("^a=(rtpmap|rtcp-fb|fmtp):(\\d+)[\\s:]?")
            for (i in sectionEnd - 1 downTo mVideoIdx + 1) {
                val m = ptAttrRe.find(lines[i]) ?: continue
                if (m.groupValues[2] !in keep) lines.removeAt(i)
            }
            Log.d(TAG, "🎬 Offer 已限定 H264: pt=${h264Pts.joinToString("/")}, rtx=${rtxPts.joinToString("/")}")
            lines.joinToString("\r\n")
        } catch (e: Exception) {
            Log.e(TAG, "forceH264InVideoSection 解析失败，SDP 原样发出: ${e.message}")
            sdp
        }
    }

    private object SilentSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) { Log.e(TAG, "SDP创建失败: $e") }
        override fun onSetFailure(e: String?) { Log.e(TAG, "SDP设置失败: $e") }
    }
}
