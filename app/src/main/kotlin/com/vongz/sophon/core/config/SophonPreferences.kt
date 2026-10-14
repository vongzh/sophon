package com.vongz.sophon.core.config

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
        private const val KEY_OSD_WATERMARK = "osd_watermark"
        private const val KEY_RAINY_NIGHT_SHADER = "rainy_night_shader"

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
