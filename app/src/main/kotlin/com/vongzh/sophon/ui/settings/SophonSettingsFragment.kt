package com.vongzh.sophon.ui.settings

import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.vongzh.sophon.R
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.core.remote.bark.BarkNotifier
import com.vongzh.sophon.core.remote.feishu.FeishuBotClient
import com.vongzh.sophon.core.remote.telegram.TelegramBotClient
import com.vongzh.sophon.core.storage.UsbBenchmark
import com.vongzh.sophon.domain.model.StorageUsageBreakdown
import com.vongzh.sophon.ui.widget.MultiSegmentStorageBarView
import com.vongzh.sophon.ui.widget.StorageCapacityRingView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vongzh.sophon.core.config.ImageTuning
import com.vongzh.sophon.core.geofence.SentryHomeGeofence
import com.vongzh.sophon.service.OverlayMatrixController
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface
import java.util.Locale

/**
 * 极视座舱系统偏好设置组件 (SophonSettingsFragment)
 *
 * 【左侧 10 大分类导航 + 右侧卡片式车机网格，涵盖全部座舱配置与扩展功能】
 * 【安全红线 - 底线二 (Zero Write to Internal Flash)】
 * 坚决不支持车身内部闪存录像，外置 USB 存储为唯一合法落盘介质。
 */
class SophonSettingsFragment : Fragment() {

    private lateinit var scrollSettingsContent: ScrollView

    // 左侧导航按钮
    private lateinit var btnTabCommon: TextView
    private lateinit var btnTabBasic: TextView
    private lateinit var btnTabStorage: TextView
    private lateinit var btnTabBlindspot: TextView
    private lateinit var btnTabHud: TextView
    private lateinit var btnTabRemote: TextView
    private lateinit var btnTabStartup: TextView
    private lateinit var btnTabPower: TextView
    private lateinit var btnTabLab: TextView
    private lateinit var btnTabAbout: TextView

    // 右侧卡片组
    private lateinit var groupCommon: View
    private lateinit var groupBlindspot: View
    private lateinit var groupStorage: View
    private lateinit var groupRemote: View
    private lateinit var groupAbout: View
    private lateinit var groupRecord: View
    private lateinit var groupOverlay: View
    private lateinit var groupSentry: View
    private lateinit var groupPower: View
    private lateinit var groupNetwork: View

    // 控件：切片时长
    private lateinit var rgChunkDuration: RadioGroup
    private lateinit var rbChunk1: RadioButton
    private lateinit var rbChunk3: RadioButton
    private lateinit var rbChunk5: RadioButton

    // 控件：碰撞灵敏度
    private lateinit var rgCrashSensitivity: RadioGroup
    private lateinit var rbCrashLow: RadioButton
    private lateinit var rbCrashMid: RadioButton
    private lateinit var rbCrashHigh: RadioButton

    // 控件：常用开关
    private lateinit var swAudioRecord: Switch
    private lateinit var swAutoCrashLock: Switch
    private lateinit var swShowFabLabels: Switch
    private lateinit var swTurnSignalBlindSpot: Switch
    private lateinit var swDoorOpenWarning: Switch

    // 控件：局域网快传（状态与二维码均为真实服务）
    private lateinit var tvLanShareStatusPill: TextView
    private lateinit var tvLanShareAddr: TextView
    private lateinit var btnLanShareQr: TextView

    // 控件：配置备份
    private lateinit var btnBackupConfig: TextView
    private lateinit var btnRestoreConfig: TextView

    // 控件：主题模式选择 (深色/浅色/跟随系统)
    private lateinit var tvThemeModeCurrentPill: TextView
    private lateinit var rgThemeMode: RadioGroup
    private lateinit var rbThemeSystem: RadioButton
    private lateinit var rbThemeLight: RadioButton
    private lateinit var rbThemeDark: RadioButton

    // 控件：Bark
    private lateinit var swBarkEnabled: Switch
    private lateinit var etBarkServer: EditText
    private lateinit var etBarkKey: EditText
    private lateinit var btnTestBark: TextView

    // 控件：Telegram
    private lateinit var swTelegramEnabled: Switch
    private lateinit var etTelegramBotToken: EditText
    private lateinit var etTelegramChatId: EditText
    private lateinit var btnTestTelegram: TextView

    // 控件：Feishu（Webhook 与自建应用两套凭据，运行时按填充情况择一）
    private lateinit var swFeishuEnabled: Switch
    private lateinit var etFeishuWebhook: EditText
    private lateinit var etFeishuAppId: EditText
    private lateinit var etFeishuAppSecret: EditText
    private lateinit var etFeishuReceiveId: EditText
    private lateinit var btnTestFeishu: TextView
    // 自建应用凭据折叠区
    private lateinit var tvFeishuAppSummary: TextView
    private lateinit var btnFeishuAppToggle: TextView
    private lateinit var groupFeishuAppFields: View

    // 控件：存储配置相关 (严守不支持车身存储规范，外置 USB 专属)
    private lateinit var tvStorageCardTitle: TextView
    private lateinit var ringStorageCapacity: StorageCapacityRingView
    private lateinit var tvStorageRingPercent: TextView
    private lateinit var tvStorageRingSummary: TextView
    private lateinit var tvStorageAvailTitle: TextView
    private lateinit var tvStorageTotalTitle: TextView
    private lateinit var tvStorageStatusTip: TextView
    private lateinit var barStorageSegments: MultiSegmentStorageBarView
    private lateinit var tvLegendOther: TextView
    private lateinit var tvLegendDriving: TextView
    private lateinit var tvLegendPhotos: TextView
    private lateinit var tvLegendSentry: TextView
    private lateinit var tvLegendFree: TextView

    private lateinit var btnStorageHelp: TextView
    private lateinit var tvStorageUsbStatusPill: TextView

    // 步进器 Stepper 与触发时机
    private lateinit var btnVideoQuotaMinus: TextView
    private lateinit var tvVideoQuotaVal: TextView
    private lateinit var btnVideoQuotaPlus: TextView

    private lateinit var btnCleanRatioMinus: TextView
    private lateinit var tvCleanRatioVal: TextView
    private lateinit var btnCleanRatioPlus: TextView

    private lateinit var btnPhotoQuotaMinus: TextView
    private lateinit var tvPhotoQuotaVal: TextView
    private lateinit var btnPhotoQuotaPlus: TextView

    private lateinit var btnTriggerRecordEnd: TextView
    private lateinit var btnTriggerHighUsage: TextView
    private lateinit var btnTriggerStartup: TextView

    // 控件：录制策略 (车身信号联动)
    private lateinit var swStopRecordOnPark: Switch
    private lateinit var swStopRecordOnDriverDoor: Switch

    // 控件：影像参数
    private lateinit var rgExposure: RadioGroup
    private lateinit var rgNoise: RadioGroup
    private lateinit var rbEvM2: RadioButton
    private lateinit var rbEvM1: RadioButton
    private lateinit var rbEv0: RadioButton
    private lateinit var rbEvP1: RadioButton
    private lateinit var rbEvP2: RadioButton
    private lateinit var rbNrOff: RadioButton
    private lateinit var rbNrFast: RadioButton
    private lateinit var rbNrHq: RadioButton
    private lateinit var btnApplyNightPreset: TextView

    // 控件：画面与播放
    private lateinit var swOsdWatermark: Switch
    private lateinit var swRainyNight: Switch
    private lateinit var swPipAuto: Switch

    // 控件：悬浮窗矩阵
    private lateinit var swRearViewOverlay: Switch
    private lateinit var swStatusOverlay: Switch
    private lateinit var swAssistBall: Switch

    // 控件：哨兵与围栏
    private lateinit var swSentryMode: Switch
    private lateinit var swSentryDeterrent: Switch
    private lateinit var etSentryHomeSsids: EditText
    private lateinit var tvSentryGeofenceStatus: TextView

    // 控件：电源与低压保护
    private lateinit var swBatteryProtection: Switch
    private lateinit var btnBattVoltsMinus: TextView
    private lateinit var tvBattVoltsVal: TextView
    private lateinit var btnBattVoltsPlus: TextView

    // 控件：窄道会车阈值
    private lateinit var btnHazardSpeedMinus: TextView
    private lateinit var tvHazardSpeedVal: TextView
    private lateinit var btnHazardSpeedPlus: TextView

    // 控件：网络与固件
    private lateinit var etLanPort: EditText
    private lateinit var tvFirmwareInfo: TextView

    // 控件：关于页
    private lateinit var tvAboutVersion: TextView
    private lateinit var tvAboutPlatform: TextView
    private lateinit var tvAboutStorage: TextView
    private lateinit var btnAboutPrivacy: View
    private lateinit var btnAboutDisclaimer: View
    private lateinit var btnAboutDiag: View

    // 维护与测速
    private lateinit var tvBenchmarkResult: TextView
    private lateinit var btnRunUsbBenchmark: TextView
    private lateinit var btnFormatStorage: TextView

    /** 围栏状态订阅任务：配置恢复后会重新调用 refreshGeofenceStatus，须先取消旧订阅避免重复刷新 */
    private var geofenceJob: kotlinx.coroutines.Job? = null

