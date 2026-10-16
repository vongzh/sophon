package com.vongzh.sophon.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * 悬浮窗矩阵统一启停控制器 (OverlayMatrixController)
 *
 * 依据 SophonPreferences 的三个开关，对齐悬浮窗服务的实际运行状态：
 * 开则拉起、关则停止。设置保存后与 MainActivity 启动时各调用一次，保证幂等。
 */
object OverlayMatrixController {

    private const val TAG = "Sophon.OverlayMatrix"

    fun apply(context: Context) {
        val prefs = com.vongzh.sophon.SophonApp.preferences

        sync(context, prefs.isRearViewOverlayEnabled, RearViewOverlayService::class.java)
        sync(context, prefs.isStatusOverlayEnabled, VehicleStatusOverlayService::class.java)
        sync(context, prefs.isAssistBallEnabled, AssistBallOverlayService::class.java)
    }

    private fun sync(context: Context, enabled: Boolean, service: Class<*>) {
        try {
            if (enabled) {
                if (!canDrawOverlays(context)) {
                    Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted, skip starting ${service.simpleName}")
                    return
                }
                context.startService(Intent(context, service))
            } else {
                context.stopService(Intent(context, service))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync overlay service ${service.simpleName}: ${e.message}")
        }
    }

    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)
}
