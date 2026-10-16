package com.vongzh.sophon.core.geofence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.vongzh.sophon.core.config.SophonPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 哨兵常用地点安全围栏 (SentryHomeGeofence) —— 基于 Wi-Fi SSID 白名单判据
 *
 * 【设计定位与隐私合规】
 * 在家、公司地库等安全驻车场景下，车辆熄火锁车后可自动撤防或降低哨兵灵敏度，节约 12V 小电瓶电量。
 * 围栏判据采用 Wi-Fi SSID 白名单匹配策略：
 * 1. 严格保护用户隐私，避免常驻申请高敏感的系统级后台精确定位权限；
 * 2. 覆盖用户常驻的固定 Wi-Fi 区域（家庭私家车位、公司园区等）；
 * 3. 锁车休眠检测期间定期探活已知网络 SSID，命中白名单则判定处于安全围栏区域。
 *
 * 【权限策略】
 * - Android 13+：使用 NEARBY_WIFI_DEVICES 且声明 neverForLocation，读取 SSID 无需位置权限。
 * - Android 12L 及以下：SSID 读取受系统权限保护，仅在用户主动配置了围栏白名单时才按需申请
 *   ACCESS_FINE_LOCATION，拒绝时本功能静默失效，不影响核心录制主流程。
 */
class SentryHomeGeofence(
    private val context: Context,
    private val preferences: SophonPreferences,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "Sophon.HomeGeofence"
        private const val POLL_INTERVAL_MS = 20_000L

        /** WifiManager 在无权限 / 无连接时返回的占位串 */
        private const val UNKNOWN_SSID = "<unknown ssid>"

        /**
         * Android 13 起读取 SSID 所需的权限；以下版本退化为位置权限。
         */
        @JvmStatic
        fun requiredPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
    }

    private val _isAtHome = MutableStateFlow(false)
    val isAtHome: StateFlow<Boolean> = _isAtHome.asStateFlow()

    private val _currentSsid = MutableStateFlow<String?>(null)
    val currentSsid: StateFlow<String?> = _currentSsid.asStateFlow()

    private var pollJob: Job? = null

    /** 用户是否已配置围栏白名单 */
    val hasWhitelist: Boolean
        get() = preferences.sentryHomeSsids.isNotEmpty()

    /** 当前是否具备读取 SSID 的权限 */
    val hasSsidReadPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, requiredPermission()) ==
                PackageManager.PERMISSION_GRANTED

    fun start() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * 立即重算一次围栏状态。白名单为空时恒为 false（功能关闭）。
     */
    fun refresh() {
        val whitelist = preferences.sentryHomeSsids
        if (whitelist.isEmpty()) {
            _currentSsid.value = readCurrentSsid()
            _isAtHome.value = false
            return
        }

        val ssid = readCurrentSsid()
        _currentSsid.value = ssid
        val atHome = ssid != null && whitelist.any { it.equals(ssid, ignoreCase = true) }
        if (atHome != _isAtHome.value) {
            Log.i(TAG, "Home geofence changed -> isAtHome=$atHome (ssid=$ssid, whitelist=${whitelist.size})")
        }
        _isAtHome.value = atHome
    }

    /**
     * 读取当前已连接 Wi-Fi 的 SSID；无权限 / 未连接 / 被系统隐藏时返回 null。
     */
    fun readCurrentSsid(): String? {
        if (!hasSsidReadPermission) return null
        return try {
            val appCtx = context.applicationContext
            @Suppress("DEPRECATION")
            val wifiManager = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val raw = wifiManager?.connectionInfo?.ssid
            normalizeSsid(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read current WiFi SSID: ${e.message}")
            null
        }
    }

    /**
     * 归一化 SSID：去掉 WifiManager 返回的外层双引号，过滤系统占位串。
     */
    private fun normalizeSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().trim('"')
        if (trimmed.isBlank() || trimmed == UNKNOWN_SSID) return null
        return trimmed
    }
}
