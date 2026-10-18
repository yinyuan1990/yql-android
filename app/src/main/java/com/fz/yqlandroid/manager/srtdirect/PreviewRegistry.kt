package com.fz.yqlandroid.manager.srtdirect

import android.view.Surface
import java.util.concurrent.CopyOnWriteArraySet

/**
 * §134 屏幕预览 Surface 登记处（拷自 test-android，照 silu `UvcPreviewSurfaceRegistry`）。
 *
 * 推流页可见时登记 SurfaceView 的 Surface，熄屏/切后台时撤销；摄像头会话监听变化：
 * 有预览就同时输出给预览，没有就输出给看不见的后台替身（见 SdCameraSource.standbySize）。
 */
object PreviewRegistry {
    fun interface Listener {
        fun onPreviewChanged(surface: Surface?)
    }

    @Volatile var surface: Surface? = null
        private set
    private val listeners = CopyOnWriteArraySet<Listener>()

    fun set(s: Surface?) {
        surface = s
        listeners.forEach { it.onPreviewChanged(s) }
    }

    /** 当前可用的预览 Surface（已失效的视为没有） */
    fun current(): Surface? = surface?.takeIf { it.isValid }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
}
