package com.vongzh.sophon.service

import android.app.Service
import android.content.Intent
import android.graphics.Color
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
import android.widget.LinearLayout
import android.widget.TextView
import com.vongzh.sophon.R
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.core.config.SophonPreferences
import com.vongzh.sophon.domain.model.CameraPosition
import com.vongzh.sophon.domain.model.DoorState
import com.vongzh.sophon.domain.model.GearState
import com.vongzh.sophon.domain.model.TurnSignalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 盲区补盲与开门防碰撞悬浮窗服务 (BlindSpotOverlayService) · 真实视频流驱动版
 *
 * 【全场景智能侧视感知体系】
 * 1. 转向灯盲区补盲：打左/右转向灯时，对应侧滑入 480x320 实时侧视相机画面；
 * 2. 窄道会车模式：低速 (<15km/h) 且挂 D 挡打双闪时，屏幕底部正中弹出 800x320 左右双分屏全景；
 * 3. 开门防碰撞辅助 (DOW / Door Open Warning)：驻车或低速 (<5km/h) 开门瞬间，自动弹出对应侧相机画面，并悬浮黄色高亮警示标语，杜绝“开门杀”事故；
 * 4. 50ms 原厂 AVM 极速避让：原厂倒车或 360 影像激活时，50ms 内无条件隐身并释放 GPU！
 */
class BlindSpotOverlayService : Service() {

    companion object {
        private const val TAG = "Sophon.BlindSpotOverlay"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var overlayContainer: FrameLayout? = null
    private var surfaceHost: FrameLayout? = null
    private var tvWarningBanner: TextView? = null
    private var isOverlayAttached = false
    private var isDoorWarningActive = false

    private var observationJob: Job? = null
    private var doorWarningDismissJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        initWindowLayout()
        startSignalObservation()
        // 用户在设置页关掉转向灯补盲时，可能正打着转向灯悬浮窗仍停留屏幕；
        // 监听偏好变化立即收起，避免开关"看起来没生效"。
        SophonApp.preferences.registerOnPreferenceChange(prefListener)
    }

