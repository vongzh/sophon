package com.vongzh.sophon.core.config

import android.content.Context
import android.content.SharedPreferences

/**
 * 极视全局用户偏好与车机参数配置管理 (SophonPreferences)
 *
 * 【座舱个性化与极客配置】
 * 统一管理 Bark 推送密钥、切片时长、窄道双闪会车速度阈值、鱼眼消除与碰撞锁存灵敏度。
 */
class SophonPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "sophon_cockpit_prefs"

        const val THEME_MODE_SYSTEM = 0
        const val THEME_MODE_LIGHT = 1
        const val THEME_MODE_DARK = 2

        /** 对外暴露：BlindSpotOverlayService 需监听该键以实时响应开关变化 */
        const val KEY_BLIND_SPOT = "blind_spot_overlay_enabled"

        private const val KEY_BARK_ENABLED = "bark_push_enabled"
        private const val KEY_BARK_DEVICE_KEY = "bark_device_key"
        private const val KEY_BARK_SERVER_URL = "bark_server_url"
        private const val KEY_CHUNK_DURATION_SEC = "chunk_duration_sec"
        private const val KEY_HAZARD_SPEED_THRESHOLD = "hazard_speed_threshold"
        private const val KEY_AUTO_CRASH_LOCK = "auto_crash_lock"
        private const val KEY_CRASH_SENSITIVITY = "crash_sensitivity" // 0: 3.5G, 1: 2.5G, 2: 1.8G
        private const val KEY_OSD_WATERMARK = "osd_watermark"
        private const val KEY_RAINY_NIGHT_SHADER = "rainy_night_shader"
        private const val KEY_DOOR_OPEN_WARNING = "door_open_warning"
        private const val KEY_BATTERY_PROTECTION_ENABLED = "battery_protection_enabled"
        private const val KEY_BATTERY_PROTECTION_VOLTS = "battery_protection_volts"
        private const val KEY_SENTRY_MODE_ENABLED = "sentry_mode_enabled"
        private const val KEY_SENTRY_DETERRENT_ENABLED = "sentry_deterrent_enabled"
        private const val KEY_PIP_AUTO_ENABLED = "pip_auto_enabled"
        private const val KEY_AUDIO_RECORD_ENABLED = "audio_record_enabled"
        private const val KEY_STORAGE_VIDEO_QUOTA_GB = "storage_video_quota_gb"
        private const val KEY_STORAGE_CLEAN_RATIO_PERCENT = "storage_clean_ratio_percent"
        private const val KEY_STORAGE_PHOTO_QUOTA_GB = "storage_photo_quota_gb"
        private const val KEY_STORAGE_CLEANUP_TRIGGER = "storage_cleanup_trigger"
        private const val KEY_LAN_SHARE_PORT = "lan_share_port"
        private const val KEY_PRIVACY_AGREED_VERSION = "privacy_agreed_version"
        private const val KEY_STOP_RECORD_ON_PARK = "stop_record_on_park"
        private const val KEY_STOP_RECORD_ON_DRIVER_DOOR = "stop_record_on_driver_door"
        private const val KEY_SENTRY_HOME_SSIDS = "sentry_home_ssids"
        private const val KEY_REAR_VIEW_OVERLAY = "overlay_rear_view"
        private const val KEY_STATUS_OVERLAY = "overlay_vehicle_status"
        private const val KEY_ASSIST_BALL = "overlay_assist_ball"
        private const val KEY_IMAGE_EV_STEPS = "image_ev_steps"
        private const val KEY_IMAGE_NOISE_MODE = "image_noise_mode"
        private const val KEY_SHOW_FAB_LABELS = "show_fab_labels"
        private const val KEY_THEME_MODE = "ui_theme_mode" // 0: SYSTEM(跟随系统), 1: LIGHT(浅色日间), 2: DARK(深色夜间)

        // Telegram Bot 配置
        private const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
        private const val KEY_TELEGRAM_CHAT_ID = "telegram_chat_id"
        private const val KEY_TELEGRAM_ENABLED = "telegram_enabled"

        // 飞书 (Feishu) 机器人配置
        private const val KEY_FEISHU_WEBHOOK = "feishu_webhook"
        private const val KEY_FEISHU_APP_ID = "feishu_app_id"
        private const val KEY_FEISHU_APP_SECRET = "feishu_app_secret"
        private const val KEY_FEISHU_RECEIVE_ID = "feishu_receive_id"
        private const val KEY_FEISHU_ENABLED = "feishu_enabled"

        private const val DEFAULT_BARK_SERVER = "https://api.day.app"
        private const val DEFAULT_CHUNK_DURATION = 60 // 默认 1 分钟切片
        private const val DEFAULT_HAZARD_SPEED = 15f // 默认 15 km/h 会车阈值
        private const val DEFAULT_BATTERY_VOLTS = 11.8f // 默认 11.8V 蓄电池防亏电门限
        private const val DEFAULT_LAN_SHARE_PORT = 8088 // 默认免流快传端口
    }

    private val sp: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var barkDeviceKey: String
        get() = sp.getString(KEY_BARK_DEVICE_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_BARK_DEVICE_KEY, value.trim()).apply()

    var barkServerUrl: String
        get() = sp.getString(KEY_BARK_SERVER_URL, DEFAULT_BARK_SERVER) ?: DEFAULT_BARK_SERVER
        set(value) = sp.edit().putString(KEY_BARK_SERVER_URL, value.trim()).apply()

    var chunkDurationSec: Int
        get() = sp.getInt(KEY_CHUNK_DURATION_SEC, DEFAULT_CHUNK_DURATION)
        set(value) = sp.edit().putInt(KEY_CHUNK_DURATION_SEC, value).apply()

    var hazardSpeedThresholdKmh: Float
        get() = sp.getFloat(KEY_HAZARD_SPEED_THRESHOLD, DEFAULT_HAZARD_SPEED)
        set(value) = sp.edit().putFloat(KEY_HAZARD_SPEED_THRESHOLD, value).apply()


    var isAutoCrashLockEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_CRASH_LOCK, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_CRASH_LOCK, value).apply()

    var isOsdWatermarkEnabled: Boolean
        get() = sp.getBoolean(KEY_OSD_WATERMARK, true)
        set(value) = sp.edit().putBoolean(KEY_OSD_WATERMARK, value).apply()

    var isRainyNightModeEnabled: Boolean
        get() = sp.getBoolean(KEY_RAINY_NIGHT_SHADER, false)
        set(value) = sp.edit().putBoolean(KEY_RAINY_NIGHT_SHADER, value).apply()

    var isDoorOpenWarningEnabled: Boolean
        get() = sp.getBoolean(KEY_DOOR_OPEN_WARNING, true)
        set(value) = sp.edit().putBoolean(KEY_DOOR_OPEN_WARNING, value).apply()

    var isDowEnabled: Boolean
        get() = isDoorOpenWarningEnabled
        set(value) { isDoorOpenWarningEnabled = value }

    var isBlindSpotEnabled: Boolean
        get() = sp.getBoolean(KEY_BLIND_SPOT, true)
        set(value) = sp.edit().putBoolean(KEY_BLIND_SPOT, value).apply()

    /**
     * Bark 通道总开关。
     *
     * 默认值取「是否已配置 deviceKey」而非恒 false：本开关此前未参与推送判定（只判 key 非空），
     * 老用户配了 key 却从未碰过开关。若默认 false，修复判定后会让这批人静默失去推送。
     * 一旦用户显式开/关过，就以存储值为准。
     */
    var isBarkEnabled: Boolean
        get() = sp.getBoolean(KEY_BARK_ENABLED, barkDeviceKey.isNotBlank())
        set(value) = sp.edit().putBoolean(KEY_BARK_ENABLED, value).apply()

    var isBatteryProtectionEnabled: Boolean
        get() = sp.getBoolean(KEY_BATTERY_PROTECTION_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_BATTERY_PROTECTION_ENABLED, value).apply()

    var batteryProtectionThresholdVolts: Float
        get() = sp.getFloat(KEY_BATTERY_PROTECTION_VOLTS, DEFAULT_BATTERY_VOLTS)
        set(value) = sp.edit().putFloat(KEY_BATTERY_PROTECTION_VOLTS, value).apply()

    var isSentryModeEnabled: Boolean
        get() = sp.getBoolean(KEY_SENTRY_MODE_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_SENTRY_MODE_ENABLED, value).apply()

    var isSentryDeterrentEnabled: Boolean
        get() = sp.getBoolean(KEY_SENTRY_DETERRENT_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_SENTRY_DETERRENT_ENABLED, value).apply()

    var isPipAutoEnabled: Boolean
        get() = sp.getBoolean(KEY_PIP_AUTO_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_PIP_AUTO_ENABLED, value).apply()

    var isAudioRecordEnabled: Boolean
        get() = sp.getBoolean(KEY_AUDIO_RECORD_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_AUDIO_RECORD_ENABLED, value).apply()

    var isShowFabLabels: Boolean
        get() = sp.getBoolean(KEY_SHOW_FAB_LABELS, false)
        set(value) = sp.edit().putBoolean(KEY_SHOW_FAB_LABELS, value).apply()

    var crashSensitivity: Int
        get() = sp.getInt(KEY_CRASH_SENSITIVITY, 1) // 0: 3.5G, 1: 2.5G, 2: 1.8G
        set(value) = sp.edit().putInt(KEY_CRASH_SENSITIVITY, value).apply()



    var storageVideoQuotaGb: Int
        get() = sp.getInt(KEY_STORAGE_VIDEO_QUOTA_GB, 10)
        set(value) = sp.edit().putInt(KEY_STORAGE_VIDEO_QUOTA_GB, value).apply()

    var storageCleanupRatioPercent: Int
        get() = sp.getInt(KEY_STORAGE_CLEAN_RATIO_PERCENT, 30)
        set(value) = sp.edit().putInt(KEY_STORAGE_CLEAN_RATIO_PERCENT, value).apply()

    var storagePhotoQuotaGb: Int
        get() = sp.getInt(KEY_STORAGE_PHOTO_QUOTA_GB, 10)
        set(value) = sp.edit().putInt(KEY_STORAGE_PHOTO_QUOTA_GB, value).apply()

    var storageCleanupTrigger: Int
        get() = sp.getInt(KEY_STORAGE_CLEANUP_TRIGGER, 0) // 0: 录制结束, 1: 空间超90%, 2: 启动时
        set(value) = sp.edit().putInt(KEY_STORAGE_CLEANUP_TRIGGER, value).apply()

    var telegramBotToken: String
        get() = sp.getString(KEY_TELEGRAM_BOT_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_TELEGRAM_BOT_TOKEN, value.trim()).apply()

    var telegramChatId: String
        get() = sp.getString(KEY_TELEGRAM_CHAT_ID, "") ?: ""
        set(value) = sp.edit().putString(KEY_TELEGRAM_CHAT_ID, value.trim()).apply()

    var isTelegramEnabled: Boolean
        get() = sp.getBoolean(KEY_TELEGRAM_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_TELEGRAM_ENABLED, value).apply()

    var feishuWebhookUrl: String
        get() = sp.getString(KEY_FEISHU_WEBHOOK, "") ?: ""
        set(value) = sp.edit().putString(KEY_FEISHU_WEBHOOK, value.trim()).apply()

    var feishuAppId: String
        get() = sp.getString(KEY_FEISHU_APP_ID, "") ?: ""
        set(value) = sp.edit().putString(KEY_FEISHU_APP_ID, value.trim()).apply()

    var feishuAppSecret: String
        get() = sp.getString(KEY_FEISHU_APP_SECRET, "") ?: ""
        set(value) = sp.edit().putString(KEY_FEISHU_APP_SECRET, value.trim()).apply()

    var feishuReceiveId: String
        get() = sp.getString(KEY_FEISHU_RECEIVE_ID, "") ?: ""
        set(value) = sp.edit().putString(KEY_FEISHU_RECEIVE_ID, value.trim()).apply()

    var isFeishuEnabled: Boolean
        get() = sp.getBoolean(KEY_FEISHU_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_FEISHU_ENABLED, value).apply()

    /**
     * 局域网免流快传服务端口 (默认 8088，可配置以避免与车机其它服务冲突)
     * 取值范围限定 1024 至 65535，非法值回落默认端口。
     */
    var lanSharePort: Int
        get() = sp.getInt(KEY_LAN_SHARE_PORT, DEFAULT_LAN_SHARE_PORT)
        set(value) {
            val safe = if (value in 1024..65535) value else DEFAULT_LAN_SHARE_PORT
            sp.edit().putInt(KEY_LAN_SHARE_PORT, safe).apply()
        }

    /**
     * 已签署的隐私协议版本号 (0 表示从未签署)
     * 首启合规：未签署前不得采集摄像头画面 / 录音 / 车辆信息。
     */
    var privacyAgreedVersion: Int
        get() = sp.getInt(KEY_PRIVACY_AGREED_VERSION, 0)
        set(value) = sp.edit().putInt(KEY_PRIVACY_AGREED_VERSION, value).apply()

    /**
     * 挂入 P 挡时自动停止行车循环录制 (默认关闭)
     * 车身信号联动：避免驻车后录满大量无效影像。哨兵模式开启时该策略失效。
     */
    var isStopRecordOnParkEnabled: Boolean
        get() = sp.getBoolean(KEY_STOP_RECORD_ON_PARK, false)
        set(value) = sp.edit().putBoolean(KEY_STOP_RECORD_ON_PARK, value).apply()

    /**
     * 主驾车门开启时自动停止行车循环录制 (默认关闭)
     */
    var isStopRecordOnDriverDoorEnabled: Boolean
        get() = sp.getBoolean(KEY_STOP_RECORD_ON_DRIVER_DOOR, false)
        set(value) = sp.edit().putBoolean(KEY_STOP_RECORD_ON_DRIVER_DOOR, value).apply()

    /**
     * 影像参数：曝光补偿步进（以 1/3 EV 为单位，运行时按设备支持范围夹紧）
     */
    var exposureCompensationSteps: Int
        get() = sp.getInt(KEY_IMAGE_EV_STEPS, 0).coerceIn(-6, 6)
        set(value) = sp.edit().putInt(KEY_IMAGE_EV_STEPS, value.coerceIn(-6, 6)).apply()

    /**
     * UI 主题模式：
     * 0: MODE_NIGHT_FOLLOW_SYSTEM (跟随系统)
     * 1: MODE_NIGHT_NO (浅色日间)
     * 2: MODE_NIGHT_YES (深色夜间)
     */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME_MODE, THEME_MODE_SYSTEM)
        set(value) = sp.edit().putInt(KEY_THEME_MODE, value).apply()

    /**
     * 影像参数：降噪档位 0=关闭 / 1=快速 / 2=高质量
     */
    var noiseReductionMode: Int
        get() = sp.getInt(KEY_IMAGE_NOISE_MODE, ImageTuning.DEFAULT_NOISE_MODE).coerceIn(0, 2)
        set(value) = sp.edit().putInt(KEY_IMAGE_NOISE_MODE, value.coerceIn(0, 2)).apply()

    /** 当前影像参数组合 */
    var imageTuning: ImageTuning
        get() = ImageTuning(evSteps = exposureCompensationSteps, noiseReductionMode = noiseReductionMode)
        set(value) {
            exposureCompensationSteps = value.evSteps
            noiseReductionMode = value.noiseReductionMode
        }

    /**
     * 悬浮窗矩阵 · 后视角小窗：挂 R 挡（原厂 360 未占用屏幕）时弹出后视实时画面，替代传统流媒体后视镜
     */
    var isRearViewOverlayEnabled: Boolean
        get() = sp.getBoolean(KEY_REAR_VIEW_OVERLAY, false)
        set(value) = sp.edit().putBoolean(KEY_REAR_VIEW_OVERLAY, value).apply()

    /**
     * 悬浮窗矩阵 · 车辆状态小窗：常驻显示车速 / 挡位 / 12V 电压 / 录制与哨兵状态
     */
    var isStatusOverlayEnabled: Boolean
        get() = sp.getBoolean(KEY_STATUS_OVERLAY, false)
        set(value) = sp.edit().putBoolean(KEY_STATUS_OVERLAY, value).apply()

    /**
     * 悬浮窗矩阵 · 功能悬浮球：可拖拽圆球，单击展开抓拍 / 加锁 / 哨兵 / 主界面快捷菜单
     */
    var isAssistBallEnabled: Boolean
        get() = sp.getBoolean(KEY_ASSIST_BALL, false)
        set(value) = sp.edit().putBoolean(KEY_ASSIST_BALL, value).apply()

    /**
     * 哨兵围栏：连接到的 Wi-Fi SSID 在白名单内时自动撤防 (回家/公司自动撤防)
     *
     * 【判据设计】
     * 采用已知安全 Wi-Fi SSID 作为无位置权限依赖前提下的隐私合规判定方案，
     * 保护用户位置隐私的同时满足常用安全驻车场景下的电量节约需求。
     *
     * 存储为换行分隔的 SSID 列表。
     */
    var sentryHomeSsids: Set<String>
        get() = (sp.getString(KEY_SENTRY_HOME_SSIDS, "") ?: "")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        set(value) = sp.edit().putString(
            KEY_SENTRY_HOME_SSIDS,
            value.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        ).apply()

    // ==================== 配置备份 / 恢复 ====================

    /**
     * 导出当前全部偏好项，用于写入 U 盘备份文件。
     * 直接取 SharedPreferences 全量快照，新增配置项无需在此处同步维护。
     */
    /**
     * 注册偏好变更监听，供运行时服务实时响应开关变化（而不是等到下次事件触发才生效）。
     */
    fun registerOnPreferenceChange(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnPreferenceChange(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun exportAll(): Map<String, Any?> = sp.all.toMap()

    /**
     * 从备份数据整体写回偏好。按值类型分派，未知类型跳过。
     *
     * 注意：不会清空未出现在备份中的键——避免恢复后丢失本机特有状态
     * (如隐私协议版本号)。
     */
    fun importAll(values: Map<String, Any?>) {
        val editor = sp.edit()
        var applied = 0
        for ((key, value) in values) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as? Set<String> ?: continue)
                }
                else -> continue
            }
            applied++
        }
        editor.apply()
        android.util.Log.i("SophonPreferences", "importAll: applied $applied / ${values.size} keys")
    }
}
