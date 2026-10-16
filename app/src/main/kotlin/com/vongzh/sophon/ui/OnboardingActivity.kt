package com.vongzh.sophon.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.vongzh.sophon.R
import com.vongzh.sophon.SophonApp

/**
 * 首启合规门 (OnboardingActivity) · 隐私协议 + 分层权限授权
 *
 * 【合规红线】
 * 极视会采集四路车外影像、车内录音与车身信号，属于敏感个人信息。
 * 依据《个人信息保护法》与车厂应用商店审核要求，必须在采集前展示隐私协议并取得明示同意，
 * 因此本页在 MainActivity 任何摄像头 / 录音 / 车辆信号订阅之前启动。
 *
 * 【为什么权限页要逐项展示真实状态】
 * 权限展示拆分为基础核心 / 可选扩展 / 车载系统三类，
 * 每项状态均来自真实系统查询（checkSelfPermission / canDrawOverlays），
 * 并且只提供真实可达的授权入口——系统签名级的车身权限无法运行时申请，
 * 就如实标注「需系统授予」而不放不可操作的按钮。
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        /** 当前隐私协议版本号：协议条款重大变更时递增，可强制老用户重新确认 */
        const val CURRENT_PRIVACY_VERSION = 1

        /** 供外部判断是否已通过合规门 */
        fun isAgreed(): Boolean = SophonApp.preferences.privacyAgreedVersion >= CURRENT_PRIVACY_VERSION

        private const val REQ_PERMISSIONS = 1001
        private const val REQ_OVERLAY = 1002

        /** 车身信号取其中一项作为代表即可判定系统是否已预置授权 */
        private val CAR_SIGNAL_PERMISSIONS = arrayOf(
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_POWERTRAIN",
            "android.car.permission.CAR_EXTERIOR_LIGHTS"
        )
    }

    private lateinit var cbAgree: CheckBox
    private lateinit var btnContinue: TextView
    private lateinit var btnGrantAll: TextView
    private lateinit var tvSummary: TextView

    /** 一行权限的控件句柄 */
    private class Row(val root: View) {
        val icon: ImageView = root.findViewById(R.id.iv_perm_icon)
        val name: TextView = root.findViewById(R.id.tv_perm_name)
        val desc: TextView = root.findViewById(R.id.tv_perm_desc)
        val status: TextView = root.findViewById(R.id.tv_perm_status)
        val action: TextView = root.findViewById(R.id.btn_perm_action)
    }

    private lateinit var rowCamera: Row
    private lateinit var rowAudio: Row
    private lateinit var rowNotify: Row
    private lateinit var rowOverlay: Row
    private lateinit var rowCar: Row

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        cbAgree = findViewById(R.id.cb_privacy_agree)
        btnContinue = findViewById(R.id.btn_agree_continue)
        btnGrantAll = findViewById(R.id.btn_permission_grant_all)
        tvSummary = findViewById(R.id.tv_permission_summary)

        rowCamera = Row(findViewById(R.id.row_perm_camera))
        rowAudio = Row(findViewById(R.id.row_perm_audio))
        rowNotify = Row(findViewById(R.id.row_perm_notify))
        rowOverlay = Row(findViewById(R.id.row_perm_overlay))
        rowCar = Row(findViewById(R.id.row_perm_car))

        // 未勾选前禁用「同意并继续」，避免误触跳过协议
        applyContinueState(false)
        cbAgree.setOnCheckedChangeListener { _, isChecked -> applyContinueState(isChecked) }

        btnContinue.setOnClickListener {
            if (!cbAgree.isChecked) return@setOnClickListener
            SophonApp.preferences.privacyAgreedVersion = CURRENT_PRIVACY_VERSION
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        btnGrantAll.setOnClickListener { requestAllGrantablePermissions() }

        bindStaticRowContent()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 合规门不可返回绕过，仅允许通过系统任务键切换离开
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回后状态会变，必须重新查询而不是沿用上次结果
        refreshAllPermissionStates()
    }

    private fun bindStaticRowContent() {
        rowCamera.icon.setImageResource(R.drawable.ic_action_camera)
        rowCamera.name.text = getString(R.string.perm_camera_name)
        rowCamera.desc.text = getString(R.string.perm_camera_desc)
        rowCamera.action.setOnClickListener {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA))
        }

        rowAudio.icon.setImageResource(R.drawable.ic_perm_mic)
        rowAudio.name.text = getString(R.string.perm_audio_name)
        rowAudio.desc.text = getString(R.string.perm_audio_desc)
        rowAudio.action.setOnClickListener {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO))
        }

        rowNotify.icon.setImageResource(R.drawable.ic_settings_notify)
        rowNotify.name.text = getString(R.string.perm_notify_name)
        rowNotify.desc.text = getString(R.string.perm_notify_desc)
        rowNotify.action.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
            } else {
                openNotificationSettings()
            }
        }

        rowOverlay.icon.setImageResource(R.drawable.ic_pip_window)
        rowOverlay.name.text = getString(R.string.perm_overlay_name)
        rowOverlay.desc.text = getString(R.string.perm_overlay_desc)
        rowOverlay.action.setOnClickListener { openOverlaySettings() }

        rowCar.icon.setImageResource(R.drawable.ic_settings_vehicle)
        rowCar.name.text = getString(R.string.perm_car_name)
        rowCar.desc.text = getString(R.string.perm_car_desc)
        // 系统签名级权限无法运行时申请，不提供点了也没用的按钮
        rowCar.action.visibility = View.GONE
    }

    private fun refreshAllPermissionStates() {
        refreshRuntimeRow(rowCamera, android.Manifest.permission.CAMERA)
        refreshRuntimeRow(rowAudio, android.Manifest.permission.RECORD_AUDIO)

        val notifyPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.POST_NOTIFICATIONS
        } else null
        if (notifyPermission == null) {
            // Android 13 以下通知权限默认授予，无运行时开关
            applyGranted(rowNotify)
            rowNotify.action.visibility = View.GONE
        } else {
            refreshRuntimeRow(rowNotify, notifyPermission)
        }

        val overlayGranted = Settings.canDrawOverlays(this)
        applyState(rowOverlay, overlayGranted, systemLevel = false)
        rowOverlay.action.visibility = if (overlayGranted) View.GONE else View.VISIBLE
        rowOverlay.action.text = getString(R.string.perm_action_open)

        val carGranted = CAR_SIGNAL_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (carGranted) {
            applyGranted(rowCar)
        } else {
            rowCar.status.text = getString(R.string.perm_status_system)
            rowCar.status.setTextColor(getColor(R.color.sp_tx3))
            rowCar.icon.setColorFilter(getColor(R.color.sp_tx3))
        }

        // 汇总只看真正的必需项：只有相机。麦克风只影响视频音轨，缺了照样能录制，
        // 把它算进「必需」会让用户在拒绝麦克风后被永久挡在门外。
        val missingBasic = listOf(
            android.Manifest.permission.CAMERA
        ).count { !isGranted(it) }

        tvSummary.text = if (missingBasic == 0) {
            getString(R.string.perm_summary_all_granted)
        } else {
            getString(R.string.perm_summary_pending, missingBasic)
        }
        tvSummary.setTextColor(getColor(if (missingBasic == 0) R.color.sp_ok else R.color.sp_wn))

        btnGrantAll.isEnabled = missingBasic > 0 || !Settings.canDrawOverlays(this)
        btnGrantAll.alpha = if (btnGrantAll.isEnabled) 1.0f else 0.4f
    }

    private fun refreshRuntimeRow(row: Row, permission: String) {
        val granted = isGranted(permission)
        applyState(row, granted, systemLevel = false)
        row.action.visibility = if (granted) View.GONE else View.VISIBLE
        row.action.text = getString(R.string.perm_action_grant)
    }

    private fun applyState(row: Row, granted: Boolean, systemLevel: Boolean) {
        if (granted) {
            applyGranted(row)
            return
        }
        row.status.text = if (systemLevel) getString(R.string.perm_status_system) else getString(R.string.perm_status_denied)
        val color = getColor(if (systemLevel) R.color.sp_tx3 else R.color.sp_wn)
        row.status.setTextColor(color)
        row.icon.setColorFilter(color)
    }

    private fun applyGranted(row: Row) {
        row.status.text = getString(R.string.perm_status_granted)
        row.status.setTextColor(getColor(R.color.sp_ok))
        row.icon.setColorFilter(getColor(R.color.sp_ac))
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 一键授权：一次性申请全部可运行时授予的危险权限。
     * 悬浮窗不属于运行时权限，申请完危险权限后再引导至系统设置页。
     */
    private fun requestAllGrantablePermissions() {
        val pending = mutableListOf<String>()
        if (!isGranted(android.Manifest.permission.CAMERA)) pending.add(android.Manifest.permission.CAMERA)
        if (!isGranted(android.Manifest.permission.RECORD_AUDIO)) pending.add(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !isGranted(android.Manifest.permission.POST_NOTIFICATIONS)
        ) {
            pending.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        if (pending.isEmpty()) {
            if (!Settings.canDrawOverlays(this)) openOverlaySettings()
            return
        }
        ActivityCompat.requestPermissions(this, pending.toTypedArray(), REQ_PERMISSIONS)
    }

    private fun requestPermissions(permissions: Array<String>) {
        ActivityCompat.requestPermissions(this, permissions, REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        refreshAllPermissionStates()
        // 危险权限全部到位后，再补悬浮窗这一项需要跳系统设置的权限
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }
    }

    private fun applyContinueState(agreed: Boolean) {
        btnContinue.isEnabled = agreed
        btnContinue.alpha = if (agreed) 1.0f else 0.4f
    }
}
