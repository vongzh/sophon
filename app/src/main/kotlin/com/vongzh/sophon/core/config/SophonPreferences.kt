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

        private const val KEY_BARK_DEVICE_KEY = "bark_device_key"
        private const val KEY_BARK_SERVER_URL = "bark_server_url"
        private const val KEY_CHUNK_DURATION_SEC = "chunk_duration_sec"
        private const val KEY_HAZARD_SPEED_THRESHOLD = "hazard_speed_threshold"
        private const val KEY_FISHEYE_CORRECTION = "fisheye_correction"
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
        private const val KEY_STORAGE_NORMAL_QUOTA = "storage_normal_quota"
        private const val KEY_STORAGE_EMERGENCY_QUOTA = "storage_emergency_quota"

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

    var isFisheyeCorrectionEnabled: Boolean
        get() = sp.getBoolean(KEY_FISHEYE_CORRECTION, true)
        set(value) = sp.edit().putBoolean(KEY_FISHEYE_CORRECTION, value).apply()

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

    var crashSensitivity: Int
        get() = sp.getInt(KEY_CRASH_SENSITIVITY, 1) // 0: 3.5G, 1: 2.5G, 2: 1.8G
        set(value) = sp.edit().putInt(KEY_CRASH_SENSITIVITY, value).apply()

    var storageNormalQuotaPercent: Int
        get() = sp.getInt(KEY_STORAGE_NORMAL_QUOTA, 70)
        set(value) = sp.edit().putInt(KEY_STORAGE_NORMAL_QUOTA, value).apply()

    var storageEmergencyQuotaPercent: Int
        get() = sp.getInt(KEY_STORAGE_EMERGENCY_QUOTA, 20)
        set(value) = sp.edit().putInt(KEY_STORAGE_EMERGENCY_QUOTA, value).apply()

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
}
