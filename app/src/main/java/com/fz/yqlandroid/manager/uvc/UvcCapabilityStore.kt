package com.fz.yqlandroid.manager.uvc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ⭐ 第四十八章：OTG(UVC) 相机能力快照——供画面层叠显。
 *
 * 背景：OTG 摄像头占用 USB 口无法连 adb，PC 端调节面板改造需要知道该 UVC 设备
 * 实际支持哪些 软件/硬件 参数及上下限。UvcVideoCapturer 开流后枚举能力写入这里，
 * StreamingScreen 在 OTG 模式把它叠显到预览画面上（同时也打进 meidui 日志 → OTG 日志上报后端）。
 */
object UvcCapabilityStore {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun set(newLines: List<String>) { _lines.value = newLines }
    fun clear() { _lines.value = emptyList() }
}
