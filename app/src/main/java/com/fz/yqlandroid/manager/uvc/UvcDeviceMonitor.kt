package com.fz.yqlandroid.manager.uvc

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.utils.CameraUtils
import com.jiangdg.usb.USBMonitor
import java.util.concurrent.CopyOnWriteArraySet

/**
 * ⭐ 外接 OTG 摄像头（UVC）设备监视器 —— 第四十八章，与自带摄像头链路完全分离。
 *
 * 职责：USB 插拔广播注册、UVC 设备枚举（AUSBC 内部按 USB class 14 过滤）、
 * USB 权限弹窗、向 [UvcVideoCapturer] 提供已授权的 UsbControlBlock。
 *
 * 仅登录页选「外接OTG」（token_prefs.camera_mode == "otg"）后由 UvcVideoCapturer 启动，
 * 自带摄像头模式下本类零触碰。
 *
 * 实现注意：AUSBC 的 USBMonitor 是进程级单例（USBMonitor.getInstance），
 * 因此这里只做 register/unRegister，绝不调 destroy()（会毁掉单例，之后再也起不来）。
 */
object UvcDeviceMonitor {

    private const val TAG = "UvcDeviceMonitor"

    interface Listener {
        /** 设备已授权可用（USB 权限已拿到，ctrlBlock 可直接开 UVCCamera） */
        fun onUvcDeviceReady(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock)
        /** 设备被拔出/断开 */
        fun onUvcDeviceGone(device: UsbDevice)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    private var client: MultiCameraClient? = null
    private var appContext: Context? = null

    /** 当前已授权设备（采集器重启场景直接复用，无需再等广播） */
    @Volatile private var readyDevice: UsbDevice? = null
    @Volatile private var readyCtrlBlock: USBMonitor.UsbControlBlock? = null

    /** 已发起过权限请求的设备（deviceId），防重复弹窗 */
    private val permissionRequested = HashSet<Int>()

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** 当前已授权设备，无则 null */
    fun currentReady(): Pair<UsbDevice, USBMonitor.UsbControlBlock>? {
        val d = readyDevice ?: return null
        val cb = readyCtrlBlock ?: return null
        return d to cb
    }

    /**
     * 启动监视（幂等）。注册 USB 插拔广播；对已插着的设备主动补一轮权限请求
     * （部分 ROM 的 USBMonitor register 不回放已插设备的 onAttach）。
     */
    fun start(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (client == null) {
            client = MultiCameraClient(ctx, connectCallback)
        }
        client?.register()
        // 已插着的设备主动请求权限（register 的 attach 回放不一定来）
        mainHandler.postDelayed({ requestPermissionForAttached() }, 500)
        Log.d("meidui", "🔌 [OTG] UvcDeviceMonitor 启动（注册USB广播）")
    }

    /** 停止监视：只注销广播，不 destroy 单例 USBMonitor（见类注释） */
    fun stop() {
        try { client?.unRegister() } catch (e: Exception) {
            Log.w(TAG, "unRegister failed: ${e.message}")
        }
        permissionRequested.clear()
        Log.d("meidui", "🔌 [OTG] UvcDeviceMonitor 停止（注销USB广播）")
    }

    private fun requestPermissionForAttached() {
        val c = client ?: return
        val devices = try { c.getDeviceList() } catch (e: Exception) { null } ?: return
        // ⚠️ getDeviceList 返回全部 USB 设备（含 OTG 集线器/键鼠），必须过滤出 UVC 摄像头(class 14)
        val camera = devices.firstOrNull { CameraUtils.isUsbCamera(it) }
        if (camera == null) {
            Log.d("meidui", "🔌 [OTG] 未检测到外接UVC摄像头（USB设备${devices.size}个均非摄像头；请确认手机支持OTG且已插好）")
            return
        }
        requestPermissionOnce(camera)
    }

    private fun requestPermissionOnce(device: UsbDevice) {
        if (readyDevice != null) return   // 已有可用设备，不再抢
        if (!permissionRequested.add(device.deviceId)) return
        Log.d("meidui", "🔌 [OTG] 请求USB权限: ${device.productName ?: device.deviceName}")
        client?.requestPermission(device)
    }

    private fun toast(msg: String) {
        val ctx = appContext ?: return
        mainHandler.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    private val connectCallback = object : IDeviceConnectCallBack {
        override fun onAttachDev(device: UsbDevice?) {
            device ?: return
            Log.d("meidui", "🔌 [OTG] 检测到外接摄像头插入: ${device.productName ?: device.deviceName}")
            requestPermissionOnce(device)
        }

        override fun onDetachDec(device: UsbDevice?) {
            device ?: return
            permissionRequested.remove(device.deviceId)
            if (readyDevice?.deviceId == device.deviceId) {
                readyDevice = null
                readyCtrlBlock = null
                Log.d("meidui", "🔌 [OTG] 外接摄像头已拔出: ${device.productName ?: device.deviceName}")
                toast("外接摄像头已断开")
                listeners.forEach { it.onUvcDeviceGone(device) }
            }
        }

        override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            device ?: return
            ctrlBlock ?: return
            readyDevice = device
            readyCtrlBlock = ctrlBlock
            Log.d("meidui", "🔌 [OTG] USB权限已授予，设备可用: ${device.productName ?: device.deviceName}")
            toast("外接摄像头已连接")
            listeners.forEach { it.onUvcDeviceReady(device, ctrlBlock) }
        }

        override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            // 与拔出同处理（授权撤销/异常断开）
            onDetachDec(device)
        }

        override fun onCancelDev(device: UsbDevice?) {
            device ?: return
            // 用户拒绝授权：清除标记，重插或重新进入推流页可再弹
            permissionRequested.remove(device.deviceId)
            Log.d("meidui", "🔌 [OTG] 用户拒绝了USB权限: ${device.productName ?: device.deviceName}")
            toast("未授权访问外接摄像头")
        }
    }
}