    private fun isBlindSpotEnabled(): Boolean = SophonApp.preferences.isBlindSpotEnabled

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SophonPreferences.KEY_BLIND_SPOT && !isBlindSpotEnabled()) {
            serviceScope.launch { hideOverlay() }
        }
    }

    private fun initWindowLayout() {
        val density = resources.displayMetrics.density
        overlayContainer = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.sp_bg_quad_frame)
            elevation = 16f * density
            clipToOutline = true
            visibility = View.GONE
        }

        surfaceHost = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        overlayContainer?.addView(surfaceHost)

        tvWarningBanner = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.sp_bg_pill)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xE6D92D20.toInt())
            val ph = (14 * density).toInt()
            val pv = (6 * density).toInt()
            setPadding(ph, pv, ph, pv)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            visibility = View.GONE
            text = "开门防碰撞预警 · 请留意后方非机动车"
        }

        val bannerParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            val m = (10 * density).toInt()
            setMargins(m, m, m, m)
        }
        overlayContainer?.addView(tvWarningBanner, bannerParams)
    }

    private fun startSignalObservation() {
        val bus = SophonApp.vehicleSignalBus

        observationJob = serviceScope.launch {
            // 1. 原厂 AVM 避让信道 (最高优先级 50ms 熔断)
            launch {
                bus.isAvmActive.collectLatest { isAvm ->
                    if (isAvm) {
                        dismissOverlayImmediate("AVM Active Emergency Preemption")
                    }
                }
            }

            // 2. 转向灯盲区补盲
            launch {
                bus.turnSignalState.collectLatest { turnState ->
                    if (bus.isAvmActive.value || isDoorWarningActive) {
                        return@collectLatest
                    }

                    when (turnState) {
                        // 左右转向灯补盲受 isBlindSpotEnabled 控制。此前该开关从未被读取，
                        // 设置页关掉后浮窗照弹（假设置）；此处补上判定。
                        TurnSignalState.LEFT -> if (isBlindSpotEnabled()) {
                            showOverlay(CameraPosition.LEFT, isDoorWarning = false)
                        } else hideOverlay()
                        TurnSignalState.RIGHT -> if (isBlindSpotEnabled()) {
                            showOverlay(CameraPosition.RIGHT, isDoorWarning = false)
                        } else hideOverlay()
                        // 双闪属「窄道会车」而非转向灯补盲，由 hazardSpeedThresholdKmh 独立门控
                        TurnSignalState.HAZARD -> checkHazardPassingAssist()
                        TurnSignalState.OFF -> hideOverlay()
                    }
                }
            }

            // 3. 车门状态开门防碰撞监听 (DOW)
            launch {
                bus.doorState.collectLatest { door ->
                    handleDoorStateChanged(door)
                }
            }
        }
    }

    /**
     * 开门防碰撞辅助事件决策 (DOW)
     */
    private fun handleDoorStateChanged(door: DoorState) {
        if (!SophonApp.preferences.isDoorOpenWarningEnabled || SophonApp.vehicleSignalBus.isAvmActive.value) {
            return
        }

        val speed = SophonApp.vehicleSignalBus.vehicleSpeed.value
        val gear = SophonApp.vehicleSignalBus.gearState.value

        // 仅在停稳或低速怠速时响应开门防碰撞 (< 5 km/h 或 P 挡)
        val isVehicleStopped = speed < 5f || gear == GearState.PARK

        if (isVehicleStopped && door.isAnyAjar) {
            doorWarningDismissJob?.cancel()
            doorWarningDismissJob = null
            isDoorWarningActive = true

            when {
                door.isDriverAjar || door.isRearLeftAjar -> {
                    Log.i(TAG, "Left door open detected while stopped -> Triggering DOW overlay (LEFT)")
                    showOverlay(CameraPosition.LEFT, isDoorWarning = true, warningText = "左侧开门预警 · 请留意后方电瓶车与行人")
                }
                door.isPassengerAjar || door.isRearRightAjar -> {
                    Log.i(TAG, "Right door open detected while stopped -> Triggering DOW overlay (RIGHT)")
                    showOverlay(CameraPosition.RIGHT, isDoorWarning = true, warningText = "右侧开门预警 · 请留意后方车辆与马路边缘")
                }
            }
        } else if (!door.isAnyAjar && isDoorWarningActive) {
            // 车门关上后，延时 2.5 秒平滑淡出，给车内人员确认安全的缓冲期
            doorWarningDismissJob?.cancel()
            doorWarningDismissJob = serviceScope.launch {
                delay(2500L)
                isDoorWarningActive = false
                hideOverlay()
                Log.d(TAG, "DOW overlay closed after door shut.")
            }
        }
    }

    /**
     * 窄道会车双闪模式研判
     */
    private fun checkHazardPassingAssist() {
        val threshold = SophonApp.preferences.hazardSpeedThresholdKmh
        if (threshold <= 0f) {
            hideOverlay()
            return
        }

        val bus = SophonApp.vehicleSignalBus
        val speed = bus.vehicleSpeed.value
        val gear = bus.gearState.value

        if (speed < threshold && gear == GearState.DRIVE) {
            Log.d(TAG, "Hazard passing assist triggered (Speed: $speed km/h, Limit: $threshold, D gear)")
            showDualOverlay()
        } else {
            hideOverlay()
        }
    }

    private fun showOverlay(
        position: CameraPosition,
        isDoorWarning: Boolean = false,
        warningText: String? = null
    ) {
        val wm = windowManager ?: return
        val view = overlayContainer ?: return

        hideOverlay()

        // 真实装载单摄 SurfaceView 直连底层视频流
        surfaceHost?.removeAllViews()
        val sv = SurfaceView(this)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                SophonCoreService.instance?.attachPreviewSurface(position, holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                SophonCoreService.instance?.attachPreviewSurface(position, holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                SophonCoreService.instance?.attachPreviewSurface(position, null)
            }
        })
        surfaceHost?.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (isDoorWarning) {
            tvWarningBanner?.text = warningText ?: "开门防碰撞预警 · 请留意后方盲区"
            tvWarningBanner?.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_action_warning, 0, 0, 0)
            tvWarningBanner?.compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
            tvWarningBanner?.visibility = View.VISIBLE
        } else {
            tvWarningBanner?.visibility = View.GONE
        }

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
            view.alpha = 0f
            view.scaleX = 0.92f
            view.scaleY = 0.92f
            view.visibility = View.VISIBLE
            wm.addView(view, layoutParams)
            isOverlayAttached = true
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .start()
            Log.d(TAG, "Blind spot overlay SHOWN with live camera preview: $position (DOW: $isDoorWarning)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show single overlay", e)
        }
    }

    /**
     * 弹出左右双分屏窄道会车浮窗 (真实双路 Surface 直出)
     */
    private fun showDualOverlay() {
        val wm = windowManager ?: return
        val view = overlayContainer ?: return

        hideOverlay()
        tvWarningBanner?.visibility = View.GONE

        // 真实装载左右并排双路 SurfaceView
        surfaceHost?.removeAllViews()
        val dualLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val svLeft = SurfaceView(this)
        svLeft.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                SophonCoreService.instance?.attachPreviewSurface(CameraPosition.LEFT, holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                SophonCoreService.instance?.attachPreviewSurface(CameraPosition.LEFT, holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                SophonCoreService.instance?.attachPreviewSurface(CameraPosition.LEFT, null)
            }
        })

        val svRight = SurfaceView(this)
        svRight.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                SophonCoreService.instance?.attachPreviewSurface(CameraPosition.RIGHT, holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                SophonCoreService.instance?.attachPreviewSurface(CameraPosition.RIGHT, holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                SophonCoreService.instance?.attachPreviewSurface(CameraPosition.RIGHT, null)
            }
        })

        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f)
        dualLayout.addView(svLeft, lp)
        dualLayout.addView(svRight, lp)
        surfaceHost?.addView(dualLayout)

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
            view.alpha = 0f
            view.scaleX = 0.92f
            view.scaleY = 0.92f
            view.visibility = View.VISIBLE
            wm.addView(view, layoutParams)
            isOverlayAttached = true
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .start()
            Log.i(TAG, "Dual hazard passing overlay SHOWN with dual live camera previews.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show dual hazard overlay", e)
        }
    }

    private fun hideOverlay() {
        if (isOverlayAttached && overlayContainer != null) {
            try {
                overlayContainer?.visibility = View.GONE
                surfaceHost?.removeAllViews()
                SophonCoreService.instance?.attachPreviewSurface(CameraPosition.LEFT, null)
                SophonCoreService.instance?.attachPreviewSurface(CameraPosition.RIGHT, null)
                windowManager?.removeViewImmediate(overlayContainer)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay view", e)
            } finally {
                isOverlayAttached = false
            }
            Log.d(TAG, "Blind spot overlay HIDDEN. Surfaces released.")
        }
    }

    private fun dismissOverlayImmediate(reason: String) {
        if (isOverlayAttached && overlayContainer != null) {
            val startTime = System.currentTimeMillis()
            try {
                overlayContainer?.visibility = View.GONE
                surfaceHost?.removeAllViews()
                SophonCoreService.instance?.attachPreviewSurface(CameraPosition.LEFT, null)
                SophonCoreService.instance?.attachPreviewSurface(CameraPosition.RIGHT, null)
                windowManager?.removeViewImmediate(overlayContainer)
            } catch (e: Exception) {
                Log.w(TAG, "Error emergency dismissing overlay", e)
            } finally {
                isOverlayAttached = false
                isDoorWarningActive = false
            }
            val elapsed = System.currentTimeMillis() - startTime
            Log.w(TAG, "Emergency dismissed overlay ($reason) in ${elapsed}ms (Limit: 50ms)")
        }
    }

    override fun onDestroy() {
        SophonApp.preferences.unregisterOnPreferenceChange(prefListener)
        observationJob?.cancel()
        doorWarningDismissJob?.cancel()
        dismissOverlayImmediate("Service Destroy")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
