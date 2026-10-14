package com.vongzh.sophon.core.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.vongzh.sophon.R
import com.vongzh.sophon.ui.MainActivity

/**
 * 车载通知栏常驻卡片与状态图标管理器 (SophonNotificationManager)
 *
 * 【座舱常驻体验】
 * 1. 顶部状态栏常驻录制中图标 (ic_status_record)；
 * 2. 下拉通知栏车载深色定制卡片，实时展示录制状态、时长、U 盘健康度；
 * 3. 快捷按键直达：一键抓拍、紧急加锁、呼出主控。
 */
class SophonNotificationManager(
    private val context: Context
) {

    companion object {
        const val CHANNEL_ID = "sophon_ongoing_status"
        const val NOTIFICATION_ID = 2001

        const val ACTION_SNAPSHOT = "com.vongzh.sophon.action.NOTIF_SNAPSHOT"
        const val ACTION_LOCK_EVENT = "com.vongzh.sophon.action.NOTIF_LOCK_EVENT"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_core_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_core_service_desc)
                setShowBadge(true)
                enableVibration(false)
                enableLights(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 构建或更新常驻通知
     *
     * @param isRecording 是否处于行车录制中
     * @param durationSec 连续录制时长 (秒)
     * @param isUsbMounted 外置 U 盘是否就绪
     */
    fun buildOngoingNotification(
        isRecording: Boolean,
        durationSec: Long,
        isUsbMounted: Boolean
    ): Notification {
        val remoteViews = RemoteViews(context.packageName, R.layout.notification_sophon_ongoing)

        // 1. 格式化时长
        val hours = durationSec / 3600
        val minutes = (durationSec % 3600) / 60
        val seconds = durationSec % 60
        val timeStr = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        // 2. 状态文本
        if (isRecording) {
            remoteViews.setTextViewText(R.id.tv_notif_status, "● 录制中 $timeStr")
            remoteViews.setTextColor(R.id.tv_notif_status, context.getColor(R.color.accent_danger))
        } else {
            remoteViews.setTextViewText(R.id.tv_notif_status, "○ 待机守护中")
            remoteViews.setTextColor(R.id.tv_notif_status, context.getColor(R.color.text_secondary))
        }

        // 3. 存储状态
        val usbStr = if (isUsbMounted) "U盘状态: 正常已挂载" else "U盘状态: 未插入 (待机模式)"
        remoteViews.setTextViewText(R.id.tv_notif_storage, usbStr)

        // 4. 绑定快捷按钮 PendingIntent
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT

        // 一键抓拍
        val snapshotIntent = Intent(ACTION_SNAPSHOT).setPackage(context.packageName)
        val pSnapshot = PendingIntent.getBroadcast(context, 101, snapshotIntent, flag)
        remoteViews.setOnClickPendingIntent(R.id.btn_notif_snapshot, pSnapshot)

        // 紧急加锁
        val lockIntent = Intent(ACTION_LOCK_EVENT).setPackage(context.packageName)
        val pLock = PendingIntent.getBroadcast(context, 102, lockIntent, flag)
        remoteViews.setOnClickPendingIntent(R.id.btn_notif_lock, pLock)

        // 点击打开主页
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pOpen = PendingIntent.getActivity(context, 103, openIntent, flag)
        remoteViews.setOnClickPendingIntent(R.id.btn_notif_open, pOpen)

        // 5. 构建 Notification
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_record)
            .setCustomContentView(remoteViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(pOpen)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    fun updateNotification(isRecording: Boolean, durationSec: Long, isUsbMounted: Boolean) {
        val notif = buildOngoingNotification(isRecording, durationSec, isUsbMounted)
        notificationManager.notify(NOTIFICATION_ID, notif)
    }
}
