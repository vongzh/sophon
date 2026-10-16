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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _isAllCamerasStreaming = MutableStateFlow(false)
    val isAllCamerasStreaming: StateFlow<Boolean> = _isAllCamerasStreaming.asStateFlow()

    private val _availableCameras = MutableStateFlow<Set<CameraPosition>>(emptySet())
    val availableCameras: StateFlow<Set<CameraPosition>> = _availableCameras.asStateFlow()

    private var isStarting = false

    /**
     * 按照串行化栅栏调度启动四路摄像头
     */
    fun startAllCameras() {
        if (isStarting) return
        isStarting = true

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
                Log.d(TAG, "Entering barrier for camera: $pos (ID: $physicalId)")

                // 1. 向 GPU 合成器请求专属的 OES SurfaceTexture
                val surfaceTexture = compositor.bindCameraSurfaceTexture(pos)

                // 2. 创建并打开单路会话
                val session = SingleCameraSession(context, pos, physicalId)
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
