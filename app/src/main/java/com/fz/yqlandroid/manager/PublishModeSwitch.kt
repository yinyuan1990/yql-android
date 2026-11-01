package com.fz.yqlandroid.manager

import android.content.Context

/**
 * 总后台「用户管理」/ PC 相机设定下发推流方式（CONFIG_UPDATE ptype=publish_mode，mode = "srt" / "srs"）：
 * 推流页退回登录页，登录页按新方式自动登录一次（一次性；以后手动登录按哪个按钮走哪种）。
 */
object PublishModeSwitch {
    /** 登录页读到非空就按它自动登录一次（"srt" = 登录 / "srs" = 老版本登录），用完清空 */
    @Volatile var pendingMode: String? = null

    /** 本次运行最近一次登录成功的密码：没勾「记住密码」时自动重登用（只在内存，不落盘） */
    @Volatile var lastUsername: String = ""
    @Volatile var lastPassword: String = ""

    fun currentMode(context: Context): String =
        if (context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE).getBoolean("force_webrtc", true)) "srs" else "srt"

    fun normalize(raw: Any?): String? = when (raw?.toString()?.lowercase()) {
        "srs" -> "srs"
        "srt" -> "srt"
        else -> null
    }

    fun modeName(mode: String): String = if (mode == "srs") "省流模式(SRS)" else "SRT"
}
