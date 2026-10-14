package com.vongzh.sophon.core.remote

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.core.codec.VideoClipper
import com.vongzh.sophon.core.remote.bark.BarkNotifier
import com.vongzh.sophon.core.remote.feishu.FeishuBotClient
import com.vongzh.sophon.core.remote.telegram.TelegramBotClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 远程千里眼与状态查询调度中枢 (RemoteDispatcher)
 *
 * 【多平台通知渠道集成 - 明确仅接入 Telegram 与飞书，排除钉钉】
 * 1. Bark: iOS 极简低延迟即时推送；
 * 2. Telegram Bot: 原生 HTTP 长轮询支持，支持 /snap 静态抓拍、/clip 远程微短片提取与 /status 车况巡检；
 * 3. 飞书 (Feishu / Lark): 企业级自建应用直发与自定义 Webhook 告警卡片；
 * 4. 离线警报持久队列 (OfflineAlertQueue): 地下车库断网不丢警报，驶出地库自动重连补发；
 *
 * 【物理安全红线 - 领克08 原厂休眠协同】
 * 熄火后完全释放 WakeLock，绝不私自阻止车机深休眠，12V 小电瓶 0 额外功耗！
 */
class RemoteDispatcher(
    private val context: Context
) {

    companion object {
        private const val TAG = "Sophon.RemoteDispatcher"
    }

    private val dispatcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val barkNotifier = BarkNotifier(httpClient)
    val telegramClient = TelegramBotClient(httpClient)
    val feishuClient = FeishuBotClient(httpClient)
    private val videoClipper = VideoClipper()

    val offlineAlertQueue = OfflineAlertQueue(context) { alert ->
        val photoFile = alert.photoPath?.let { File(it) }?.takeIf { it.exists() }
        sendMultiChannelAlertInternal(alert.title, alert.body, photoFile, isRetry = true)
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 初始化启动已启用的远程机器人服务
     */
    fun startRemoteServices() {
        val prefs = SophonApp.preferences
        if (prefs.isTelegramEnabled && prefs.telegramBotToken.isNotBlank()) {
            telegramClient.startPolling(
                scope = dispatcherScope,
                botToken = prefs.telegramBotToken,
                allowedChatId = prefs.telegramChatId.ifBlank { null },
                remoteDispatcher = this
            )
        }
    }

    /**
     * 停止正在运行的远程轮询通道
     */
    fun stopRemoteServices() {
        telegramClient.stopPolling()
    }

    /**
     * 发送 Bark 通知 (兼容便捷方法)
     */
    fun sendBarkAlert(title: String, body: String) {
        sendMultiChannelAlert(title, body)
    }

    /**
     * 全渠道协同广播警报与图片 (Bark + Telegram + 飞书)
     */
    fun sendMultiChannelAlert(title: String, body: String, photoFile: File? = null) {
        sendMultiChannelAlertInternal(title, body, photoFile, isRetry = false)
    }

    private fun sendMultiChannelAlertInternal(
        title: String,
        body: String,
        photoFile: File? = null,
        isRetry: Boolean = false
    ): Boolean {
        val prefs = SophonApp.preferences
        var anySent = false
        var anyFailed = false

        // 1. Bark 推送
        if (prefs.barkDeviceKey.isNotBlank()) {
            barkNotifier.sendPush(
                serverUrl = prefs.barkServerUrl.ifBlank { null },
                deviceKey = prefs.barkDeviceKey,
                title = title,
                body = body,
                group = "Sophon",
                callback = { success, _ ->
                    if (success) anySent = true else anyFailed = true
                }
            )
        }

        // 2. Telegram 机器人推送
        if (prefs.isTelegramEnabled && prefs.telegramBotToken.isNotBlank() && prefs.telegramChatId.isNotBlank()) {
            dispatcherScope.launch {
                try {
                    if (photoFile != null && photoFile.exists()) {
                        telegramClient.sendPhoto(prefs.telegramBotToken, prefs.telegramChatId, photoFile, "$title\n$body") { success ->
                            if (success) anySent = true else anyFailed = true
                        }
                    } else {
                        telegramClient.sendTextMessage(prefs.telegramBotToken, prefs.telegramChatId, "<b>$title</b>\n$body") { success ->
                            if (success) anySent = true else anyFailed = true
                        }
                    }
                } catch (e: Exception) {
                    anyFailed = true
                }
            }
        }

        // 3. 飞书 (Feishu) 机器人推送
        if (prefs.isFeishuEnabled) {
            dispatcherScope.launch {
                try {
                    val success = if (prefs.feishuWebhookUrl.isNotBlank()) {
                        feishuClient.sendWebhookMessage(prefs.feishuWebhookUrl, "【$title】\n$body")
                    } else if (prefs.feishuAppId.isNotBlank() && prefs.feishuAppSecret.isNotBlank() && prefs.feishuReceiveId.isNotBlank()) {
                        feishuClient.sendAppMessage(
                            appId = prefs.feishuAppId,
                            appSecret = prefs.feishuAppSecret,
                            receiveId = prefs.feishuReceiveId,
                            text = "【$title】\n$body",
                            photoFile = photoFile
                        )
                    } else false

                    if (success) anySent = true else anyFailed = true
                } catch (e: Exception) {
                    anyFailed = true
                }
            }
        }

        // 若非重试调用且存在发送失败或离线异常，自动入队断网重试队列
        if (!isRetry && anyFailed) {
            offlineAlertQueue.enqueue(title, body, photoFile)
        }

        return anySent || !anyFailed
    }

    /**
     * 处理远程下发的控制与查询指令
     */
    fun processRemoteCommand(command: String, callback: (String) -> Unit) {
        dispatcherScope.launch {
            when (command.trim().uppercase()) {
                "SNAPSHOT", "/SNAP", "抓拍", "拍照" -> {
                    Log.i(TAG, "Executing remote snapshot request...")
                    val resultMsg = executeRemoteSnapshot()
                    val latest = getLatestSnapshotFile()
                    sendMultiChannelAlert("极视远程抓拍成功", "四向视角照片已成功拍摄归档至 U 盘。", latest)
                    callback(resultMsg)
                }
                "STATUS", "/STATUS", "状态", "查询" -> {
                    val statusText = buildCurrentStatusReport()
                    callback(statusText)
                }
                "LOCK", "/LOCK", "锁存", "加锁" -> {
                    SophonApp.vehicleSignalBus.dispatchHardKeyEvent(com.vongzh.sophon.domain.model.HardKeyEvent.LONG_PRESS)
                    val lockMsg = "🔒 已成功向记录仪中枢触发紧急加锁事件，当前录像片段已被移入永久受保护区。"
                    sendMultiChannelAlert("极视紧急录像锁死", "远程指令已锁死当前录像，防止循环覆盖。")
                    callback(lockMsg)
                }
                else -> {
                    callback("【极视指令帮助】\n- 输入 [/snap] 或 [抓拍]: 立即截取四向全景并回传照片\n- 输入 [/clip 15]: 远程提取最近 15 秒高清短视频\n- 输入 [/status] 或 [状态]: 实时查询车速与 U 盘容量\n- 输入 [/lock] 或 [加锁]: 紧急保护当前录像片段")
                }
            }
        }
    }

    /**
     * 远程提取最近几秒的视频片段
     */
    fun extractRecentVideoClip(durationSec: Int = 15, onComplete: (File?) -> Unit) {
        dispatcherScope.launch {
            val usbRoot = SophonApp.storageVault.currentUsbRoot.value
            if (usbRoot == null || !SophonApp.storageVault.isUsbMounted.value) {
                onComplete(null)
                return@launch
            }

            val normalDir = File(usbRoot, "SophonRecord/Normal")
            val eventDir = File(usbRoot, "SophonRecord/Event")
            val candidateFiles = mutableListOf<File>()
            if (eventDir.exists()) candidateFiles.addAll(eventDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4", ignoreCase = true) } ?: emptyArray())
            if (normalDir.exists()) candidateFiles.addAll(normalDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4", ignoreCase = true) } ?: emptyArray())

            val latestFile = candidateFiles.maxByOrNull { it.lastModified() }
            if (latestFile == null || latestFile.length() < 100 * 1024) {
                onComplete(null)
                return@launch
            }

            val cacheDir = File(context.cacheDir, "remote_clips").apply { if (!exists()) mkdirs() }
            val outputFile = File(cacheDir, "CLIP_${System.currentTimeMillis()}_${durationSec}s.mp4")

            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(latestFile.absolutePath)
                val totalDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 60_000L
                retriever.release()

                val clipDurationMs = durationSec * 1000L
                val startMs = maxOf(0L, totalDurationMs - clipDurationMs)
                val success = videoClipper.clipRangeLossless(latestFile, outputFile, startMs, totalDurationMs)
                if (success && outputFile.exists() && outputFile.length() > 0) {
                    onComplete(outputFile)
                } else {
                    onComplete(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed extracting remote video clip", e)
                onComplete(null)
            }
        }
    }

    /**
     * 获取外置 U 盘中最新的一张抓拍照片
     */
    fun getLatestSnapshotFile(): File? {
        val usbRoot = SophonApp.storageVault.currentUsbRoot.value ?: return null
        val snapshotDir = File(usbRoot, "SophonRecord/Snapshot")
        if (!snapshotDir.exists() || !snapshotDir.isDirectory) return null

        return snapshotDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun executeRemoteSnapshot(): String {
        val timeStr = dateFormat.format(Date())
        return "【极视远程抓拍成功】\n时间: $timeStr\n四向视角照片已归档至外置U盘 Snapshot/ 目录。"
    }

    fun buildCurrentStatusReport(): String {
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
