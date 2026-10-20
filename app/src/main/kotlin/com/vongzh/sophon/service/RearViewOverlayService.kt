package com.vongzh.sophon.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.vongzh.sophon.R
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.domain.model.GearState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 悬浮窗矩阵 · 后视角小窗 (RearViewOverlayService)
 *
 * 挂入 R 挡时以右上角 480x270 小窗直出后摄实时画面，替代传统流媒体后视镜；
 * 原厂 360 影像激活时无条件避让，避免与原厂倒车影像抢占屏幕。
 *
 * 由 SophonPreferences.isRearViewOverlayEnabled 控制总开关（默认关闭）。
 */
class RearViewOverlayService : Service() {

    companion object {
        private const val TAG = "Sophon.RearViewOverlay"
        private const val WIDTH_PX = 480
        private const val HEIGHT_PX = 270
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var container: FrameLayout? = null
    private var surfaceHost: FrameLayout? = null
    private var tvTitle: TextView? = null
    private var isAttached = false
    private var observationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        buildLayout()
        startObservation()
    }

    private fun buildLayout() {
        val density = resources.displayMetrics.density
        container = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.sp_bg_quad_frame)
            elevation = 16f * density
            visibility = View.GONE
            clipToOutline = true
        }

        surfaceHost = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container?.addView(surfaceHost)

        tvTitle = TextView(this).apply {
            text = "后视 · R 挡"
            setTextColor(getColor(R.color.sp_osd_tx))
            setBackgroundResource(R.drawable.sp_bg_osd_chip)
            textSize = 11f
            val ph = (10 * density).toInt()
            val pv = (4 * density).toInt()
            setPadding(ph, pv, ph, pv)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val titleParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val m = (10 * density).toInt()
            setMargins(m, m, m, m)
        }
        container?.addView(tvTitle, titleParams)
    }

    private fun startObservation() {
        val bus = SophonApp.vehicleSignalBus
        observationJob = serviceScope.launch {
            // 原厂 360 影像激活时最高优先级避让
            launch {
                bus.isAvmActive.collectLatest { isAvm ->
                    if (isAvm) hideWindow("AVM preemption")
                }
            }
            // R 挡联动
            launch {
                bus.gearState.collectLatest { gear ->
                    val enabled = SophonApp.preferences.isRearViewOverlayEnabled
                    if (!enabled || bus.isAvmActive.value) {
                        hideWindow("Disabled or AVM active")
                        return@collectLatest
                    }
                    if (gear == GearState.REVERSE) showWindow() else hideWindow("Gear: $gear")
                }
            }
        }
    }

    private fun showWindow() {
        val wm = windowManager ?: return
        val view = container ?: return
        if (isAttached) return

        surfaceHost?.removeAllViews()
        val sv = SurfaceView(this)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                SophonCoreService.instance?.attachPreviewSurface(
                    com.vongzh.sophon.domain.model.CameraPosition.BACK, holder.surface
                )
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                SophonCoreService.instance?.attachPreviewSurface(
                    com.vongzh.sophon.domain.model.CameraPosition.BACK, holder.surface
                )
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                SophonCoreService.instance?.attachPreviewSurface(
                    com.vongzh.sophon.domain.model.CameraPosition.BACK, null
                )
            }
        })
        surfaceHost?.addView(
            sv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val params = overlayParams().apply {
            gravity = Gravity.TOP or Gravity.END
            x = 32
            y = 96
        }

        try {
            view.alpha = 0f
            view.scaleX = 0.92f
            view.scaleY = 0.92f
            view.visibility = View.VISIBLE
            wm.addView(view, params)
            isAttached = true
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .start()
            Log.d(TAG, "Rear view overlay shown (REVERSE gear).")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach rear view overlay", e)
        }
    }

    private fun hideWindow(reason: String) {
        if (!isAttached) return
        try {
            container?.visibility = View.GONE
            surfaceHost?.removeAllViews()
            SophonCoreService.instance?.attachPreviewSurface(
                com.vongzh.sophon.domain.model.CameraPosition.BACK, null
            )
            windowManager?.removeViewImmediate(container)
            Log.d(TAG, "Rear view overlay hidden ($reason).")
        } catch (e: Exception) {
            Log.w(TAG, "Error removing rear view overlay", e)
        } finally {
            isAttached = false
        }
    }

    private fun overlayParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WIDTH_PX,
        HEIGHT_PX,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    override fun onDestroy() {
        hideWindow("Service destroy")
        observationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
