package com.fz.yqlandroid.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
    private val peerNetworkType = HashMap<String, String>() // pcDeviceId → "cellular"/"wifi"/...

    private val gson = Gson()
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 本机网络监听（蜂窝强制 relay + 切网重连）
    private var isOnCellular = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetSwitchAt = 0L

    private val prefs get() = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
    private val maxViewers: Int get() = prefs.getInt("max_p2p_viewers", 4).let { if (it > 0) it else 4 }
    private val forceRelay: Boolean get() = prefs.getBoolean("force_relay", false)

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
        startNetworkMonitoring()
        Log.d(TAG, "✅ P2PManager 启动，maxViewers=$maxViewers, forceRelay=$forceRelay")
    }

    fun stop() {
        isReadyForViewers = false
        closeAllViewerSessions(notifyPC = true)
        stopNetworkMonitoring()
        isActive = false
        Log.d(TAG, "🛑 P2PManager 停止")
    }

    // MARK: - 网络监听（切网 → ICE Restart；蜂窝 → 强制 relay）

    private fun startNetworkMonitoring() {
        if (networkCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val typeChanged = (cellular != isOnCellular)
                isOnCellular = cellular
                if (typeChanged) onNetworkSwitched("类型变化(蜂窝=$cellular)")
            }

            override fun onAvailable(network: Network) {
                // 换 WiFi / 断网恢复通常经历 onAvailable
                onNetworkSwitched("网络可用")
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.e(TAG, "网络监听注册失败: ${e.message}")
        }
    }

    private fun stopNetworkMonitoring() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
    }

    private fun onNetworkSwitched(reason: String) {
        // 节流：5s 内多次抖动只触发一次
        val now = System.currentTimeMillis()
        if (now - lastNetSwitchAt < 5000) return
        if (viewerCount == 0) return
        lastNetSwitchAt = now
        Log.d(TAG, "📶 网络切换($reason)，处理 $viewerCount 个会话")
        mainScope.launch { restartAllIceForNetworkSwitch() }
    }

    /** WebSocket 重连成功后由外部调用：所有 P2P 会话做 ICE Restart（信令通道刚恢复） */
    fun onWebSocketReconnected() {
        if (viewerCount == 0) return
        Log.d(TAG, "🔌 WebSocket 重连，重连所有 P2P 会话")
        mainScope.launch { restartAllIceForNetworkSwitch() }
    }

    private fun restartAllIceForNetworkSwitch() {
        val sessions = synchronized(viewerSessions) { viewerSessions.toMap() }
        for ((pcId, pc) in sessions) {
            // 切网是「新一次」重连，重置该会话的 ICE 重试计数
            iceRetryCount[pcId] = 0
            val state = pc.connectionState()
            if (state == PeerConnection.PeerConnectionState.CLOSED ||
                state == PeerConnection.PeerConnectionState.FAILED) {
                // 无法 ICE Restart：拆掉并让 PC 重新发起
                removeViewerSession(pcId, notifyPC = false)
                WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_HANGUP", "network_switch_reconnect", pcId)
            } else {
                if (isOnCellular) forceRelayPeerIds.add(pcId)
                retryIceConnection(pcId, pc)
            }
        }
    }

    // MARK: - 传输策略

    private fun effectiveForceRelay(pcId: String): Boolean {
        if (forceRelay) return true
        return isOnCellular || peerNetworkType[pcId] == "cellular" || forceRelayPeerIds.contains(pcId)
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

        when (type) {
            "VIEWER_CONNECTED" -> Log.d(TAG, "✅ PC $fromDevice 已收到画面")
            "VIEWER_DISCONNECTED" -> Log.d(TAG, "🔌 PC $fromDevice 断开")
            "WEBRTC_REQUEST" -> {
                peerNetworkType[fromDevice] = (message["networkType"] as? String) ?: "unknown"
                if (!isReadyForViewers) {
                    WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_REJECT", "not_ready", fromDevice)
                    return
                }
                createViewerSession(fromDevice)
            }
            "WEBRTC_SDP" -> {
                val sdpType = message["sdpType"] as? String ?: ""
                val sdp = message["sdp"] as? String ?: ""
                if (sdpType == "answer") handleRemoteAnswer(sdp, fromDevice)
            }
            "WEBRTC_ICE" -> handleRemoteIce(message, fromDevice)
            "WEBRTC_HANGUP" -> removeViewerSession(fromDevice, notifyPC = false)
        }
    }

    private fun handleRemoteAnswer(sdp: String, pcId: String) {
        val pc = synchronized(viewerSessions) { viewerSessions[pcId] } ?: return
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

    fun createViewerSession(pcId: String) {
        val ds = dataSource ?: run { Log.e(TAG, "❌ dataSource 为空"); return }
        val factory = ds.p2pFactory ?: run { Log.e(TAG, "❌ factory 为空"); return }

        synchronized(viewerSessions) {
            viewerSessions[pcId]?.let { existing ->
                val s = existing.connectionState()
                if (s == PeerConnection.PeerConnectionState.NEW ||
                    s == PeerConnection.PeerConnectionState.CONNECTING) {
                    Log.w(TAG, "⚠️ PC $pcId 会话建立中，忽略重复请求")
                    return
                }
            }
        }
        // 已存在旧会话（非建立中）→ 拆掉重建
        if (synchronized(viewerSessions) { viewerSessions.containsKey(pcId) }) {
            removeViewerSession(pcId, notifyPC = false)
        }

        if (viewerCount >= maxViewers) {
            Log.e(TAG, "❌ 已达最大观看人数($maxViewers)，拒绝 $pcId")
            WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_REJECT", "max_viewers_reached", pcId)
            return
        }

        val videoTrack = ds.p2pLocalVideoTrack ?: run {
            Log.e(TAG, "❌ 视频轨未就绪，无法创建会话")
            return
        }

        // ⚠️ 不加公共 STUN 兜底：ICE 节点一律用后端登录下发的自有 STUN/TURN（coturn）。
        //   后端没下发就空列表（同局域网 host 候选仍可直连），绝不连第三方免费节点。
        val servers = loadIceServers()
        if (servers.isEmpty()) {
            Log.w(TAG, "⚠️ 后端未下发 iceServers，无 STUN/TURN（仅局域网 host 候选可直连）")
        }
        val turnCount = servers.count { it.urls.any { u -> u.startsWith("turn:") } }
        Log.d(TAG, "🔔 ICE 服务器 ${servers.size} 个 (TURN=$turnCount)")

        val cfg = PeerConnection.RTCConfiguration(servers)
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        cfg.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        cfg.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        cfg.iceCandidatePoolSize = 2
        // P0-2 对齐 iOS：ICE 稳定性参数（8s 无收包才判 disconnected，弱网更耐抖）
        cfg.iceConnectionReceivingTimeout = 8000
        cfg.iceBackupCandidatePairPingInterval = 2000
        val useRelay = effectiveForceRelay(pcId)
        cfg.iceTransportsType = if (useRelay) PeerConnection.IceTransportsType.RELAY
                                else PeerConnection.IceTransportsType.ALL
        Log.d(TAG, "🔔 创建会话 $pcId，传输策略=${if (useRelay) "relay(TURN)" else "all(直连优先)"}")

        val observer = createObserver(pcId)
        val newPC = factory.createPeerConnection(cfg, observer) ?: run {
            Log.e(TAG, "❌ 创建 PeerConnection 失败 $pcId")
            return
        }

        val sender = newPC.addTrack(videoTrack, listOf("s0"))
        synchronized(viewerSessions) {
            viewerSessions[pcId] = newPC
            viewerSenders[pcId] = sender
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
                newPC.setLocalDescription(SilentSdpObserver, sdp)
                WebSocketManager.instance.sendWebRTCSignalingSDP("offer", sdp.description, pcId)
                Log.d(TAG, "📤 已发送 Offer 给 $pcId")
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
            currentViewerCount = viewerSessions.size
            removed
        }
        try { pc?.close() } catch (_: Exception) {}
        pendingRemoteIce.remove(pcId)
        pendingIceRestart.remove(pcId)
        iceRetryCount.remove(pcId)
        forceRelayPeerIds.remove(pcId)
        peerNetworkType.remove(pcId)
        Log.d(TAG, "🔌 移除会话 $pcId，剩余 $viewerCount")
    }

    fun closeAllViewerSessions(notifyPC: Boolean) {
        val sessions = synchronized(viewerSessions) {
            val copy = viewerSessions.toMap()
            viewerSessions.clear()
            viewerSenders.clear()
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
                    pc.setLocalDescription(SilentSdpObserver, sdp)
                    WebSocketManager.instance.sendWebRTCSignalingSDP("offer", sdp.description, pcId)
                    Log.d(TAG, "🔄 ICE Restart Offer 已发送 $pcId (${cur + 1}/$MAX_ICE_RETRIES)")
                }
                override fun onCreateFailure(e: String?) { Log.e(TAG, "ICE Restart Offer 失败 $pcId: $e") }
                override fun onSetSuccess() {}
                override fun onSetFailure(e: String?) {}
            }, cons)
        } else {
            Log.e(TAG, "❌ $pcId ICE 重试耗尽，断开（静态连接方式，不回退 SRS）")
            iceRetryCount.remove(pcId)
            removeViewerSession(pcId, notifyPC = false)
            WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_HANGUP", "ice_failed", pcId)
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

    private object SilentSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) { Log.e(TAG, "SDP创建失败: $e") }
        override fun onSetFailure(e: String?) { Log.e(TAG, "SDP设置失败: $e") }
    }
}
