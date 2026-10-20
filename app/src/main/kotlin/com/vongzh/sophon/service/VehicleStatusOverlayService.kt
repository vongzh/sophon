package com.vongzh.sophon.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.vongzh.sophon.R
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.domain.model.GearState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 悬浮窗矩阵 · 车辆状态小窗 (VehicleStatusOverlayService)
 *
 * 常驻左上角，200dp 宽纵向卡片，实时显示：
 * 车速 / 挡位 / 12V 蓄电池电压 / 录制状态 / 哨兵状态 / 外置 U 盘。
 *
 * 全部取自 VehicleSignalBus 与 PowerStateMachine 真实信号，无占位数据。
 * 由 SophonPreferences.isStatusOverlayEnabled 控制总开关（默认关闭）。
 */
class VehicleStatusOverlayService : Service() {

    companion object {
        private const val TAG = "Sophon.StatusOverlay"
        private const val REFRESH_MS = 500L
        private const val WIDTH_DP = 200
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var panel: LinearLayout? = null
    private var isAttached = false
    private var refreshJob: Job? = null

    private lateinit var tvSpeed: TextView
    private lateinit var tvGear: TextView
    private lateinit var tvVoltage: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvRecord: TextView
    private lateinit var tvSentry: TextView
    private lateinit var tvStorage: TextView

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        buildPanel()
        startRefreshLoop()
    }

    private fun buildPanel() {
        val density = resources.displayMetrics.density
        val padding = (14 * density).toInt()
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.sp_bg_card)
            elevation = 16f * density
            setPadding(padding, padding, padding, padding)
            visibility = View.GONE
        }

        tvSpeed = addRow("车速", "-- km/h", 20f, true)
        tvGear = addRow("挡位", "--")
        tvVoltage = addRow("12V 电压", "-- V")
        tvTemp = addRow("车外温度", "-- ℃")
        tvRecord = addRow("录制", "--")
        tvSentry = addRow("哨兵", "--")
        tvStorage = addRow("外置存储", "--")
    }

    private fun addRow(label: String, initial: String, valueSp: Float = 13f, isAccent: Boolean = false): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6 }
        }

        val tvLabel = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(getColor(R.color.sp_tx3))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
            )
        }

        val tvValue = TextView(this).apply {
            text = initial
            textSize = valueSp
            setTextColor(getColor(if (isAccent) R.color.sp_ac else R.color.sp_tx))
            typeface = android.graphics.Typeface.MONOSPACE
            includeFontPadding = false
            if (isAccent) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        row.addView(tvLabel)
        row.addView(tvValue)
        panel?.addView(row)
        return tvValue
    }

    private fun startRefreshLoop() {
        refreshJob = serviceScope.launch {
            while (isActive) {
                val enabled = SophonApp.preferences.isStatusOverlayEnabled
                if (!enabled) {
                    detach("Disabled by preference")
                } else {
                    attach()
                    updateValues()
                }
                delay(REFRESH_MS)
            }
        }
    }

    private fun updateValues() {
        val bus = SophonApp.vehicleSignalBus
        val power = SophonApp.powerStateMachine.currentState.value
        val volts = bus.batteryVoltage.value

        tvSpeed.text = "%.0f km/h".format(bus.vehicleSpeed.value)
        tvGear.text = when (bus.gearState.value) {
            GearState.PARK -> "P 驻车"
            GearState.REVERSE -> "R 倒车"
            GearState.NEUTRAL -> "N 空挡"
            GearState.DRIVE -> "D 前进"
            GearState.UNKNOWN -> "未知"
        }
        tvVoltage.text = if (volts > 0f) "%.2f V".format(volts) else "未接入"

        val temp = bus.outsideTemperature.value
        tvTemp.text = if (temp != null) "%.1f ℃".format(temp) else "-- ℃"

        val isRecording = SophonCoreService.instance?.isRecordingFlow?.value ?: false
        tvRecord.text = if (isRecording) "● 录制中" else "○ 已停止"
        tvRecord.setTextColor(getColor(if (isRecording) R.color.sp_dg else R.color.sp_tx))

        val sentryOn = power.name.startsWith("SENTRY")
        tvSentry.text = when {
            sentryOn && power.name == "SENTRY_ALERT" -> "告警中"
            sentryOn -> "● 布防中"
            SophonApp.sentryHomeGeofence.isAtHome.value -> "○ 围栏撤防"
            else -> "○ 未布防"
        }
        tvSentry.setTextColor(
            getColor(
                when {
                    power.name == "SENTRY_ALERT" -> R.color.sp_dg
                    sentryOn -> R.color.sp_ok
                    else -> R.color.sp_tx
                }
            )
        )

        val mounted = SophonApp.storageVault.isUsbMounted.value
        tvStorage.text = if (mounted) "已挂载" else "未挂载"
        tvStorage.setTextColor(getColor(if (mounted) R.color.sp_ok else R.color.sp_wn))
    }

    private fun attach() {
        if (isAttached) return
        val wm = windowManager ?: return
        val view = panel ?: return

        val widthPx = (WIDTH_DP * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            widthPx,
            ViewGroup.LayoutParams.WRAP_CONTENT,
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
            gravity = Gravity.TOP or Gravity.START
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
            Log.d(TAG, "Vehicle status overlay attached.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach status overlay", e)
        }
    }

    private fun detach(reason: String) {
        if (!isAttached) return
        try {
            panel?.visibility = View.GONE
            windowManager?.removeViewImmediate(panel)
            Log.d(TAG, "Vehicle status overlay detached ($reason).")
        } catch (e: Exception) {
            Log.w(TAG, "Error detaching status overlay", e)
        } finally {
            isAttached = false
        }
    }

    override fun onDestroy() {
        detach("Service destroy")
        refreshJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
