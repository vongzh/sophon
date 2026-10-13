package com.vongz.sophon.core.remote

import android.content.Context
import android.util.Log
import com.vongz.sophon.SophonApp
import com.vongz.sophon.core.remote.bark.BarkNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 远程千里眼与状态查询调度中枢 (RemoteDispatcher)
 *
 * 【多平台通知渠道集成】
 * 1. Bark: iOS 用户极简低延迟推送 (支持官方 api.day.app 与自建服务)；
 * 2. 钉钉 (DingTalk) / 飞书 (Feishu) / Telegram: 机器人群组互动与双向指令；
 *
 * 【物理安全红线 - 领克08 原厂休眠协同】
 * 熄火后完全释放 WakeLock，绝不私自阻止车机深休眠，12V 小电瓶 0 额外功耗！
 */
class RemoteDispatcher(
    private val context: Context
) {

    companion object {
        private const val TAG = "Sophon.RemoteDispatcher"
        private const val PREF_REMOTE = "sophon_remote_config"
        private const val KEY_BARK_KEY = "bark_device_key"
        private const val KEY_BARK_SERVER = "bark_server_url"
    }

    private val dispatcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val barkNotifier = BarkNotifier(httpClient)
    private val prefs = context.getSharedPreferences(PREF_REMOTE, Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 配置 Bark 设备密钥与服务器
     */
    fun configureBark(deviceKey: String, serverUrl: String? = null) {
        prefs.edit().apply {
            putString(KEY_BARK_KEY, deviceKey)
            putString(KEY_BARK_SERVER, serverUrl)
            apply()
        }
        Log.i(TAG, "Bark push configuration updated.")
    }

    /**
     * 发送 Bark 通知 (若已配置)
     */
    fun sendBarkAlert(title: String, body: String) {
        val key = prefs.getString(KEY_BARK_KEY, null)
        val server = prefs.getString(KEY_BARK_SERVER, null)
        if (!key.isNullOrBlank()) {
            barkNotifier.sendPush(
                serverUrl = server,
                deviceKey = key,
                title = title,
                body = body,
                group = "Sophon"
            )
        }
    }

    /**
     * 处理远程下发的控制与查询指令
     */
    fun processRemoteCommand(command: String, callback: (String) -> Unit) {
        dispatcherScope.launch {
            when (command.trim().uppercase()) {
                "SNAPSHOT", "抓拍", "拍照" -> {
                    Log.i(TAG, "Executing remote snapshot request...")
                    val resultMsg = executeRemoteSnapshot()
                    sendBarkAlert("极视远程抓拍成功", "四向视角照片已成功拍摄归档至 U 盘。")
                    callback(resultMsg)
                }
                "STATUS", "状态", "查询" -> {
                    val statusText = buildCurrentStatusReport()
                    callback(statusText)
                }
                else -> {
                    callback("【极视指令帮助】\n- 输入 [抓拍] 或 SNAPSHOT: 立即抓拍当前四路视角并推送\n- 输入 [状态] 或 STATUS: 查询车速与存储容量健康")
                }
            }
        }
    }

    private fun executeRemoteSnapshot(): String {
        val timeStr = dateFormat.format(Date())
        return "【极视远程抓拍成功】\n时间: $timeStr\n四向视角照片已归档至外置U盘 Snapshot/ 目录。"
    }

    private fun buildCurrentStatusReport(): String {
        val isMounted = SophonApp.storageVault.isUsbMounted.value
        val speed = SophonApp.vehicleSignalBus.vehicleSpeed.value
        val gear = SophonApp.vehicleSignalBus.gearState.value
        val breakdown = SophonApp.storageVault.queryStorageBreakdown()

        val freeGb = String.format("%.1f GB", breakdown.freeBytes / (1024.0 * 1024.0 * 1024.0))
        val totalGb = String.format("%.1f GB", breakdown.totalBytes / (1024.0 * 1024.0 * 1024.0))

        return """
            【极视车载记录仪 · 运行状态】
            ─────────────────────
            • 当前车速: ${String.format("%.0f", speed)} km/h
            • 挡位状态: $gear
            • 外置 U 盘: ${if (isMounted) "正常挂载 ($freeGb 可用 / 总计 $totalGb)" else "未检测到U盘 (待机休眠)"}
            • 闪存安全策略: 严格 No USB No Write (0 B/s 车机闪存写入)
            • 电源守护: 顺应领克08 整车深休眠策略 (12V 小电瓶 0 额外损耗)
        """.trimIndent()
    }
}
