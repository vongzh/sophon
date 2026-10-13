package com.vongz.sophon.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vongz.sophon.R
import com.vongz.sophon.SophonApp
import com.vongz.sophon.core.storage.RamRingBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 极视核心视觉与录制守护服务 (SophonCoreService)
 *
 * 【架构隔离】
 * 运行于独立的 `:core` 后台守护进程中。
 * 即使 UI 交互进程发生任何 OOM 或崩溃，核心行车记录与补盲管线均不受任何干扰。
 */
class SophonCoreService : Service() {

    companion object {
        private const val TAG = "Sophon.CoreService"
        private const val CHANNEL_ID = "sophon_core_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ramRingBuffer = RamRingBuffer()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SophonCoreService onCreate in PID: ${android.os.Process.myPid()}")
        startForegroundServiceInternal()
        observeStorageHealth()
    }

    private fun startForegroundServiceInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_core_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_core_service_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.recording_active))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun observeStorageHealth() {
        val storage = SophonApp.storageVault
        serviceScope.launch {
            storage.isUsbMounted.collectLatest { isMounted ->
                if (!isMounted) {
                    Log.w(TAG, "USB storage unmounted! Halting disk writer. RAM Ring Buffer enters standby.")
                    ramRingBuffer.clear()
                } else {
                    Log.i(TAG, "USB storage mounted. Ready for streaming write.")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
