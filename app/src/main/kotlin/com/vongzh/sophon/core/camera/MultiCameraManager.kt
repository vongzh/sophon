package com.vongzh.sophon.core.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.vongzh.sophon.domain.contract.ICompositor
import com.vongzh.sophon.domain.model.CameraPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 四路摄像头并发栅栏调度管理器 (MultiCameraManager)
 *
 * 【安全红线 - 底线一 (Zero Impact on IVI)】
 * 1. 龙鹰一号 CSI 串行化打开栅栏 (Open Barrier)：四路相机间隔 150ms 逐一打开握手，杜绝底层 HAL 带宽竞争死锁；
 * 2. 状态感知与优雅恢复：当某一通道发生 HAL 错误时平滑自愈，严禁向系统抛出未捕获崩溃。
 */
class MultiCameraManager(
    private val context: Context,
    private val compositor: ICompositor
) {

    companion object {
        private const val TAG = "Sophon.MultiCameraManager"
        private const val CAMERA_OPEN_BARRIER_DELAY_MS = 150L // 串行打开栅栏安全间隔
    }

    private var managerJob = SupervisorJob()
    private var managerScope = CoroutineScope(managerJob + Dispatchers.Default)
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val sessions = ConcurrentHashMap<CameraPosition, SingleCameraSession>()
    private val pendingPreviewSurfaces = ConcurrentHashMap<CameraPosition, android.view.Surface>()
    private val recoveryJobs = ConcurrentHashMap<CameraPosition, Job>()
    private var physicalToPositionMap = ConcurrentHashMap<String, CameraPosition>()

    private val _isAllCamerasStreaming = MutableStateFlow(false)
    val isAllCamerasStreaming: StateFlow<Boolean> = _isAllCamerasStreaming.asStateFlow()

    private val _availableCameras = MutableStateFlow<Set<CameraPosition>>(emptySet())
    val availableCameras: StateFlow<Set<CameraPosition>> = _availableCameras.asStateFlow()

    private var isStarting = false
    private var isStopping = false

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            val pos = physicalToPositionMap[cameraId] ?: return
            if (!_availableCameras.value.contains(pos) && !isStopping) {
                Log.i(TAG, "Hardware camera ID $cameraId became available, fast-tracking auto-recovery for $pos...")
                scheduleCameraRecovery(pos, cameraId, isImmediate = true)
            }
        }

        override fun onCameraUnavailable(cameraId: String) {
            val pos = physicalToPositionMap[cameraId] ?: return
            Log.w(TAG, "Hardware camera ID $cameraId unavailable on HAL bus ($pos).")
        }
    }

    /**
     * 按照串行化栅栏调度启动四路摄像头
     */
    fun startAllCameras() {
        if (isStarting) return
        isStarting = true
        isStopping = false
        physicalToPositionMap.clear()

        try {
            cameraManager.registerAvailabilityCallback(availabilityCallback, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register camera availability callback: ${e.message}")
        }

        if (!managerJob.isActive) {
            managerJob = SupervisorJob()
            managerScope = CoroutineScope(managerJob + Dispatchers.Default)
        }

        managerScope.launch {
            Log.i(TAG, "Starting Multi-Camera Pipeline with Concurrency Barrier...")

            val cameraIds = resolvePhysicalCameraIds()
            val sequence = listOf(
                CameraPosition.FRONT,
                CameraPosition.BACK,
                CameraPosition.LEFT,
                CameraPosition.RIGHT
            )

            val openedPositions = mutableSetOf<CameraPosition>()

            for (pos in sequence) {
                val physicalId = cameraIds[pos] ?: continue
                physicalToPositionMap[physicalId] = pos
                Log.d(TAG, "Entering barrier for camera: $pos (ID: $physicalId)")

                // 1. 向 GPU 合成器请求专属的 OES SurfaceTexture
                val surfaceTexture = compositor.bindCameraSurfaceTexture(pos)

                // 2. 创建并打开单路会话 (挂接实时上下线感知与自愈监听)
                val session = SingleCameraSession(context, pos, physicalId).apply {
                    onSessionOpened = { p ->
                        val current = _availableCameras.value.toMutableSet()
                        current.add(p)
                        _availableCameras.value = current
                        _isAllCamerasStreaming.value = current.isNotEmpty()
                        Log.i(TAG, "Camera $p streaming online. Active set: $current")
                    }
                    onSessionClosed = { p, err ->
                        val current = _availableCameras.value.toMutableSet()
                        current.remove(p)
                        _availableCameras.value = current
                        _isAllCamerasStreaming.value = current.isNotEmpty()
                        Log.w(TAG, "Camera $p disconnected / offline (err: $err). Active set: $current")
                        scheduleCameraRecovery(p, physicalId)
                    }
                }
                sessions[pos] = session
                pendingPreviewSurfaces[pos]?.let { surface ->
                    if (surface.isValid) {
                        session.setPreviewSurface(surface)
                    }
                }
                val success = session.open(surfaceTexture)
                if (success) {
                    openedPositions.add(pos)
                    _availableCameras.value = openedPositions.toSet()
                } else {
                    Log.w(TAG, "Camera $pos failed to open, continuing other cameras.")
                    scheduleCameraRecovery(pos, physicalId)
                }

                // 3. 严格遵循 150ms 硬件总线避让延迟
                delay(CAMERA_OPEN_BARRIER_DELAY_MS)
            }

            _isAllCamerasStreaming.value = openedPositions.isNotEmpty()
            isStarting = false
            Log.i(TAG, "All cameras configured through concurrency barrier successfully. Available: $openedPositions")
        }
    }

    /**
     * 自动退避重连调度器：应对车辆振动、USB 抖动或 HAL 瞬时崩溃
     */
    private fun scheduleCameraRecovery(position: CameraPosition, physicalId: String, isImmediate: Boolean = false) {
        if (isStopping || !managerJob.isActive) return
        recoveryJobs[position]?.cancel()
        recoveryJobs[position] = managerScope.launch {
            var attempt = 0
            val backoffs = if (isImmediate) {
                listOf(200L, 2000L, 4000L, 8000L, 12000L)
            } else {
                listOf(2000L, 4000L, 8000L, 12000L, 15000L)
            }
            while (isActive && !isStopping) {
                val delayMs = backoffs.getOrElse(attempt) { 15000L }
                Log.i(TAG, "Camera $position scheduled for auto-recovery reconnect attempt #${attempt + 1} in ${delayMs}ms...")
                delay(delayMs)
                attempt++

                val isAvailable = try {
                    cameraManager.cameraIdList.contains(physicalId)
                } catch (e: Exception) {
                    false
                }

                if (!isAvailable) {
                    Log.d(TAG, "Physical camera $physicalId not present on system HAL yet, awaiting availability...")
                    continue
                }

                Log.i(TAG, "Executing auto-recovery handshake for camera $position (ID: $physicalId)...")
                val surfaceTexture = compositor.bindCameraSurfaceTexture(position)
                val session = sessions[position] ?: SingleCameraSession(context, position, physicalId).apply {
                    onSessionOpened = { p ->
                        val current = _availableCameras.value.toMutableSet()
                        current.add(p)
                        _availableCameras.value = current
                        _isAllCamerasStreaming.value = current.isNotEmpty()
                        Log.i(TAG, "Camera $p streaming online after recovery. Active set: $current")
                    }
                    onSessionClosed = { p, err ->
                        val current = _availableCameras.value.toMutableSet()
                        current.remove(p)
                        _availableCameras.value = current
                        _isAllCamerasStreaming.value = current.isNotEmpty()
                        Log.w(TAG, "Camera $p disconnected / offline again (err: $err). Active set: $current")
                        scheduleCameraRecovery(p, physicalId)
                    }
                }
                sessions[position] = session
                pendingPreviewSurfaces[position]?.let { surface ->
                    if (surface.isValid) {
                        session.setPreviewSurface(surface)
                    }
                }

                val success = session.open(surfaceTexture)
                if (success) {
                    Log.i(TAG, "Camera $position AUTO-RECOVERY SUCCESSFUL! Re-established stream.")
                    recoveryJobs.remove(position)
                    break
                } else {
                    Log.w(TAG, "Camera $position reconnect attempt #$attempt failed, will retry next interval.")
                }
            }
        }
    }

    /**
     * 自动推导车机物理摄像头 ID 映射
     */
    private fun resolvePhysicalCameraIds(): Map<CameraPosition, String> {
        val mapping = mutableMapOf<CameraPosition, String>()
        try {
            val idList = cameraManager.cameraIdList
            Log.i(TAG, "Available system camera IDs: ${idList.joinToString()}")

            if (idList.size >= 4) {
                // 车载四路系统常规映射
                mapping[CameraPosition.FRONT] = idList[0]
                mapping[CameraPosition.BACK] = idList[1]
                mapping[CameraPosition.LEFT] = idList[2]
                mapping[CameraPosition.RIGHT] = idList[3]
            } else {
                // 兼容调试环境：如果系统仅有 1~2 个相机，采用降级映射
                idList.forEachIndexed { index, id ->
                    when (index) {
                        0 -> mapping[CameraPosition.FRONT] = id
                        1 -> mapping[CameraPosition.BACK] = id
                        2 -> mapping[CameraPosition.LEFT] = id
                        3 -> mapping[CameraPosition.RIGHT] = id
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve camera IDs", e)
        }
        return mapping
    }

    /**
     * 动态挂载或解绑单路摄像头 UI 预览 Surface
     */
    fun setPreviewSurface(position: CameraPosition, surface: android.view.Surface?) {
        if (surface != null && surface.isValid) {
            pendingPreviewSurfaces[position] = surface
        } else {
            pendingPreviewSurfaces.remove(position)
        }
        sessions[position]?.setPreviewSurface(surface)
    }

    /**
     * 影像参数热更新：向所有已建立会话的摄像头重发采集请求
     *
     * 不重建 CaptureSession，避免 USB 摄像头在多路并发下出现 HAL 抖动与掉帧。
     */
    fun refreshImageTuning() {
        if (sessions.isEmpty()) {
            Log.i(TAG, "No active camera session, image tuning will apply on next camera start.")
            return
        }
        sessions.values.forEach { session ->
            try {
                session.refreshImageTuning()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh image tuning on ${session.position}: ${e.message}")
            }
        }
    }

    /**
     * 安全停止所有摄像头并释放显存与硬件锁
     */
    fun stopAllCameras() {
        Log.i(TAG, "Stopping all camera sessions...")
        isStopping = true
        try {
            cameraManager.unregisterAvailabilityCallback(availabilityCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering camera availability callback", e)
        }
        recoveryJobs.values.forEach { it.cancel() }
        recoveryJobs.clear()
        physicalToPositionMap.clear()

        _isAllCamerasStreaming.value = false
        _availableCameras.value = emptySet()
        sessions.values.forEach { session ->
            try {
                session.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing camera ${session.position}", e)
            }
        }
        sessions.clear()
        managerJob.cancelChildren()
    }
}
