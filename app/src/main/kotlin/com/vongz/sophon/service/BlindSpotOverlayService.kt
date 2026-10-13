package com.vongz.sophon.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.vongz.sophon.SophonApp
import com.vongz.sophon.domain.model.CameraPosition
import com.vongz.sophon.domain.model.TurnSignalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 盲区补盲悬浮窗服务 (BlindSpotOverlayService)
 *
 * 【性能红线 - 底线一 (Zero Impact on IVI)】
 * 1. 50ms 原厂 AVM 避让机制：当接收到倒车或 360 全景信号时，窗口瞬间隐身并解绑；
 * 2. GPU 节能休眠联动：当悬浮窗隐藏时，通知渲染管线挂起，释放 GPU 资源给车机系统。
 */
class BlindSpotOverlayService : Service() {

    companion object {
        private const val TAG = "Sophon.BlindSpotOverlay"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isOverlayAttached = false

    private var observationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        initWindowLayout()
        startSignalObservation()
    }

    private fun initWindowLayout() {
        overlayView = View(this).apply {
            setBackgroundColor(0xCC0D0F12.toInt())
            visibility = View.GONE
        }
    }

    private fun startSignalObservation() {
        val bus = SophonApp.vehicleSignalBus

        observationJob = serviceScope.launch {
            launch {
                bus.isAvmActive.collectLatest { isAvm ->
                    if (isAvm) {
                        dismissOverlayImmediate("AVM Active Emergency Preemption")
                    }
                }
            }

            launch {
                bus.turnSignalState.collectLatest { turnState ->
                    if (bus.isAvmActive.value) {
                        return@collectLatest
                    }

                    when (turnState) {
                        TurnSignalState.LEFT -> showOverlay(CameraPosition.LEFT)
                        TurnSignalState.RIGHT -> showOverlay(CameraPosition.RIGHT)
                        TurnSignalState.OFF, TurnSignalState.HAZARD -> hideOverlay()
                    }
                }
            }
        }
    }

    private fun showOverlay(position: CameraPosition) {
        val wm = windowManager ?: return
        val view = overlayView ?: return

        if (!isOverlayAttached) {
            val layoutParams = WindowManager.LayoutParams(
                480,
                320,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = if (position == CameraPosition.LEFT)
                    Gravity.BOTTOM or Gravity.START
                else
                    Gravity.BOTTOM or Gravity.END
                x = 32
                y = 32
            }

            view.visibility = View.VISIBLE
            wm.addView(view, layoutParams)
            isOverlayAttached = true
            Log.d(TAG, "Blind spot overlay SHOWN for camera: $position")
        }
    }

    private fun hideOverlay() {
        if (isOverlayAttached && overlayView != null) {
            overlayView?.visibility = View.GONE
            windowManager?.removeViewImmediate(overlayView)
            isOverlayAttached = false
            Log.d(TAG, "Blind spot overlay HIDDEN. Conserving GPU.")
        }
    }

    private fun dismissOverlayImmediate(reason: String) {
        if (isOverlayAttached && overlayView != null) {
            val startTime = System.currentTimeMillis()
            overlayView?.visibility = View.GONE
            windowManager?.removeViewImmediate(overlayView)
            isOverlayAttached = false
            val elapsed = System.currentTimeMillis() - startTime
            Log.w(TAG, "Dismissed overlay ($reason) in ${elapsed}ms (Limit: 50ms)")
        }
    }

    override fun onDestroy() {
        observationJob?.cancel()
        dismissOverlayImmediate("Service Destroy")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