    private val barkNotifier = BarkNotifier()
    private val telegramClient = TelegramBotClient()
    private val feishuClient = FeishuBotClient()
    private val usbBenchmark = UsbBenchmark()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        loadCurrentPreferences()
        setupListeners()
        loadNetworkAndFirmwareInfo()
        refreshGeofenceStatus()
        observeStorageState()
    }

    private fun initViews(root: View) {
        scrollSettingsContent = root.findViewById(R.id.scroll_settings_content)

        btnTabCommon = root.findViewById(R.id.btn_tab_common)
        btnTabBasic = root.findViewById(R.id.btn_tab_basic)
        btnTabStorage = root.findViewById(R.id.btn_tab_storage)
        btnTabBlindspot = root.findViewById(R.id.btn_tab_blindspot)
        btnTabHud = root.findViewById(R.id.btn_tab_hud)
        btnTabRemote = root.findViewById(R.id.btn_tab_remote)
        btnTabStartup = root.findViewById(R.id.btn_tab_startup)
        btnTabPower = root.findViewById(R.id.btn_tab_power)
        btnTabLab = root.findViewById(R.id.btn_tab_lab)
        btnTabAbout = root.findViewById(R.id.btn_tab_about)

        groupCommon = root.findViewById(R.id.group_settings_common)
        groupBlindspot = root.findViewById(R.id.group_settings_blindspot)
        groupStorage = root.findViewById(R.id.group_settings_storage)
        groupRemote = root.findViewById(R.id.group_settings_remote)
        groupAbout = root.findViewById(R.id.group_settings_about)
        groupRecord = root.findViewById(R.id.group_settings_record)
        groupOverlay = root.findViewById(R.id.group_settings_overlay)
        groupSentry = root.findViewById(R.id.group_settings_sentry)
        groupPower = root.findViewById(R.id.group_settings_power)
        groupNetwork = root.findViewById(R.id.group_settings_network)

        rgChunkDuration = root.findViewById(R.id.rg_chunk_duration)
        rbChunk1 = root.findViewById(R.id.rb_chunk_1)
        rbChunk3 = root.findViewById(R.id.rb_chunk_3)
        rbChunk5 = root.findViewById(R.id.rb_chunk_5)

        rgCrashSensitivity = root.findViewById(R.id.rg_crash_sensitivity)
        rbCrashLow = root.findViewById(R.id.rb_crash_low)
        rbCrashMid = root.findViewById(R.id.rb_crash_mid)
        rbCrashHigh = root.findViewById(R.id.rb_crash_high)

        swAudioRecord = root.findViewById(R.id.sw_audio_record)
        swAutoCrashLock = root.findViewById(R.id.sw_auto_crash_lock)
        swShowFabLabels = root.findViewById(R.id.sw_show_fab_labels)
        swTurnSignalBlindSpot = root.findViewById(R.id.sw_turn_signal_blind_spot)
        swDoorOpenWarning = root.findViewById(R.id.sw_door_open_warning)

        tvLanShareStatusPill = root.findViewById(R.id.tv_lan_share_status_pill)
        tvLanShareAddr = root.findViewById(R.id.tv_lan_share_addr)
        btnLanShareQr = root.findViewById(R.id.btn_lan_share_qr)

        btnBackupConfig = root.findViewById(R.id.btn_backup_config)
        btnRestoreConfig = root.findViewById(R.id.btn_restore_config)

        tvThemeModeCurrentPill = root.findViewById(R.id.tv_theme_mode_current_pill)
        rgThemeMode = root.findViewById(R.id.rg_theme_mode)
        rbThemeSystem = root.findViewById(R.id.rb_theme_system)
        rbThemeLight = root.findViewById(R.id.rb_theme_light)
        rbThemeDark = root.findViewById(R.id.rb_theme_dark)

        swBarkEnabled = root.findViewById(R.id.sw_bark_enabled)
        etBarkServer = root.findViewById(R.id.et_bark_server)
        etBarkKey = root.findViewById(R.id.et_bark_key)
        btnTestBark = root.findViewById(R.id.btn_test_bark)

        swTelegramEnabled = root.findViewById(R.id.sw_telegram_enabled)
        etTelegramBotToken = root.findViewById(R.id.et_telegram_bot_token)
        etTelegramChatId = root.findViewById(R.id.et_telegram_chat_id)
        btnTestTelegram = root.findViewById(R.id.btn_test_telegram)

        swFeishuEnabled = root.findViewById(R.id.sw_feishu_enabled)
        etFeishuWebhook = root.findViewById(R.id.et_feishu_webhook)
        etFeishuAppId = root.findViewById(R.id.et_feishu_app_id)
        etFeishuAppSecret = root.findViewById(R.id.et_feishu_app_secret)
        etFeishuReceiveId = root.findViewById(R.id.et_feishu_receive_id)
        btnTestFeishu = root.findViewById(R.id.btn_test_feishu)
        tvFeishuAppSummary = root.findViewById(R.id.tv_feishu_app_summary)
        btnFeishuAppToggle = root.findViewById(R.id.btn_feishu_app_toggle)
        groupFeishuAppFields = root.findViewById(R.id.group_feishu_app_fields)

        // 存储概览控件
        tvStorageCardTitle = root.findViewById(R.id.tv_storage_card_title)
        ringStorageCapacity = root.findViewById(R.id.ring_storage_capacity)
        tvStorageRingPercent = root.findViewById(R.id.tv_storage_ring_percent)
        tvStorageRingSummary = root.findViewById(R.id.tv_storage_ring_summary)
        tvStorageAvailTitle = root.findViewById(R.id.tv_storage_avail_title)
        tvStorageTotalTitle = root.findViewById(R.id.tv_storage_total_title)
        tvStorageStatusTip = root.findViewById(R.id.tv_storage_status_tip)
        barStorageSegments = root.findViewById(R.id.bar_storage_segments)
        tvLegendOther = root.findViewById(R.id.tv_legend_other)
        tvLegendDriving = root.findViewById(R.id.tv_legend_driving)
        tvLegendPhotos = root.findViewById(R.id.tv_legend_photos)
        tvLegendSentry = root.findViewById(R.id.tv_legend_sentry)
        tvLegendFree = root.findViewById(R.id.tv_legend_free)

        btnStorageHelp = root.findViewById(R.id.btn_storage_help)
        tvStorageUsbStatusPill = root.findViewById(R.id.tv_storage_usb_status_pill)

        // 步进器
        btnVideoQuotaMinus = root.findViewById(R.id.btn_video_quota_minus)
        tvVideoQuotaVal = root.findViewById(R.id.tv_video_quota_val)
        btnVideoQuotaPlus = root.findViewById(R.id.btn_video_quota_plus)

        btnCleanRatioMinus = root.findViewById(R.id.btn_clean_ratio_minus)
        tvCleanRatioVal = root.findViewById(R.id.tv_clean_ratio_val)
        btnCleanRatioPlus = root.findViewById(R.id.btn_clean_ratio_plus)

        btnPhotoQuotaMinus = root.findViewById(R.id.btn_photo_quota_minus)
        tvPhotoQuotaVal = root.findViewById(R.id.tv_photo_quota_val)
        btnPhotoQuotaPlus = root.findViewById(R.id.btn_photo_quota_plus)

        btnTriggerRecordEnd = root.findViewById(R.id.btn_trigger_record_end)
        btnTriggerHighUsage = root.findViewById(R.id.btn_trigger_high_usage)
        btnTriggerStartup = root.findViewById(R.id.btn_trigger_startup)

        tvBenchmarkResult = root.findViewById(R.id.tv_benchmark_result)
        btnRunUsbBenchmark = root.findViewById(R.id.btn_run_usb_benchmark)

        swStopRecordOnPark = root.findViewById(R.id.sw_stop_record_on_park)
        swStopRecordOnDriverDoor = root.findViewById(R.id.sw_stop_record_on_driver_door)

        rgExposure = root.findViewById(R.id.rg_exposure)
        rgNoise = root.findViewById(R.id.rg_noise)
        rbEvM2 = root.findViewById(R.id.rb_ev_m2)
        rbEvM1 = root.findViewById(R.id.rb_ev_m1)
        rbEv0 = root.findViewById(R.id.rb_ev_0)
        rbEvP1 = root.findViewById(R.id.rb_ev_p1)
        rbEvP2 = root.findViewById(R.id.rb_ev_p2)
        rbNrOff = root.findViewById(R.id.rb_nr_off)
        rbNrFast = root.findViewById(R.id.rb_nr_fast)
        rbNrHq = root.findViewById(R.id.rb_nr_hq)
        btnApplyNightPreset = root.findViewById(R.id.btn_apply_night_preset)

        swOsdWatermark = root.findViewById(R.id.sw_osd_watermark)
        swRainyNight = root.findViewById(R.id.sw_rainy_night)
        swPipAuto = root.findViewById(R.id.sw_pip_auto)

        swRearViewOverlay = root.findViewById(R.id.sw_rear_view_overlay)
        swStatusOverlay = root.findViewById(R.id.sw_status_overlay)
        swAssistBall = root.findViewById(R.id.sw_assist_ball)

        swSentryMode = root.findViewById(R.id.sw_sentry_mode)
        swSentryDeterrent = root.findViewById(R.id.sw_sentry_deterrent)
        etSentryHomeSsids = root.findViewById(R.id.et_sentry_home_ssids)
        tvSentryGeofenceStatus = root.findViewById(R.id.tv_sentry_geofence_status)

        swBatteryProtection = root.findViewById(R.id.sw_battery_protection)
        btnBattVoltsMinus = root.findViewById(R.id.btn_batt_volts_minus)
        tvBattVoltsVal = root.findViewById(R.id.tv_batt_volts_val)
        btnBattVoltsPlus = root.findViewById(R.id.btn_batt_volts_plus)

        btnHazardSpeedMinus = root.findViewById(R.id.btn_hazard_speed_minus)
        tvHazardSpeedVal = root.findViewById(R.id.tv_hazard_speed_val)
        btnHazardSpeedPlus = root.findViewById(R.id.btn_hazard_speed_plus)

        etLanPort = root.findViewById(R.id.et_lan_port)
        tvFirmwareInfo = root.findViewById(R.id.tv_firmware_info)
        btnFormatStorage = root.findViewById(R.id.btn_format_storage)

        tvAboutVersion = root.findViewById(R.id.tv_about_version)
        tvAboutPlatform = root.findViewById(R.id.tv_about_platform)
        tvAboutStorage = root.findViewById(R.id.tv_about_storage)
        btnAboutPrivacy = root.findViewById(R.id.btn_about_privacy)
        btnAboutDisclaimer = root.findViewById(R.id.btn_about_disclaimer)
        btnAboutDiag = root.findViewById(R.id.btn_about_diag)
    }

    private fun loadCurrentPreferences() {
        val prefs = SophonApp.preferences

        when (prefs.chunkDurationSec) {
            60 -> rbChunk1.isChecked = true
            300 -> rbChunk5.isChecked = true
            else -> rbChunk3.isChecked = true
        }

        when (prefs.themeMode) {
            com.vongzh.sophon.core.config.SophonPreferences.THEME_MODE_LIGHT -> {
                rbThemeLight.isChecked = true
                updateThemeUiState(com.vongzh.sophon.core.config.SophonPreferences.THEME_MODE_LIGHT)
            }
            com.vongzh.sophon.core.config.SophonPreferences.THEME_MODE_DARK -> {
                rbThemeDark.isChecked = true
                updateThemeUiState(com.vongzh.sophon.core.config.SophonPreferences.THEME_MODE_DARK)
            }
            else -> {
                rbThemeSystem.isChecked = true
                updateThemeUiState(com.vongzh.sophon.core.config.SophonPreferences.THEME_MODE_SYSTEM)
            }
        }

        when (prefs.crashSensitivity) {
            0 -> rbCrashLow.isChecked = true
            2 -> rbCrashHigh.isChecked = true
            else -> rbCrashMid.isChecked = true
        }

        swAudioRecord.isChecked = prefs.isAudioRecordEnabled
        swAutoCrashLock.isChecked = prefs.isAutoCrashLockEnabled
        swShowFabLabels.isChecked = prefs.isShowFabLabels
        swTurnSignalBlindSpot.isChecked = prefs.isBlindSpotEnabled
        swDoorOpenWarning.isChecked = prefs.isDowEnabled

        swOsdWatermark.isChecked = prefs.isOsdWatermarkEnabled
        swRainyNight.isChecked = prefs.isRainyNightModeEnabled
        swPipAuto.isChecked = prefs.isPipAutoEnabled

        swSentryMode.isChecked = prefs.isSentryModeEnabled
        swSentryDeterrent.isChecked = prefs.isSentryDeterrentEnabled

        swBatteryProtection.isChecked = prefs.isBatteryProtectionEnabled
        tvBattVoltsVal.text = String.format(Locale.US, "%.1f V", prefs.batteryProtectionThresholdVolts)
        tvHazardSpeedVal.text = String.format(Locale.US, "%.0f km/h", prefs.hazardSpeedThresholdKmh)

        swBarkEnabled.isChecked = prefs.isBarkEnabled
        etBarkServer.setText(prefs.barkServerUrl)
        etBarkKey.setText(prefs.barkDeviceKey)

        swTelegramEnabled.isChecked = prefs.isTelegramEnabled
        etTelegramBotToken.setText(prefs.telegramBotToken)
        etTelegramChatId.setText(prefs.telegramChatId)

        swFeishuEnabled.isChecked = prefs.isFeishuEnabled
        etFeishuWebhook.setText(prefs.feishuWebhookUrl)
        etFeishuAppId.setText(prefs.feishuAppId)
        etFeishuAppSecret.setText(prefs.feishuAppSecret)
        etFeishuReceiveId.setText(prefs.feishuReceiveId)
        // 已配置过自建应用的用户直接展开，否则凭据会变成看不见的隐藏状态
        setFeishuAppExpanded(isFeishuAppConfigured())

        swStopRecordOnPark.isChecked = prefs.isStopRecordOnParkEnabled
        swStopRecordOnDriverDoor.isChecked = prefs.isStopRecordOnDriverDoorEnabled

        when (prefs.imageTuning.evSteps) {
            -2 -> rbEvM2.isChecked = true
            -1 -> rbEvM1.isChecked = true
            1 -> rbEvP1.isChecked = true
            2 -> rbEvP2.isChecked = true
            else -> rbEv0.isChecked = true
        }
        when (prefs.imageTuning.noiseReductionMode) {
            0 -> rbNrOff.isChecked = true
            2 -> rbNrHq.isChecked = true
            else -> rbNrFast.isChecked = true
        }

        swRearViewOverlay.isChecked = prefs.isRearViewOverlayEnabled
        swStatusOverlay.isChecked = prefs.isStatusOverlayEnabled
        swAssistBall.isChecked = prefs.isAssistBallEnabled

        etSentryHomeSsids.setText(prefs.sentryHomeSsids.joinToString("\n"))
        etLanPort.setText(prefs.lanSharePort.toString())

        // 存储空间管理偏好
        tvVideoQuotaVal.text = "${prefs.storageVideoQuotaGb} GB"
        tvCleanRatioVal.text = "${prefs.storageCleanupRatioPercent} %"
        tvPhotoQuotaVal.text = "${prefs.storagePhotoQuotaGb} GB"
        updateTriggerSelection(prefs.storageCleanupTrigger)
    }

    /**
     * 统一处理侧栏导航的选中态。文字色、图标色（compoundDrawableTintList）、
     * 背景、字重必须一起切，只改其中一个会出现「文字蓝了图标还是灰」的半选中态。
     */
    private fun highlightNav(items: List<Pair<TextView, View>>, active: TextView) {
        for ((btn, _) in items) {
            val isCur = (btn == active)
            btn.setBackgroundResource(
                if (isCur) R.drawable.sp_bg_nav_item_active else R.drawable.sp_bg_nav_item
            )
            val color = requireContext().getColor(if (isCur) R.color.sp_ac else R.color.sp_tx2)
            btn.setTextColor(color)
            btn.setTypeface(null, if (isCur) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            btn.compoundDrawableTintList = ColorStateList.valueOf(color)
        }
        // 确保左侧导航面板中的高亮 Tab 处于可见视口内
        (active.parent?.parent as? ScrollView)?.let { railScroll ->
            val rect = android.graphics.Rect()
            active.getDrawingRect(rect)
            railScroll.offsetDescendantRectToMyCoords(active, rect)
            railScroll.requestChildRectangleOnScreen(active, rect, false)
        }
    }

    private fun setupListeners() {
        // 左侧分类导航：顺序必须与内容区自上而下的滚动顺序一致，否则用户点「第 3 项」
        // 却跳到页面靠后的位置，导航即失去空间参照意义。
        val navItems = listOf(
            btnTabCommon to groupCommon,
            btnTabBasic to groupRecord,
            btnTabBlindspot to groupBlindspot,
            btnTabHud to groupOverlay,
            btnTabStartup to groupSentry,
            btnTabPower to groupPower,
            btnTabStorage to groupStorage,
            btnTabRemote to groupRemote,
            btnTabLab to groupNetwork,
            btnTabAbout to groupAbout
        )

        var isProgrammaticScroll = false
        for ((btn, targetView) in navItems) {
            btn.setOnClickListener {
                isProgrammaticScroll = true
                highlightNav(navItems, btn)
                scrollSettingsContent.smoothScrollTo(0, targetView.top)
                scrollSettingsContent.postDelayed({
                    isProgrammaticScroll = false
                }, 500)
            }
        }

        // 滚动反查高亮：点击导航平滑滚动时不互相打架；用户手动滚动时根据当前位置自动同步左侧高亮；
        // 触底时自动高亮最底部「关于」项
        scrollSettingsContent.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (isProgrammaticScroll) return@setOnScrollChangeListener
            val contentChild = scrollSettingsContent.getChildAt(0)
            if (contentChild != null && scrollY + scrollSettingsContent.height >= contentChild.height - 30) {
                highlightNav(navItems, navItems.last().first)
                return@setOnScrollChangeListener
            }
            val offset = scrollY + 80
            var current = navItems.first().first
            for ((btn, targetView) in navItems) {
                if (targetView.top <= offset) current = btn
            }
            highlightNav(navItems, current)
        }

        highlightNav(navItems, btnTabCommon)

        // 单选组即时落盘：此前必须点「保存配置」才写，用户改完直接返回就会静默丢失
        rgChunkDuration.setOnCheckedChangeListener { _, checkedId ->
            SophonApp.preferences.chunkDurationSec = when (checkedId) {
                R.id.rb_chunk_1 -> 60
                R.id.rb_chunk_5 -> 300
                else -> 180
            }
            com.vongzh.sophon.service.SophonCoreService.instance?.refreshChunkDuration()
        }

        // 主题模式单选即时切换与持久化
        rgThemeMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_theme_light -> com.vongzh.sophon.core.config.SophonPreferences.THEME_MODE_LIGHT
                R.id.rb_theme_dark -> com.vongzh.sophon.core.config.SophonPreferences.THEME_MODE_DARK
                else -> com.vongzh.sophon.core.config.SophonPreferences.THEME_MODE_SYSTEM
            }
            if (SophonApp.preferences.themeMode != mode) {
                switchThemeMode(mode)
            }
        }

        rgCrashSensitivity.setOnCheckedChangeListener { _, checkedId ->
            SophonApp.preferences.crashSensitivity = when (checkedId) {
                R.id.rb_crash_low -> 0
                R.id.rb_crash_high -> 2
                else -> 1
            }
        }

        // 曝光与降噪同样即时下发：此前「套用夜视预设」能立刻生效、手改单选却要等保存，行为不一致
        val applyImageTuning = {
            val prefs = SophonApp.preferences
            val evSteps = when {
                rbEvM2.isChecked -> -2
                rbEvM1.isChecked -> -1
                rbEvP1.isChecked -> 1
                rbEvP2.isChecked -> 2
                else -> 0
            }
            val nrMode = when {
                rbNrOff.isChecked -> 0
                rbNrHq.isChecked -> 2
                else -> ImageTuning.DEFAULT_NOISE_MODE
            }
            prefs.imageTuning = ImageTuning(evSteps = evSteps, noiseReductionMode = nrMode)
            com.vongzh.sophon.service.SophonCoreService.instance?.refreshImageTuning()
        }
        rgExposure.setOnCheckedChangeListener { _, _ -> applyImageTuning() }
        rgNoise.setOnCheckedChangeListener { _, _ -> applyImageTuning() }

        // 开关即时落盘：此前 18 个开关全部只在点「保存配置」时写入，用户拨一下再返回
        // 就静默丢失，且与「步进器/单选组已即时生效」的行为自相矛盾。这里统一即时写入，
        // 「保存配置」退化为只兜底提交文本框。
        swAudioRecord.setOnCheckedChangeListener { _, v ->
            SophonApp.preferences.isAudioRecordEnabled = v
            // 真正起停麦克风采集：本开关此前只改 UI 图标，录出来的视频没有音轨。
            // 返回值必须处理——采集失败却留着开关在开位，等于又造一条假设置。
            val core = com.vongzh.sophon.service.SophonCoreService.instance
            val active = core?.refreshCabinAudio() ?: false
            if (v && !active) {
                // 三种失败原因必须分开说，否则最正常的「停车时先打开开关」会被说成故障：
                // 未在录制时开关本来就不该起麦（避免无消费者空转），属于预期行为。
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    requireContext(), android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val msg = when {
                    !granted -> "未授予麦克风权限，录像不会包含音轨，请到权限页授权"
                    core?.isRecordingFlow?.value != true -> "已记录，将在开始录制时自动收音"
                    else -> "麦克风被其他应用占用，将在下次录制开始时重试"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
        swAutoCrashLock.setOnCheckedChangeListener { _, v -> SophonApp.preferences.isAutoCrashLockEnabled = v }
        swShowFabLabels.setOnCheckedChangeListener { _, v -> SophonApp.preferences.isShowFabLabels = v }
        swTurnSignalBlindSpot.setOnCheckedChangeListener { _, v ->
            // BlindSpotOverlayService 已注册偏好变更监听，写入即实时收起/恢复浮窗
            SophonApp.preferences.isBlindSpotEnabled = v
        }
        swDoorOpenWarning.setOnCheckedChangeListener { _, v -> SophonApp.preferences.isDoorOpenWarningEnabled = v }
        swStopRecordOnPark.setOnCheckedChangeListener { _, v -> SophonApp.preferences.isStopRecordOnParkEnabled = v }
        swStopRecordOnDriverDoor.setOnCheckedChangeListener { _, v -> SophonApp.preferences.isStopRecordOnDriverDoorEnabled = v }

        swOsdWatermark.setOnCheckedChangeListener { _, v -> SophonApp.preferences.isOsdWatermarkEnabled = v }
        swRainyNight.setOnCheckedChangeListener { _, v ->
            SophonApp.preferences.isRainyNightModeEnabled = v
            com.vongzh.sophon.service.SophonCoreService.instance?.refreshRainyNightMode()
        }
        swPipAuto.setOnCheckedChangeListener { _, v -> SophonApp.preferences.isPipAutoEnabled = v }

        swSentryMode.setOnCheckedChangeListener { _, v -> SophonApp.preferences.isSentryModeEnabled = v }
        swSentryDeterrent.setOnCheckedChangeListener { _, v -> SophonApp.preferences.isSentryDeterrentEnabled = v }
        swBatteryProtection.setOnCheckedChangeListener { _, v -> SophonApp.preferences.isBatteryProtectionEnabled = v }

        // 悬浮窗矩阵：写入后必须立刻重算悬浮窗，否则用户开了开关却看不到窗口出现
        val applyOverlays = {
            OverlayMatrixController.apply(requireContext())
        }
        swRearViewOverlay.setOnCheckedChangeListener { _, v ->
            SophonApp.preferences.isRearViewOverlayEnabled = v
            applyOverlays()
        }
        swStatusOverlay.setOnCheckedChangeListener { _, v ->
            SophonApp.preferences.isStatusOverlayEnabled = v
            applyOverlays()
        }
        swAssistBall.setOnCheckedChangeListener { _, v ->
            SophonApp.preferences.isAssistBallEnabled = v
            applyOverlays()
        }

        swBarkEnabled.setOnCheckedChangeListener { _, v -> SophonApp.preferences.isBarkEnabled = v }
        swTelegramEnabled.setOnCheckedChangeListener { _, v ->
            SophonApp.preferences.isTelegramEnabled = v
            // 轮询此前仅 App 启动时拉起一次，这里热重启，避免「配好 Token 却收不到命令」
            SophonApp.remoteDispatcher.restartRemoteServices()
        }
        swFeishuEnabled.setOnCheckedChangeListener { _, v -> SophonApp.preferences.isFeishuEnabled = v }

        btnFeishuAppToggle.setOnClickListener {
            setFeishuAppExpanded(groupFeishuAppFields.visibility != View.VISIBLE)
        }

        // 围栏白名单：失焦即落盘，SentryHomeGeofence 每次轮询都会重读白名单
        etSentryHomeSsids.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                commitSentryHomeSsids()
                refreshGeofenceStatus()
            }
        }

        // 局域网快传：状态与地址来自服务真实运行状态，按钮打开真实二维码
        btnLanShareQr.setOnClickListener {
            com.vongzh.sophon.ui.lan.LanShareDialog(requireContext()).show()
        }

        // 关于页：只挂真实可执行的动作（无更新后端，故不提供「检查更新」）
        btnAboutPrivacy.setOnClickListener { showStructuredPolicyDialog(isDisclaimer = false) }
        btnAboutDisclaimer.setOnClickListener { showStructuredPolicyDialog(isDisclaimer = true) }
        btnAboutDiag.setOnClickListener { copyDiagnosticsToClipboard() }

        // Stepper: 窄道会车双闪触发车速 (5 ~ 60 km/h, step 5)
        btnHazardSpeedMinus.setOnClickListener { adjustHazardSpeed(-5f) }
        btnHazardSpeedPlus.setOnClickListener { adjustHazardSpeed(5f) }

        // Stepper: 低压保护阈值 (11.0 ~ 13.0 V, step 0.1)
        btnBattVoltsMinus.setOnClickListener { adjustBatteryVolts(-0.1f) }
        btnBattVoltsPlus.setOnClickListener { adjustBatteryVolts(0.1f) }

        btnBackupConfig.setOnClickListener {
            saveAllPreferences()
            backupConfigToUsb()
        }

        btnRestoreConfig.setOnClickListener { restoreConfigFromUsb() }

        // LAN 快传服务端口：失焦即时落盘并热重启服务
        etLanPort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val portText = etLanPort.text.toString().trim()
                val port = portText.toIntOrNull()
                if (port != null && port in 1024..65535) {
                    if (port != SophonApp.preferences.lanSharePort) {
                        SophonApp.preferences.lanSharePort = port
                        com.vongzh.sophon.service.SophonCoreService.instance?.restartLanShareServer()
                    }
                } else if (portText.isNotEmpty()) {
                    Toast.makeText(context, "LAN 端口需为 1024-65535，已保留原值", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 远程通知凭据：失焦即时落盘并热重启通道，保证「所填即所存」
        val autoSaveRemoteFields = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val prefs = SophonApp.preferences
                prefs.barkServerUrl = etBarkServer.text.toString().trim()
                prefs.barkDeviceKey = etBarkKey.text.toString().trim()
                prefs.telegramBotToken = etTelegramBotToken.text.toString().trim()
                prefs.telegramChatId = etTelegramChatId.text.toString().trim()
                prefs.feishuWebhookUrl = etFeishuWebhook.text.toString().trim()
                prefs.feishuAppId = etFeishuAppId.text.toString().trim()
                prefs.feishuAppSecret = etFeishuAppSecret.text.toString().trim()
                prefs.feishuReceiveId = etFeishuReceiveId.text.toString().trim()
                SophonApp.remoteDispatcher.restartRemoteServices()
            }
        }
        listOf(
            etBarkServer, etBarkKey,
            etTelegramBotToken, etTelegramChatId,
            etFeishuWebhook, etFeishuAppId, etFeishuAppSecret, etFeishuReceiveId
        ).forEach { it.onFocusChangeListener = autoSaveRemoteFields }

        // 存储说明弹窗 (严正重申不支持车身闪存原则)
        btnStorageHelp.setOnClickListener {
            showStoragePolicyDialog()
        }

        // Stepper: 视频存储上限 (5 ~ 128 GB, step 5)
        btnVideoQuotaMinus.setOnClickListener {
            val prefs = SophonApp.preferences
            val cur = prefs.storageVideoQuotaGb
            if (cur > 5) {
                val next = cur - 5
                prefs.storageVideoQuotaGb = next
                tvVideoQuotaVal.text = "$next GB"
            }
        }
        btnVideoQuotaPlus.setOnClickListener {
            val prefs = SophonApp.preferences
            val cur = prefs.storageVideoQuotaGb
            if (cur < 128) {
                val next = cur + 5
                prefs.storageVideoQuotaGb = next
                tvVideoQuotaVal.text = "$next GB"
            }
        }

        // Stepper: 超出限制删除比例 (10 ~ 60 %, step 5)
        btnCleanRatioMinus.setOnClickListener {
            val prefs = SophonApp.preferences
            val cur = prefs.storageCleanupRatioPercent
            if (cur > 10) {
                val next = cur - 5
                prefs.storageCleanupRatioPercent = next
                tvCleanRatioVal.text = "$next %"
            }
        }
        btnCleanRatioPlus.setOnClickListener {
            val prefs = SophonApp.preferences
            val cur = prefs.storageCleanupRatioPercent
            if (cur < 60) {
                val next = cur + 5
                prefs.storageCleanupRatioPercent = next
                tvCleanRatioVal.text = "$next %"
            }
        }

        // Stepper: 照片存储上限 (2 ~ 64 GB, step 2)
        btnPhotoQuotaMinus.setOnClickListener {
            val prefs = SophonApp.preferences
            val cur = prefs.storagePhotoQuotaGb
            if (cur > 2) {
                val next = cur - 2
                prefs.storagePhotoQuotaGb = next
                tvPhotoQuotaVal.text = "$next GB"
            }
        }
        btnPhotoQuotaPlus.setOnClickListener {
            val prefs = SophonApp.preferences
            val cur = prefs.storagePhotoQuotaGb
            if (cur < 64) {
                val next = cur + 2
                prefs.storagePhotoQuotaGb = next
                tvPhotoQuotaVal.text = "$next GB"
            }
        }

        // 单选胶囊：超限删除时机
        btnTriggerRecordEnd.setOnClickListener {
            SophonApp.preferences.storageCleanupTrigger = 0
            updateTriggerSelection(0)
        }
        btnTriggerHighUsage.setOnClickListener {
            SophonApp.preferences.storageCleanupTrigger = 1
            updateTriggerSelection(1)
        }
        btnTriggerStartup.setOnClickListener {
            SophonApp.preferences.storageCleanupTrigger = 2
            updateTriggerSelection(2)
        }

        // 格式化 U 盘
        btnFormatStorage.setOnClickListener {
            showFormatConfirmDialog()
        }

        btnApplyNightPreset.setOnClickListener {
            rbEvP2.isChecked = true
            rbNrOff.isChecked = true
            prefsNightPresetApplied()
        }

        // 测试 Bark
        btnTestBark.setOnClickListener {
            val srv = etBarkServer.text.toString().trim().ifEmpty { "https://api.day.app" }
            val key = etBarkKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(context, "请输入 Bark DeviceKey", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnTestBark.isEnabled = false
            btnTestBark.text = "发送中..."
            barkNotifier.sendPush(
                serverUrl = srv,
                deviceKey = key,
                title = "极视 (Sophon) 车机测试",
                body = "座舱哨兵与防盗告警通道联通测试成功！"
            ) { ok, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    btnTestBark.isEnabled = true
                    btnTestBark.text = "测试推送"
                    Toast.makeText(context, if (ok) "Bark 推送成功" else "Bark 推送失败，请检查网络", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 测试 Telegram
        btnTestTelegram.setOnClickListener {
            val token = etTelegramBotToken.text.toString().trim()
            val chat = etTelegramChatId.text.toString().trim()
            if (token.isEmpty() || chat.isEmpty()) {
                Toast.makeText(context, "请输入 Telegram Bot Token 与 Chat ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnTestTelegram.isEnabled = false
            btnTestTelegram.text = "发送中..."
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                // 与 Bark / 飞书保持一致：如实回报成功与否，而不是无条件提示「已发送」
                telegramClient.sendTextMessage(token, chat, "🚨 极视 (Sophon) 哨兵通道联通测试成功！") { ok ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        btnTestTelegram.isEnabled = true
                        btnTestTelegram.text = "测试 TG"
                        Toast.makeText(context, if (ok) "Telegram 推送成功" else "Telegram 推送失败，请检查 Token 与 Chat ID", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 测试 飞书：Webhook 与自建应用两套凭据都能测，否则只填了 AppId/Secret 的用户
        // 会被一句「请输入 Webhook 地址」挡回去，误以为自建应用模式不可用。
        btnTestFeishu.setOnClickListener {
            val webhook = etFeishuWebhook.text.toString().trim()
            val appId = etFeishuAppId.text.toString().trim()
            val appSecret = etFeishuAppSecret.text.toString().trim()
            val receiveId = etFeishuReceiveId.text.toString().trim()
            val useApp = webhook.isEmpty() && appId.isNotEmpty() && appSecret.isNotEmpty() && receiveId.isNotEmpty()
            if (webhook.isEmpty() && !useApp) {
                Toast.makeText(context, "请填写飞书 Webhook 地址，或完整填写自建应用的 AppId / AppSecret / 接收者 ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnTestFeishu.isEnabled = false
            btnTestFeishu.text = "发送中..."
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val ok = if (useApp) {
                    feishuClient.sendAppMessage(
                        appId = appId,
                        appSecret = appSecret,
                        receiveId = receiveId,
                        text = "【极视 (Sophon) 通道测试】\n哨兵告警自建应用通道联通成功！",
                        photoFile = null
                    )
                } else {
                    feishuClient.sendWebhookMessage(webhook, "🚨 极视 (Sophon) 哨兵通道飞书机器人联通成功！")
                }
                withContext(Dispatchers.Main) {
                    btnTestFeishu.isEnabled = true
                    btnTestFeishu.text = "测试飞书"
                    Toast.makeText(
                        context,
                        if (ok) "飞书通知成功（${if (useApp) "自建应用" else "Webhook"}）" else "飞书通知失败，请检查凭据与网络",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // USB 实测
        btnRunUsbBenchmark.setOnClickListener {
            val root = SophonApp.storageVault.currentUsbRoot.value
            if (root == null || !SophonApp.storageVault.isUsbMounted.value) {
                Toast.makeText(context, "未插入或未检测到可用 U 盘介质", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnRunUsbBenchmark.isEnabled = false
            btnRunUsbBenchmark.text = "测速中..."
            tvBenchmarkResult.text = "正在执行 8MB Direct I/O 并发直写物理压测..."

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val report = usbBenchmark.runBenchmark(root)
                withContext(Dispatchers.Main) {
                    btnRunUsbBenchmark.isEnabled = true
                    btnRunUsbBenchmark.text = "重新测速"
                    val speed = report.writeSpeedMBs
                    val isPass = speed >= 20.0
                    tvBenchmarkResult.text = String.format(
                        Locale.US,
                        "实测写入速率：%.1f MB/s · %s",
                        speed,
                        report.summary
                    )
                    tvBenchmarkResult.setTextColor(requireContext().getColor(if (isPass) R.color.sp_ac else R.color.sp_dg))
                }
            }
        }
    }

    private fun updateTriggerSelection(trigger: Int) {
        val accentBg = R.drawable.sp_bg_pill_accent
        val normalBg = R.drawable.sp_bg_pill
        val accentColor = requireContext().getColor(R.color.sp_ac)
        val normalColor = requireContext().getColor(R.color.sp_tx2)

        btnTriggerRecordEnd.setBackgroundResource(if (trigger == 0) accentBg else normalBg)
        btnTriggerRecordEnd.setTextColor(if (trigger == 0) accentColor else normalColor)

        btnTriggerHighUsage.setBackgroundResource(if (trigger == 1) accentBg else normalBg)
        btnTriggerHighUsage.setTextColor(if (trigger == 1) accentColor else normalColor)

        btnTriggerStartup.setBackgroundResource(if (trigger == 2) accentBg else normalBg)
        btnTriggerStartup.setTextColor(if (trigger == 2) accentColor else normalColor)
    }

    /** 自建应用三项凭据是否齐全——RemoteDispatcher 只在三者齐全时才走这条通道 */
    private fun isFeishuAppConfigured(): Boolean {
        val prefs = SophonApp.preferences
        return prefs.feishuAppId.isNotBlank() &&
            prefs.feishuAppSecret.isNotBlank() &&
            prefs.feishuReceiveId.isNotBlank()
    }

    /**
     * 展开/收起飞书自建应用凭据区。
     *
     * 收起时仍用摘要行如实说明「已配置 / 凭据不完整 / 未配置」：如果只做视觉折叠，
     * 已保存的凭据就变成了用户看不见却仍在生效的隐藏状态。
     */
    private fun setFeishuAppExpanded(expanded: Boolean) {
        groupFeishuAppFields.visibility = if (expanded) View.VISIBLE else View.GONE
        btnFeishuAppToggle.text = if (expanded) "收起" else "展开"
        val appId = SophonApp.preferences.feishuAppId
        tvFeishuAppSummary.text = when {
            isFeishuAppConfigured() -> "自建应用：已配置（${appId.take(12)}…）"
            appId.isNotBlank() -> "自建应用：凭据不完整（尚未生效）"
            else -> "自建应用：未配置"
        }
    }

    private fun observeStorageState() {
        viewLifecycleOwner.lifecycleScope.launch {
            SophonApp.storageVault.isUsbMounted.collectLatest { isMounted ->
                refreshStorageCapacityUI(isMounted)
                tvAboutStorage.text = if (isMounted) "已挂载" else "未挂载"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            com.vongzh.sophon.service.SophonCoreService.lanShareRunningFlow.collectLatest {
                refreshLanShareStatus()
            }
        }
    }

    /**
     * 统一刷新外置 U 盘状态胶囊：图标与着色跟随介质可用性切换，
     * 避免使用 emoji 充当状态图标（车机字体下渲染不可控）。
     */
    private fun setUsbStatusPill(ok: Boolean, text: String) {
        tvStorageUsbStatusPill.text = text
        tvStorageUsbStatusPill.setCompoundDrawablesWithIntrinsicBounds(
            if (ok) R.drawable.ic_usb_drive else R.drawable.ic_action_warning, 0, 0, 0
        )
        val color = requireContext().getColor(if (ok) R.color.sp_ac else R.color.sp_wn)
        tvStorageUsbStatusPill.compoundDrawableTintList = ColorStateList.valueOf(color)
        tvStorageUsbStatusPill.setTextColor(color)
    }

    private fun refreshStorageCapacityUI(isMounted: Boolean) {
        if (!isMounted) {
            tvStorageCardTitle.text = "外置 U 盘存储 (未检测到介质)"
            tvStorageAvailTitle.text = "未挂载可用介质"
            tvStorageTotalTitle.text = "共 0 B"
            tvStorageStatusTip.text = "请插入外置 U 盘以启用录制与循环存储"
            tvStorageRingPercent.text = "0.0%"
            tvStorageRingSummary.text = "请插入 U 盘\n启用循环存储"
            setUsbStatusPill(false, "外置 U 盘：未插入 (安全底线：已硬件级锁定车机闪存禁止写入)")

            val emptyBreakdown = StorageUsageBreakdown()
            ringStorageCapacity.updateBreakdown(emptyBreakdown)
            barStorageSegments.updateBreakdown(emptyBreakdown)

            tvLegendOther.text = "0 B (0.0%)"
            tvLegendDriving.text = "0 B (0.0%)"
            tvLegendPhotos.text = "0 B (0.0%)"
            tvLegendSentry.text = "0 B (0.0%)"
            tvLegendFree.text = "0 B (0.0%)"
            return
        }

        // 已插入 U 盘：异步查询容量分解
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val breakdown = SophonApp.storageVault.queryStorageBreakdown()
            val root = SophonApp.storageVault.currentUsbRoot.value
            val rootName = root?.name ?: "USB"

            withContext(Dispatchers.Main) {
                tvStorageCardTitle.text = "外置 U 盘存储 ($rootName)"
                tvStorageAvailTitle.text = "${formatSize(breakdown.freeBytes)} 可用"
                tvStorageTotalTitle.text = "共 ${formatSize(breakdown.totalBytes)}"

                val usedBytes = breakdown.totalBytes - breakdown.freeBytes
                val percent = if (breakdown.totalBytes > 0) (usedBytes * 100.0 / breakdown.totalBytes) else 0.0
                tvStorageRingPercent.text = String.format(Locale.US, "%.1f%%", percent)
                tvStorageRingSummary.text = "已用 ${formatSize(usedBytes)}\n共 ${formatSize(breakdown.totalBytes)}"

                if (percent >= 90.0) {
                    tvStorageStatusTip.text = "外置存储空间接近满载，已进入安全 FIFO 循环覆盖保护"
                    tvStorageStatusTip.setTextColor(requireContext().getColor(R.color.sp_wn))
                } else {
                    tvStorageStatusTip.text = "还有充足的空间，继续记录更多精彩"
                    tvStorageStatusTip.setTextColor(requireContext().getColor(R.color.sp_tx3))
                }

                setUsbStatusPill(true, "外置 U 盘：已就绪 (${formatSize(breakdown.totalBytes)} · 读写正常)")

                ringStorageCapacity.updateBreakdown(breakdown)
                barStorageSegments.updateBreakdown(breakdown)

                tvLegendOther.text = "${formatSize(breakdown.otherBytes)} (${calcPercent(breakdown.otherBytes, breakdown.totalBytes)})"
                tvLegendDriving.text = "${formatSize(breakdown.drivingBytes)} (${calcPercent(breakdown.drivingBytes, breakdown.totalBytes)})"
                tvLegendPhotos.text = "${formatSize(breakdown.photoBytes)} (${calcPercent(breakdown.photoBytes, breakdown.totalBytes)})"
                tvLegendSentry.text = "${formatSize(breakdown.sentryBytes)} (${calcPercent(breakdown.sentryBytes, breakdown.totalBytes)})"
                tvLegendFree.text = "${formatSize(breakdown.freeBytes)} (${calcPercent(breakdown.freeBytes, breakdown.totalBytes)})"
            }
        }
    }

    private fun showStoragePolicyDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("存储介质与安全规范说明")
            .setMessage(
                "【底线红线规范】\n" +
                "1. 严禁车身内部闪存录像：车机内置闪存 (eMMC/UFS) 承担仪表驱动、总线通信与座舱关键系统，高码率四路并发连续读写极易导致闪存颗粒提前耗尽坏死，引发车机黑屏、仪表瘫痪等原厂不可逆损坏。\n\n" +
                "2. 唯一指定外置存储：极视严格遵循「No USB, No Write」原则，未检测到外置 USB 介质时自动拒绝录制落盘，确保车身原厂系统 100% 绝对安全。\n\n" +
                "3. 循环覆盖机制：当外置 U 盘达到配额上限时，系统按最早时间顺序循环清理普通行车视频；碰撞紧急事件与哨兵抓拍受写保护，永不被自动覆盖。"
            )
            .setPositiveButton("我已知晓并遵循", null)
            .show()
    }

    private fun showFormatConfirmDialog() {
        val root = SophonApp.storageVault.currentUsbRoot.value
        if (root == null || !SophonApp.storageVault.isUsbMounted.value) {
            Toast.makeText(context, "未插入外置 U 盘，无需清理", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("确认清理 / 初始化 U 盘")
            .setMessage("该操作将清空外置 U 盘内 Sophon 录制金库中的所有行车视频、相册抓拍与哨兵加锁文件，操作不可恢复。\n\n请确认是否立即清空？")
            .setPositiveButton("确认清空") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val result = runCatching {
                        val baseDir = File(root, "Sophon")
                        if (baseDir.exists()) baseDir.deleteRecursively()
                        // deleteRecursively() 对个别失败是静默的，删除后必须复查目录是否真的没了
                        val remains = baseDir.exists()
                        val leftovers = if (remains) baseDir.listFiles()?.size ?: 0 else 0
                        Pair(remains, leftovers)
                    }
                    withContext(Dispatchers.Main) {
                        val (remains, leftovers) = result.getOrNull() ?: Pair(true, -1)
                        val msg = if (result.isFailure) {
                            "清理失败：${result.exceptionOrNull()?.message}"
                        } else if (remains) {
                            // 残留文件通常是被其他进程占用或只读挂载，如实说明而不是谎报成功
                            "清理未完成：仍有 $leftovers 项无法删除，请检查 U 盘是否写保护"
                        } else {
                            "外置存储已清空初始化"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        refreshStorageCapacityUI(SophonApp.storageVault.isUsbMounted.value)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun calcPercent(part: Long, total: Long): String {
        if (total <= 0L) return "0.0%"
        val p = (part * 100.0) / total
        return String.format(Locale.US, "%.1f%%", p)
    }

    private fun saveAllPreferences() {
        val prefs = SophonApp.preferences

        val chunkSec = when {
            rbChunk1.isChecked -> 60
            rbChunk5.isChecked -> 300
            else -> 180
        }
        prefs.chunkDurationSec = chunkSec

        prefs.isAudioRecordEnabled = swAudioRecord.isChecked
        prefs.isAutoCrashLockEnabled = swAutoCrashLock.isChecked
        prefs.isBlindSpotEnabled = swTurnSignalBlindSpot.isChecked
        prefs.isDowEnabled = swDoorOpenWarning.isChecked

        prefs.isBarkEnabled = swBarkEnabled.isChecked
        prefs.barkServerUrl = etBarkServer.text.toString().trim()
        prefs.barkDeviceKey = etBarkKey.text.toString().trim()

        prefs.isTelegramEnabled = swTelegramEnabled.isChecked
        prefs.telegramBotToken = etTelegramBotToken.text.toString().trim()
        prefs.telegramChatId = etTelegramChatId.text.toString().trim()

        prefs.isFeishuEnabled = swFeishuEnabled.isChecked
        prefs.feishuWebhookUrl = etFeishuWebhook.text.toString().trim()
        prefs.feishuAppId = etFeishuAppId.text.toString().trim()
        prefs.feishuAppSecret = etFeishuAppSecret.text.toString().trim()
        prefs.feishuReceiveId = etFeishuReceiveId.text.toString().trim()

        prefs.isOsdWatermarkEnabled = swOsdWatermark.isChecked
        prefs.isRainyNightModeEnabled = swRainyNight.isChecked
        prefs.isPipAutoEnabled = swPipAuto.isChecked
        // 雨夜模式原只在服务启动时读取，这里热下发，避免「改了没反应」
        com.vongzh.sophon.service.SophonCoreService.instance?.refreshRainyNightMode()

        prefs.isSentryModeEnabled = swSentryMode.isChecked
        prefs.isSentryDeterrentEnabled = swSentryDeterrent.isChecked

        prefs.isBatteryProtectionEnabled = swBatteryProtection.isChecked

        prefs.isStopRecordOnParkEnabled = swStopRecordOnPark.isChecked
        prefs.isStopRecordOnDriverDoorEnabled = swStopRecordOnDriverDoor.isChecked

        val evSteps = when {
            rbEvM2.isChecked -> -2
            rbEvM1.isChecked -> -1
            rbEvP1.isChecked -> 1
            rbEvP2.isChecked -> 2
            else -> 0
        }
        val nrMode = when {
            rbNrOff.isChecked -> 0
            rbNrHq.isChecked -> 2
            else -> ImageTuning.DEFAULT_NOISE_MODE
        }
        prefs.imageTuning = ImageTuning(evSteps = evSteps, noiseReductionMode = nrMode)
        // 立即热下发到四路摄像头，无需重启录制
        com.vongzh.sophon.service.SophonCoreService.instance?.refreshImageTuning()

        prefs.isRearViewOverlayEnabled = swRearViewOverlay.isChecked
        prefs.isStatusOverlayEnabled = swStatusOverlay.isChecked
        prefs.isAssistBallEnabled = swAssistBall.isChecked
        // 用 context 而非 requireContext()：本函数也会在 onDestroyView 兜底调用，届时不可强断言已附着
        context?.let { OverlayMatrixController.apply(it) }

        commitSentryHomeSsids()

        val portText = etLanPort.text.toString().trim()
        val port = portText.toIntOrNull()
        var lanPortChanged = false
        if (port != null && port in 1024..65535) {
            lanPortChanged = port != prefs.lanSharePort
            prefs.lanSharePort = port
        } else if (portText.isNotEmpty()) {
            Toast.makeText(context, "LAN 端口需为 1024-65535，已保留原值", Toast.LENGTH_SHORT).show()
        }

        // 凭据可能刚被改写，热重启远程通道
        SophonApp.remoteDispatcher.restartRemoteServices()
        // 端口此前只能等重启应用才生效，改完当场重开监听；新端口若仍被占用，
        // 卡片状态会在几百毫秒内如实回落为「未运行」。
        if (lanPortChanged) {
            com.vongzh.sophon.service.SophonCoreService.instance?.restartLanShareServer()
        }
    }

    /** 提交围栏白名单：按行拆分、去空白、去重 */
    private fun commitSentryHomeSsids() {
        SophonApp.preferences.sentryHomeSsids = etSentryHomeSsids.text.toString()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * 离开设置页前兜底落盘。
     *
     * 文本框类输入此前完全依赖用户记得点「保存配置」，直接返回即静默丢弃；
     * 这里在视图销毁前统一提交一次，保证「所见即所存」。
     */
    override fun onDestroyView() {
        if (::etSentryHomeSsids.isInitialized) {
            runCatching { saveAllPreferences() }
                .onFailure { android.util.Log.w("SophonSettings", "离开设置页兜底落盘失败", it) }
        }
        geofenceJob?.cancel()
        geofenceJob = null
        super.onDestroyView()
    }

    /**
     * 刷新「局域网快传」卡片的真实运行状态。
     *
     * 状态胶囊此前硬编码「已就绪」，属于假设置；现改为读取 SophonCoreService 暴露的
     * 服务运行状态与监听端口，端口起不来就如实显示未运行。
     */
    private fun refreshLanShareStatus() {
        val running = com.vongzh.sophon.service.SophonCoreService.lanShareRunningFlow.value
        val port = com.vongzh.sophon.service.SophonCoreService.lanSharePortFlow.value
        val ip = resolveLanAddress()

        if (running && port != null) {
            tvLanShareStatusPill.text = "运行中 :$port"
            tvLanShareStatusPill.setTextColor(requireContext().getColor(R.color.sp_ok))
            tvLanShareStatusPill.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_action_check, 0, 0, 0
            )
            tvLanShareStatusPill.compoundDrawableTintList =
                ColorStateList.valueOf(requireContext().getColor(R.color.sp_ok))
            tvLanShareAddr.text = if (ip != null) {
                "http://$ip:$port"
            } else {
                "服务已启动，但未获取到局域网 IP"
            }
        } else {
            tvLanShareStatusPill.text = "未运行"
            tvLanShareStatusPill.setTextColor(requireContext().getColor(R.color.sp_wn))
            tvLanShareStatusPill.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_action_warning, 0, 0, 0
            )
            tvLanShareStatusPill.compoundDrawableTintList =
                ColorStateList.valueOf(requireContext().getColor(R.color.sp_wn))
            tvLanShareAddr.text = "端口被占用或服务未启动，可在下方换个端口后保存，随即自动重试"
        }
    }

    /** 车载结构化协议与声明弹窗：使用专属多卡片精细化布局，摒弃枯燥纯文字 */
    private fun showStructuredPolicyDialog(isDisclaimer: Boolean) {
        val layoutRes = if (isDisclaimer) R.layout.dialog_disclaimer else R.layout.dialog_privacy_policy
        val dialog = android.app.Dialog(requireContext()).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(layoutRes)
            window?.let { win ->
                win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                win.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                win.setDimAmount(0.65f)
                val metrics = resources.displayMetrics
                val targetWidthPx = (740 * metrics.density).toInt()
                val maxWidthPx = (metrics.widthPixels * 0.92f).toInt()
                val finalWidthPx = minOf(targetWidthPx, maxWidthPx)
                win.setLayout(finalWidthPx, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        dialog.findViewById<View>(R.id.btn_dialog_close)?.setOnClickListener {
            dialog.dismiss()
        }
        dialog.findViewById<View>(R.id.btn_dialog_confirm)?.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    /**
     * 复制诊断信息：内容全部来自运行时真实状态，供反馈问题时粘贴。
     *
     * queryStorageBreakdown() 要遍历整个 U 盘目录树，必须放 IO 线程——此前在主线程
     * 直接调用，U 盘里存了几十 GB 录像时会把设置页卡住。
     */
    private fun copyDiagnosticsToClipboard() {
        // 版本信息与 Build 字段无阻塞开销，先在主线程取，避免协程里再碰 Fragment
        val info = runCatching { resolvePackageInfo() }.getOrNull()
        val version = info?.versionName ?: "未知"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info?.longVersionCode?.toString() ?: "未知"
        } else {
            @Suppress("DEPRECATION")
            info?.versionCode?.toString() ?: "未知"
        }
        val buildType = if (isDebugBuild()) "debug" else "release"

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val prefs = SophonApp.preferences
            val mounted = SophonApp.storageVault.isUsbMounted.value
            val breakdown = runCatching { SophonApp.storageVault.queryStorageBreakdown() }.getOrNull()
            val lanRunning = com.vongzh.sophon.service.SophonCoreService.lanShareRunningFlow.value
            val lanPort = com.vongzh.sophon.service.SophonCoreService.lanSharePortFlow.value

            val text = buildString {
                append("极视 Sophon 诊断信息\n")
                append("应用版本：").append(version).append(" (").append(versionCode).append(")\n")
                append("构建类型：").append(buildType).append('\n')
                append("系统：Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append("外置存储：").append(
                    if (mounted && breakdown != null) {
                        "已挂载 ${formatSize(breakdown.freeBytes)} 可用 / 共 ${formatSize(breakdown.totalBytes)}"
                    } else if (mounted) {
                        "已挂载（容量统计失败）"
                    } else {
                        "未挂载"
                    }
                ).append('\n')
                append("座舱录音：").append(if (prefs.isAudioRecordEnabled) "开" else "关")
                append(if (prefs.isAudioRecordEnabled) "（需 RECORD_AUDIO 授权，否则实际无音轨）\n" else "\n")
                append("哨兵模式：").append(if (prefs.isSentryModeEnabled) "开" else "关").append('\n')
                append("低压保护：").append(if (prefs.isBatteryProtectionEnabled) "开" else "关")
                append(" (").append(String.format(Locale.US, "%.1f", prefs.batteryProtectionThresholdVolts)).append("V)\n")
                append("快传服务：").append(if (lanRunning) "运行中" else "未运行")
                append(" · 端口 ").append(lanPort ?: prefs.lanSharePort).append('\n')
                append("切片时长：").append(prefs.chunkDurationSec).append("s")
            }

            withContext(Dispatchers.Main) {
                val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                if (cm == null) {
                    Toast.makeText(context, "当前设备未提供剪贴板服务", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                cm.setPrimaryClip(android.content.ClipData.newPlainText("SophonDiagnostics", text))
                Toast.makeText(context, "诊断信息已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun adjustHazardSpeed(delta: Float) {
        val prefs = SophonApp.preferences
        val next = (prefs.hazardSpeedThresholdKmh + delta).coerceIn(5f, 60f)
        prefs.hazardSpeedThresholdKmh = next
        tvHazardSpeedVal.text = String.format(Locale.US, "%.0f km/h", next)
    }

    private fun adjustBatteryVolts(delta: Float) {
        val prefs = SophonApp.preferences
        // 先量化到 0.1V 网格再夹紧，避免浮点累积出 11.799999 这类脏值
        val next = ((Math.round((prefs.batteryProtectionThresholdVolts + delta) * 10f) / 10f)).coerceIn(11.0f, 13.0f)
        prefs.batteryProtectionThresholdVolts = next
        tvBattVoltsVal.text = String.format(Locale.US, "%.1f V", next)
    }

    /** 遍历网卡取首个非回环 IPv4 地址，无需任何权限 */
    private fun resolveLanAddress(): String? {
        return try {
            val enums = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in enums) {
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses ?: continue
                for (addr in addrs) {
                    if (addr.isLoopbackAddress) continue
                    if (addr is java.net.Inet4Address) return addr.hostAddress
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** 备份：把全部偏好序列化为 JSON 写入 U 盘 */
    private fun backupConfigToUsb() {
        val root = SophonApp.storageVault.currentUsbRoot.value
        if (root == null || !SophonApp.storageVault.isUsbMounted.value) {
            Toast.makeText(context, "未检测到可用 U 盘，无法备份配置", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val dir = File(root, "SophonRecord").apply { mkdirs() }
                val file = File(dir, "sophon_config_backup.json")
                val obj = JSONObject()
                var count = 0
                for ((key, value) in SophonApp.preferences.exportAll()) {
                    when (value) {
                        is Boolean, is Int, is Long, is Float, is String -> {
                            obj.put(key, value)
                            count++
                        }
                        else -> Unit
                    }
                }
                obj.put("_appVersion", resolveAppVersion())
                obj.put("_backupAtMs", System.currentTimeMillis())
                file.writeText(obj.toString(2))
                count to file
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { (count, file) ->
                    Toast.makeText(context, "已备份 $count 项配置至 ${file.name}", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(context, "备份失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 恢复：从 U 盘备份文件读回偏好并刷新界面 */
    private fun restoreConfigFromUsb() {
        val root = SophonApp.storageVault.currentUsbRoot.value
        if (root == null || !SophonApp.storageVault.isUsbMounted.value) {
            Toast.makeText(context, "未检测到可用 U 盘，无法恢复配置", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val file = File(File(root, "SophonRecord"), "sophon_config_backup.json")
                if (!file.exists()) throw IllegalStateException("U 盘中未找到备份文件")
                val obj = JSONObject(file.readText())
                val map = linkedMapOf<String, Any?>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k.startsWith("_")) continue
                    map[k] = obj.get(k)
                }
                SophonApp.preferences.importAll(map)
                map.size
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { count ->
                    loadCurrentPreferences()
                    refreshGeofenceStatus()
                    Toast.makeText(context, "已从 U 盘恢复 $count 项配置", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(context, "恢复失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 夜视预设：勾选 +2EV 与关闭降噪后立即下发 */
    private fun prefsNightPresetApplied() {
        val prefs = SophonApp.preferences
        prefs.imageTuning = ImageTuning.NIGHT_VISION
        com.vongzh.sophon.service.SophonCoreService.instance?.refreshImageTuning()
        Toast.makeText(context, "已套用夜视预设：+2EV · 关闭降噪", Toast.LENGTH_SHORT).show()
    }

    /** 渲染固件与版本信息，包含车机型号、系统固件、应用构建与存储状态 */
    private fun loadNetworkAndFirmwareInfo() {
        val ctx = context ?: return
        val info = runCatching { resolvePackageInfo() }.getOrNull()
        val version = info?.versionName ?: "未知"
        val mounted = SophonApp.storageVault.isUsbMounted.value
        val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}"
        val firmwareBuild = Build.DISPLAY.takeIf { !it.isNullOrBlank() } ?: Build.ID
        tvFirmwareInfo.text = buildString {
            append("设备型号：").append(deviceInfo).append('\n')
            append("固件版本：").append(firmwareBuild).append(" (Android ").append(Build.VERSION.RELEASE).append(", API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("应用构建：").append(if (isDebugBuild()) "Debug 测试版" else "Release 正式版").append('\n')
            append("存储挂载：").append(if (mounted) "外置存储已挂载" else "未检测到外置存储")
        }

        // 关于页三行
        tvAboutVersion.text = buildString {
            append("v").append(version)
            append(" · ").append(if (isDebugBuild()) "debug" else "release")
        }
        tvAboutPlatform.text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        tvAboutStorage.text = if (mounted) "已挂载" else "未挂载"
    }

    private fun resolvePackageInfo(): android.content.pm.PackageInfo? {
        val ctx = context ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        }
    }

    /**
     * 渲染围栏状态。仅在用户真的配置了白名单时才申请 SSID 读取权限，
     * 拒绝授权时功能静默失效，不影响录制主流程。
     */
    private fun refreshGeofenceStatus() {
        if (!::tvSentryGeofenceStatus.isInitialized) return
        val prefs = SophonApp.preferences
        geofenceJob?.cancel()
        if (prefs.sentryHomeSsids.isEmpty()) {
            tvSentryGeofenceStatus.text = "围栏：未配置（哨兵不做撤防）"
            return
        }
        val geofence = SophonApp.sentryHomeGeofence
        geofenceJob = viewLifecycleOwner.lifecycleScope.launch {
            geofence.currentSsid.collect { ssid ->
                val state = when {
                    ssid == null -> "未连接 Wi-Fi"
                    geofence.isAtHome.value -> "命中围栏（哨兵已撤防）"
                    else -> "不在围栏内（哨兵正常布防）"
                }
                tvSentryGeofenceStatus.text = "当前 Wi-Fi：$ssid · $state"
            }
        }
    }

    /** 从 PackageManager 读取真实版本名 */
    private fun resolveAppVersion(): String {
        val ctx = context ?: return "未知"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            }.versionName
        }.getOrNull() ?: "未知"
    }

    private fun isDebugBuild(): Boolean {
        val ctx = context ?: return false
        return (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun switchThemeMode(mode: Int) {
        SophonApp.preferences.themeMode = mode
        updateThemeUiState(mode)
        SophonApp.applyThemeMode(mode)
        val modeDesc = when (mode) {
            com.vongzh.sophon.core.config.SophonPreferences.THEME_MODE_LIGHT -> "浅色模式 (日间)"
            com.vongzh.sophon.core.config.SophonPreferences.THEME_MODE_DARK -> "深色模式 (夜间)"
            else -> "跟随系统 (自动)"
        }
        Toast.makeText(context, "外观模式已切换为：$modeDesc", Toast.LENGTH_SHORT).show()
    }

    private fun updateThemeUiState(mode: Int) {
        if (!::tvThemeModeCurrentPill.isInitialized) return
        when (mode) {
            com.vongzh.sophon.core.config.SophonPreferences.THEME_MODE_LIGHT -> {
                tvThemeModeCurrentPill.text = "浅色模式"
            }
            com.vongzh.sophon.core.config.SophonPreferences.THEME_MODE_DARK -> {
                tvThemeModeCurrentPill.text = "深色模式"
            }
            else -> {
                tvThemeModeCurrentPill.text = "跟随系统"
            }
        }
    }
}
