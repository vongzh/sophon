package com.vongz.sophon.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.vongz.sophon.SophonApp
import com.vongz.sophon.core.notification.SophonNotificationManager
import com.vongz.sophon.core.storage.RamRingBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 极视核心视觉与录制守护服务 (SophonCoreService)
 *
 * 【架构隔离】
 * 运行于独立的 `:core` 后台守护进程中。
 * 顶部状态栏常驻图标，通知栏常驻卡片，实时感知 U 盘状态与录制时长。
 */
class SophonCoreService : Service() {

    companion object {
        private const val TAG = "Sophon.CoreService"

        const val ACTION_TRIGGER_SNAPSHOT = "com.vongz.sophon.action.TRIGGER_SNAPSHOT"
        const val ACTION_TRIGGER_LOCK = "com.vongz.sophon.action.TRIGGER_LOCK"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ramRingBuffer = RamRingBuffer()

    private lateinit var notifManager: SophonNotificationManager
    private var isRecordingActive = false
    private var recordingDurationSec = 0L

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SophonCoreService onCreate in PID: ${android.os.Process.myPid()}")

        notifManager = SophonNotificationManager(this)
        startForegroundServiceInternal()
        observeStorageHealth()
        startNotificationUpdateLoop()
    }

    private fun startForegroundServiceInternal() {
        val initialNotif = notifManager.buildOngoingNotification(
            isRecording = false,
            durationSec = 0L,
            isUsbMounted = SophonApp.storageVault.isUsbMounted.value
        )
        startForeground(SophonNotificationManager.NOTIFICATION_ID, initialNotif)
    }

    private fun observeStorageHealth() {
        val storage = SophonApp.storageVault
        serviceScope.launch {
            storage.isUsbMounted.collectLatest { isMounted ->
                if (!isMounted) {
                    Log.w(TAG, "USB storage unmounted! Halting disk writer. RAM Ring Buffer enters standby.")
                    ramRingBuffer.clear()
                    isRecordingActive = false
                } else {
                    Log.i(TAG, "USB storage mounted. Ready for streaming write.")
                    isRecordingActive = true
                }
                notifManager.updateNotification(isRecordingActive, recordingDurationSec, isMounted)
            }
        }
    }

    private fun startNotificationUpdateLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(1000L)
                if (isRecordingActive) {
                    recordingDurationSec++
                }
                notifManager.updateNotification(
                    isRecording = isRecordingActive,
                    durationSec = recordingDurationSec,
                    isUsbMounted = SophonApp.storageVault.isUsbMounted.value
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIGGER_SNAPSHOT -> {
                Log.i(TAG, "Handling snapshot trigger from notification...")
                // 执行拍照抓拍
            }
            ACTION_TRIGGER_LOCK -> {
                Log.i(TAG, "Handling event lock trigger from notification...")
                // 标记加锁事件
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "SophonCoreService onDestroy")
        serviceScope.cancel()
        ramRingBuffer.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
