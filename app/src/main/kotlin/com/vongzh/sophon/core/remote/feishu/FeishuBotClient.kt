package com.vongzh.sophon.core.remote.feishu

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 极视 飞书 (Feishu / Lark) 车载机器人客户端 (FeishuBotClient)
 *
 * 【多模式融合】
 * 1. 自定义群机器人 Webhook：零配置即用，支持文本消息与富文本告警卡片群发；
 * 2. 飞书开放平台企业自建应用：通过 TenantAccessToken，支持全向合成高清实况截图上传与私信直发；
 * 3. 严格遵循 Android 架构解耦规范，独立封装于 feishu 包中。
 */
class FeishuBotClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "Sophon.FeishuBot"
        private const val BASE_URL = "https://open.feishu.cn/open-apis"
    }

    private val gson = Gson()
    private var cachedTenantToken: String? = null
    private var tokenExpireTimeMs: Long = 0

    /**
     * 通过飞书自定义群机器人 Webhook 发送纯文本消息
     */
    suspend fun sendWebhookMessage(
        webhookUrl: String,
        text: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (webhookUrl.isBlank()) return@withContext false
        try {
            val contentObj = JsonObject().apply {
                addProperty("text", text)
            }
            val payload = JsonObject().apply {
                addProperty("msg_type", "text")
                add("content", contentObj)
            }
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(webhookUrl).post(body).build()

            httpClient.newCall(request).execute().use { resp ->
                val success = resp.isSuccessful
                if (!success) {
                    Log.w(TAG, "Feishu webhook send failed: HTTP ${resp.code}")
                }
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Feishu webhook send exception", e)
            false
        }
    }

    /**
     * 获取飞书自建应用的 TenantAccessToken
     */
    private suspend fun getTenantAccessToken(appId: String, appSecret: String): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedTenantToken != null && now < tokenExpireTimeMs - 60_000) {
            return@withContext cachedTenantToken
        }

        try {
            val url = "$BASE_URL/auth/v3/tenant_access_token/internal"
            val payload = JsonObject().apply {
                addProperty("app_id", appId)
                addProperty("app_secret", appSecret)
            }
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(body).build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val respStr = resp.body?.string() ?: return@withContext null
                val json = gson.fromJson(respStr, JsonObject::class.java)
                if (json.has("tenant_access_token")) {
                    val token = json.get("tenant_access_token").asString
                    val expireSec = json.get("expire").asLong
                    cachedTenantToken = token
                    tokenExpireTimeMs = now + (expireSec * 1000)
                    Log.i(TAG, "Feishu tenant access token obtained successfully.")
                    return@withContext token
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Feishu tenant access token", e)
        }
        null
    }

    /**
     * 通过自建应用 API 向指定用户或群组发送消息与实况截图
     */
    suspend fun sendAppMessage(
        appId: String,
        appSecret: String,
        receiveId: String,
        receiveIdType: String = "open_id",
        text: String,
        photoFile: File? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val token = getTenantAccessToken(appId, appSecret)
        if (token == null) {
            Log.e(TAG, "Cannot send Feishu app message without valid token.")
            return@withContext false
        }

        try {
            // 1. 若附带照片，先上传至飞书媒体素材库
            var imageKey: String? = null
            if (photoFile != null && photoFile.exists()) {
                imageKey = uploadImage(token, photoFile)
            }

            // 2. 发送消息
            val url = "$BASE_URL/im/v1/messages?receive_id_type=$receiveIdType"

            if (imageKey != null) {
                // 发送图片消息
                val contentObj = JsonObject().apply {
                    addProperty("image_key", imageKey)
                }
                val payload = JsonObject().apply {
                    addProperty("receive_id", receiveId)
                    addProperty("msg_type", "image")
                    addProperty("content", contentObj.toString())
                }
                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().close()
            }

            // 发送文本消息
            val textContent = JsonObject().apply {
                addProperty("text", text)
            }
            val textPayload = JsonObject().apply {
                addProperty("receive_id", receiveId)
                addProperty("msg_type", "text")
                addProperty("content", textContent.toString())
            }
            val textBody = textPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val textRequest = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(textBody)
                .build()

            httpClient.newCall(textRequest).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Feishu app message", e)
            false
        }
    }

    private fun uploadImage(token: String, file: File): String? {
        try {
            val url = "$BASE_URL/im/v1/images"
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image_type", "message")
                .addFormDataPart("image", file.name, file.asRequestBody("image/jpeg".toMediaType()))
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(multipart)
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                val json = gson.fromJson(bodyStr, JsonObject::class.java)
                if (json.has("data") && json.getAsJsonObject("data").has("image_key")) {
                    return json.getAsJsonObject("data").get("image_key").asString
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload image to Feishu", e)
        }
        return null
    }
}
