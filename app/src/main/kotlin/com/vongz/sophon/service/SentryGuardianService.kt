package com.vongz.sophon.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.vongz.sophon.SophonApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 智能哨兵事件守护服务 (SentryGuardianService)
 *
 * 【低功耗驻车监控】
 * 驻车状态下监听车身微震动与移动侦测事件，仅在触发异常时唤醒录制锁存。
 */
class SentryGuardianService : Service() {

    companion object {
        private const val TAG = "Sophon.SentryGuardian"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SentryGuardianService started.")
        observeSentryEvents()
    }

    private fun observeSentryEvents() {
        val bus = SophonApp.vehicleSignalBus
        serviceScope.launch {
            bus.sentryTriggerEvents.collectLatest { timestamp ->
                Log.w(TAG, "Sentry trigger detected at $timestamp! Triggering high-priority event lock...")
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
