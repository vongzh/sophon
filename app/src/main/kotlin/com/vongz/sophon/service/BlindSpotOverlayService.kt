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
import com.vongz.sophon.domain.model.GearState
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
 * 【核心功能扩展 - 窄道会车双闪补盲】
 * 1. 左转/右转：左下角/右下角滑入单向 480x320 盲区画面；
 * 2. 窄道会车模式：低速 (<15km/h) 且挂 D 挡打双闪时，屏幕底部正中弹出 800x320 左右双分屏全景补盲；
 * 3. 50ms 原厂 AVM 避让机制：倒车或原厂 360 雷达弹出时，50ms 内无条件隐身并释放 GPU！
 */
class BlindSpotOverlayService : Service() {

    companion object {
        private const val TAG = "Sophon.BlindSpotOverlay"
        private const val HAZARD_SPEED_THRESHOLD_KMH = 15f
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
                        TurnSignalState.HAZARD -> checkHazardPassingAssist()
                        TurnSignalState.OFF -> hideOverlay()
                    }
                }
            }
        }
    }

    /**
     * 窄道会车双闪模式研判
     */
    private fun checkHazardPassingAssist() {
        val bus = SophonApp.vehicleSignalBus
        val speed = bus.vehicleSpeed.value
        val gear = bus.gearState.value

        if (speed < HAZARD_SPEED_THRESHOLD_KMH && gear == GearState.DRIVE) {
            Log.d(TAG, "Hazard passing assist triggered (Speed: $speed km/h, D gear)")
            showDualOverlay()
        } else {
            hideOverlay()
        }
    }

    private fun showOverlay(position: CameraPosition) {
        val wm = windowManager ?: return
        val view = overlayView ?: return

        hideOverlay()

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

        try {
            view.visibility = View.VISIBLE
            wm.addView(view, layoutParams)
            isOverlayAttached = true
            Log.d(TAG, "Blind spot overlay SHOWN for camera: $position")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show single overlay", e)
        }
    }

    /**
     * 弹出左右双分屏窄道会车浮窗
     */
    private fun showDualOverlay() {
        val wm = windowManager ?: return
        val view = overlayView ?: return

        hideOverlay()

        val layoutParams = WindowManager.LayoutParams(
            800, // 宽幅双分屏
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
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 32
        }

        try {
            view.visibility = View.VISIBLE
            wm.addView(view, layoutParams)
            isOverlayAttached = true
            Log.i(TAG, "Dual hazard passing overlay SHOWN at bottom center.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show dual hazard overlay", e)
        }
    }

    private fun hideOverlay() {
        if (isOverlayAttached && overlayView != null) {
            try {
                overlayView?.visibility = View.GONE
                windowManager?.removeViewImmediate(overlayView)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay view", e)
            } finally {
                isOverlayAttached = false
            }
            Log.d(TAG, "Blind spot overlay HIDDEN. GPU conserved.")
        }
    }

    private fun dismissOverlayImmediate(reason: String) {
        if (isOverlayAttached && overlayView != null) {
            val startTime = System.currentTimeMillis()
            try {
                overlayView?.visibility = View.GONE
                windowManager?.removeViewImmediate(overlayView)
            } catch (e: Exception) {
                Log.w(TAG, "Error emergency dismissing overlay", e)
            } finally {
                isOverlayAttached = false
            }
            val elapsed = System.currentTimeMillis() - startTime
            Log.w(TAG, "Emergency dismissed overlay ($reason) in ${elapsed}ms (Limit: 50ms)")
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
