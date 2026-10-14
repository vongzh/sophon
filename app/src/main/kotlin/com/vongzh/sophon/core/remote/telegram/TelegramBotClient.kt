package com.vongzh.sophon.core.remote.telegram

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vongzh.sophon.core.remote.RemoteDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 极视 Telegram 机器人远程交互客户端 (TelegramBotClient)
 *
 * 【架构规范 - 业务逻辑与网络协议独立解耦】
 * 1. 采用官方长轮询 (Long-Polling getUpdates) 机制，无需公网固定 IP 与 Webhook 端口映射；
 * 2. 支持远程 `/snap` 抓拍并回传四向高清合成图片、`/status` 车况健康上报；
 * 3. 严格受白名单 Chat ID 鉴权保护，杜绝未授权访问。
 */
class TelegramBotClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "Sophon.TelegramBot"
        private const val BASE_URL = "https://api.telegram.org"
    }

    private val gson = Gson()
    private var pollingJob: Job? = null
    private var lastUpdateId: Long = 0

    /**
     * 启动长轮询接收远程指令
     */
    fun startPolling(
        scope: CoroutineScope,
        botToken: String,
        allowedChatId: String?,
        remoteDispatcher: RemoteDispatcher
    ) {
        stopPolling()
        if (botToken.isBlank()) {
            Log.w(TAG, "Telegram bot token is empty, polling skipped.")
            return
        }

        pollingJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Telegram bot polling loop started.")
            while (isActive) {
                try {
                    val updates = fetchUpdates(botToken, lastUpdateId + 1)
                    if (updates != null && updates.has("result")) {
                        val resultArray = updates.getAsJsonArray("result")
                        for (i in 0 until resultArray.size()) {
                            val update = resultArray.get(i).asJsonObject
                            val updateId = update.get("update_id").asLong
                            lastUpdateId = maxOf(lastUpdateId, updateId)

                            if (update.has("message")) {
                                val message = update.getAsJsonObject("message")
                                val text = message.get("text")?.asString ?: ""
                                val chat = message.getAsJsonObject("chat")
                                val chatId = chat.get("id").asString

                                // 白名单过滤
                                if (!allowedChatId.isNullOrBlank() && chatId != allowedChatId) {
                                    Log.w(TAG, "Ignored Telegram message from unauthorized chat: $chatId")
                                    continue
                                }

                                handleIncomingMessage(botToken, chatId, text, remoteDispatcher)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Telegram polling error: ${e.message}, retrying in 5s...")
                    delay(5000)
                }
            }
            Log.i(TAG, "Telegram bot polling loop stopped.")
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun handleIncomingMessage(
        botToken: String,
        chatId: String,
        text: String,
        remoteDispatcher: RemoteDispatcher
    ) {
        val trimmed = text.trim()
        when {
            trimmed.startsWith("/snap") || trimmed.equals("抓拍", ignoreCase = true) -> {
                sendTextMessage(botToken, chatId, "📷 正在为您截取领克08四向视角画面，请稍候...")
                remoteDispatcher.processRemoteCommand("SNAPSHOT") { report ->
                    // 发送最新抓拍照片
                    val latestPhoto = remoteDispatcher.getLatestSnapshotFile()
                    if (latestPhoto != null && latestPhoto.exists()) {
                        sendPhoto(botToken, chatId, latestPhoto, report)
                    } else {
                        sendTextMessage(botToken, chatId, report)
                    }
                }
            }
            trimmed.startsWith("/status") || trimmed.equals("状态", ignoreCase = true) -> {
                remoteDispatcher.processRemoteCommand("STATUS") { report ->
                    sendTextMessage(botToken, chatId, report)
                }
            }
            trimmed.startsWith("/lock") || trimmed.equals("锁存", ignoreCase = true) -> {
                remoteDispatcher.processRemoteCommand("LOCK") { report ->
                    sendTextMessage(botToken, chatId, report)
                }
            }
            else -> {
                val help = """
                    🛰️ <b>极视 (Sophon) 领克08 车载机器人</b>
                    ─────────────────────
                    /snap - 实时远程抓拍四向视角照片
                    /status - 查询车速、挡位与U盘容量
                    /lock - 紧急加锁当前录像片段
                """.trimIndent()
                sendTextMessage(botToken, chatId, help)
            }
        }
    }

    private fun fetchUpdates(botToken: String, offset: Long): JsonObject? {
        val url = "$BASE_URL/bot$botToken/getUpdates?offset=$offset&timeout=25"
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return gson.fromJson(body, JsonObject::class.java)
        }
    }

    fun sendTextMessage(
        botToken: String,
        chatId: String,
        text: String,
        callback: ((Boolean) -> Unit)? = null
    ) {
        val url = "$BASE_URL/bot$botToken/sendMessage"
        val payload = JsonObject().apply {
            addProperty("chat_id", chatId)
            addProperty("text", text)
            addProperty("parse_mode", "HTML")
        }
        val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(requestBody).build()

        try {
            httpClient.newCall(request).execute().use { resp ->
                callback?.invoke(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Telegram text", e)
            callback?.invoke(false)
        }
    }

    fun sendPhoto(
        botToken: String,
        chatId: String,
        photoFile: File,
        caption: String,
        callback: ((Boolean) -> Unit)? = null
    ) {
        val url = "$BASE_URL/bot$botToken/sendPhoto"
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("caption", caption)
            .addFormDataPart("photo", photoFile.name, photoFile.asRequestBody("image/jpeg".toMediaType()))
            .build()

        val request = Request.Builder().url(url).post(multipart).build()
        try {
            httpClient.newCall(request).execute().use { resp ->
                callback?.invoke(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Telegram photo", e)
            callback?.invoke(false)
        }
    }
}
