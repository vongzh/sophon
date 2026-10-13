package com.vongz.sophon.core.vhal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.vongz.sophon.service.SophonCoreService
import com.vongz.sophon.ui.statusbar.StatusBarCapsuleService

/**
 * 车机通电开机静默自启广播接收器 (BootCompletedReceiver)
 *
 * 【无感自启体验】
 * 1. 监听系统 BOOT_COMPLETED、LOCKED_BOOT_COMPLETED 与包替换广播；
 * 2. 车辆上电启动时静默拉起核心录制服务与顶部状态栏胶囊；
 * 3. 绝不弹出全屏界面打扰原厂车机桌面与导航，仅在状态栏亮起录制小红点。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Sophon.BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Boot broadcast received: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.i(TAG, "Vehicle booted up. Silently launching Sophon background services...")

            // 1. 启动核心录制服务 (:core 独立进程)
            val coreIntent = Intent(context, SophonCoreService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(coreIntent)
            } else {
                context.startService(coreIntent)
            }

            // 2. 启动顶部状态栏胶囊服务
            val capsuleIntent = Intent(context, StatusBarCapsuleService::class.java)
            context.startService(capsuleIntent)

            Log.i(TAG, "Silent boot startup completed successfully.")
        }
    }
}
