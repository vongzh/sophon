package com.vongzh.sophon.core.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * 离线警报断网续传队列 (OfflineAlertQueue)
 *
 * 【车规地下车库断网痛点解决】
 * 1. 车辆停放地下 B2/B3 无 4G 信号期间，碰撞、擦碰或哨兵触发的报警绝不丢弃；
 * 2. 自动持久化暂存至私有 JSON 缓存队列；
 * 3. 动态监听系统 ConnectivityManager 网络恢复回调；
 * 4. 车辆驶出地库重获网络时，立即按时间先后顺序批量补发积压警报与抓拍图。
 */
class OfflineAlertQueue(
    private val context: Context,
    private val onRetryAlert: suspend (QueuedAlert) -> Boolean
) {

    companion object {
        private const val TAG = "Sophon.OfflineAlertQueue"
        private const val QUEUE_FILE_NAME = "sophon_pending_alerts.json"
        private const val MAX_QUEUE_SIZE = 50
    }

    data class QueuedAlert(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val body: String,
        val photoPath: String?,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueFile = File(context.filesDir, QUEUE_FILE_NAME)
    private val mutex = Mutex()
    private val queueList = mutableListOf<QueuedAlert>()

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var isNetworkAvailable = false

    init {
        loadPersistedQueue()
        startNetworkMonitoring()
    }

    private fun loadPersistedQueue() {
        if (!queueFile.exists()) return
        try {
            val json = queueFile.readText()
            val type = object : TypeToken<List<QueuedAlert>>() {}.type
            val list: List<QueuedAlert>? = gson.fromJson(json, type)
            if (!list.isNullOrEmpty()) {
                queueList.addAll(list)
                Log.i(TAG, "Loaded ${queueList.size} pending offline alerts from storage.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load offline alert queue", e)
        }
    }

    private suspend fun persistQueueLocked() {
        try {
            val json = gson.toJson(queueList)
            queueFile.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist offline alert queue", e)
        }
    }

    /**
     * 将未发送成功的警报入队持久化
     */
    fun enqueue(title: String, body: String, photoFile: File?) {
        scope.launch {
            mutex.withLock {
                if (queueList.size >= MAX_QUEUE_SIZE) {
                    queueList.removeAt(0) // 淘汰最旧的一条
                }
                val alert = QueuedAlert(
                    title = title,
                    body = body,
                    photoPath = photoFile?.takeIf { it.exists() }?.absolutePath
                )
                queueList.add(alert)
                persistQueueLocked()
                Log.w(TAG, "Offline alert enqueued: '${title}' (Queue size: ${queueList.size})")
            }
        }
    }

    /**
     * 监听系统网络恢复并触发冲刷队列
     */
    private fun startNetworkMonitoring() {
        if (connectivityManager == null) return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    isNetworkAvailable = true
                    Log.i(TAG, "Network connection restored! Triggering offline alert flush...")
                    flushPendingAlerts()
                }

                override fun onLost(network: Network) {
                    isNetworkAvailable = false
                    Log.w(TAG, "Network connection lost (e.g. entering underground basement).")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    /**
     * 冲刷并补发积压的所有离线警报
     */
    fun flushPendingAlerts() {
        scope.launch {
            mutex.withLock {
                if (queueList.isEmpty()) return@withLock
                Log.i(TAG, "Flushing ${queueList.size} pending offline alerts...")

                val iterator = queueList.iterator()
                while (iterator.hasNext()) {
                    val alert = iterator.next()
                    try {
                        val success = onRetryAlert(alert)
                        if (success) {
                            iterator.remove()
                            Log.i(TAG, "Successfully resent offline alert: ${alert.title}")
                        } else {
                            Log.w(TAG, "Failed resending alert: ${alert.title}, aborting flush.")
                            break
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception during alert resend: ${alert.title}", e)
                        break
                    }
                }
                persistQueueLocked()
            }
        }
    }
}
