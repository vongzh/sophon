package com.vongzh.sophon.core.remote.bark

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Bark iOS 极速轻量化推送服务客户端 (BarkNotifier)
 *
 * 【隐私与极速推送】
 * 支持官方服务器 (https://api.day.app) 及自建 Bark 服务器：
 * 1. 抓拍完成即时推送到 iPhone 手机；
 * 2. 支持自定义通知分组 (group=Sophon)、提示音、角标与跳转链接；
 * 3. 异步并发网络请求，零阻塞车机主线程。
 */
class BarkNotifier(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "Sophon.BarkNotifier"
        private const val DEFAULT_SERVER = "https://api.day.app"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * 发送 Bark 推送通知
     *
     * @param serverUrl 服务器根地址 (若为空则采用默认官方服务器 https://api.day.app)
     * @param deviceKey 用户的 Bark 设备 Key
     * @param title 通知标题
     * @param body 通知正文
     * @param group 分组标签 (默认 "Sophon")
     * @param copyContent 可选点击复制内容
     * @param callback 发送结果回调
     */
    fun sendPush(
        serverUrl: String? = null,
        deviceKey: String,
        title: String,
        body: String,
        group: String = "Sophon",
        copyContent: String? = null,
        callback: ((Boolean, String?) -> Unit)? = null
    ) {
        if (deviceKey.isBlank()) {
            Log.w(TAG, "Bark deviceKey is empty, skipping push.")
            callback?.invoke(false, "DeviceKey is empty")
            return
        }

        val base = if (serverUrl.isNullOrBlank()) DEFAULT_SERVER else serverUrl.trimEnd('/')
        val endpoint = "$base/$deviceKey"

        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("title", title)
                    put("body", body)
                    put("group", group)
                    copyContent?.let { put("copy", it) }
                }

                val reqBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(endpoint)
                    .post(reqBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    val isSuccess = response.isSuccessful
                    val respBody = response.body?.string()
                    Log.i(TAG, "Bark push result: code=${response.code}, success=$isSuccess")
                    callback?.invoke(isSuccess, respBody)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send Bark notification", e)
                callback?.invoke(false, e.message)
            }
        }
    }

    /**
     * 发送系统预警通知 (自动读取全局配置中的 Key 与 Server)
     */
    fun sendAlert(title: String, body: String) {
        val prefs = com.vongzh.sophon.SophonApp.preferences
        val key = prefs.barkDeviceKey
        if (key.isNotBlank()) {
            sendPush(
                serverUrl = prefs.barkServerUrl,
                deviceKey = key,
                title = title,
                body = body
            )
        }
    }
}
