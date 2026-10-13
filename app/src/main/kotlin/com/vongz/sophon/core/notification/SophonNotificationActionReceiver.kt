package com.vongz.sophon.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.vongz.sophon.service.SophonCoreService

/**
 * 通知栏快捷动作广播接收器 (SophonNotificationActionReceiver)
 */
class SophonNotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Sophon.NotifAction"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Notification action received: $action")

        when (action) {
            SophonNotificationManager.ACTION_SNAPSHOT -> {
                Toast.makeText(context, "正在执行四向画面即时抓拍...", Toast.LENGTH_SHORT).show()
                val serviceIntent = Intent(context, SophonCoreService::class.java).apply {
                    this.action = SophonCoreService.ACTION_TRIGGER_SNAPSHOT
                }
                context.startService(serviceIntent)
            }
            SophonNotificationManager.ACTION_LOCK_EVENT -> {
                Toast.makeText(context, "已触发紧急加锁，当前视频已保护！", Toast.LENGTH_SHORT).show()
                val serviceIntent = Intent(context, SophonCoreService::class.java).apply {
                    this.action = SophonCoreService.ACTION_TRIGGER_LOCK
                }
                context.startService(serviceIntent)
            }
        }
    }
}
