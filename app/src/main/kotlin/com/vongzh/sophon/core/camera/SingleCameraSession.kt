package com.vongzh.sophon.core.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraMetadata
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.core.config.ImageTuning
import com.vongzh.sophon.domain.model.CameraPosition
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 单路摄像头会话生命周期包装器 (SingleCameraSession)
 *
 * 【安全红线 - 底线一 (Zero Impact on IVI)】
 * 1. 单路仅绑定唯一的 SurfaceTexture，杜绝多动态 Surface 导致的 HAL 死锁；
 * 2. 内部独立异步 HandlerThread，避免阻塞主线程与车机核心逻辑；
 * 3. 严格的 CaptureSession 与 CameraDevice 生命周期防御性关闭。
 */
class SingleCameraSession(
    private val context: Context,
    val position: CameraPosition,
    private val physicalCameraId: String
) {

    companion object {
        private const val TAG = "Sophon.SingleCamera"
        private const val CAMERA_OPEN_TIMEOUT_MS = 2500L
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private val openCloseLock = Semaphore(1)
    private var isSessionActive = false

    var surfaceTexture: SurfaceTexture? = null
        private set
    private var outputSurface: Surface? = null

    /**
     * 初始化专有相机后台调度线程
     */
    fun startThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("Sophon-Cam-${position.tag}").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
    }

    /**
     * 安全打开摄像头
     *
     * @param targetTexture 预分配的 OES 目标 SurfaceTexture
     */
    @SuppressLint("MissingPermission")
    fun open(targetTexture: SurfaceTexture): Boolean {
        startThread()
        this.surfaceTexture = targetTexture

        if (!openCloseLock.tryAcquire(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Camera open lock timeout for $position (ID: $physicalCameraId)")
            return false
        }

        try {
            Log.i(TAG, "Opening camera $position (ID: $physicalCameraId)...")
            cameraManager.openCamera(physicalCameraId, deviceStateCallback, cameraHandler)
            return true
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera $position", e)
            openCloseLock.release()
            return false
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied for $position", e)
            openCloseLock.release()
            return false
        }
    }

    private val deviceStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.i(TAG, "Camera $position opened successfully.")
            cameraDevice = camera
            openCloseLock.release()
            createCaptureSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "Camera $position disconnected.")
            openCloseLock.release()
            closeInternal()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera $position error: $error")
            openCloseLock.release()
            closeInternal()
        }
    }

    private var previewSurface: Surface? = null

    /**
     * 绑定或解绑前台 UI 实时预览 Surface (如 SurfaceView)
     */
    fun setPreviewSurface(surface: Surface?) {
        cameraHandler?.post {
            if (this.previewSurface == surface) return@post
            this.previewSurface = surface
            if (cameraDevice != null && isSessionActive) {
                createCaptureSession()
            }
        }
    }

    /**
     * 建立预览与采集会话 (支持 GPU 编码 Surface 与 UI 预览 Surface 双向分发)
     */
    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val texture = surfaceTexture ?: return

        try {
            // 动态协商车规摄像头最优分辨率：优先 1920x1080，若硬件不支持则自动向下兼容最佳尺寸
            val optimalSize = resolveOptimalBufferSize()
            texture.setDefaultBufferSize(optimalSize.width, optimalSize.height)
            Log.i(TAG, "Camera $position buffer size negotiated: ${optimalSize.width}x${optimalSize.height}")
            val surface = Surface(texture)
            outputSurface = surface

            val surfaces = mutableListOf(surface)
            val currentPreview = previewSurface
            if (currentPreview != null && currentPreview.isValid) {
                surfaces.add(currentPreview)
            }

            @Suppress("DEPRECATION")
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    isSessionActive = true
                    Log.i(TAG, "CaptureSession configured for camera $position on ${surfaces.size} surfaces.")
                    startRepeatingRequest(session, surfaces)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "CaptureSession configuration failed for camera $position!")
                    isSessionActive = false
                }
            }, cameraHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session for camera $position", e)
        }
    }

    private fun startRepeatingRequest(session: CameraCaptureSession, surfaces: List<Surface>) {
        val device = cameraDevice ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                for (s in surfaces) {
                    if (s.isValid) {
                        addTarget(s)
                    }
                }
                // 车载环境建议锁定或连续自动曝光
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                applyImageTuning(this, SophonApp.preferences.imageTuning)
            }
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
            Log.d(TAG, "Repeating capture request active for camera $position on ${surfaces.size} targets.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set repeating request for camera $position", e)
        }
    }

    /**
     * 自动探测并协商车机传感器支持的最佳分辨率
     * 优先 1080p -> 720p -> 16:9 最大尺寸 -> 硬件最大尺寸
     */
    private fun resolveOptimalBufferSize(): android.util.Size {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(physicalCameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = map?.getOutputSizes(SurfaceTexture::class.java)
            if (!outputSizes.isNullOrEmpty()) {
                val exact1080p = outputSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
                if (exact1080p != null) return exact1080p

                val exact720p = outputSizes.firstOrNull { it.width == 1280 && it.height == 720 }
                if (exact720p != null) return exact720p

                val aspect16x9 = outputSizes.filter { size ->
                    val ratio = size.width.toDouble() / size.height.toDouble()
                    kotlin.math.abs(ratio - (16.0 / 9.0)) < 0.05
                }.maxByOrNull { it.width * it.height }
                if (aspect16x9 != null) return aspect16x9

                return outputSizes.maxByOrNull { it.width * it.height } ?: android.util.Size(1920, 1080)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query supported camera output sizes for $position", e)
        }
        return android.util.Size(1920, 1080)
    }

    /**
     * 影像参数调节：将曝光补偿、降噪、边缘锐化与高动态色调映射写入采集请求
     *
     * 走 Camera2 标准硬件参数（CONTROL_AE_EXPOSURE_COMPENSATION / NOISE_REDUCTION_MODE / EDGE_MODE / TONEMAP_MODE），
     * 而非在后处理 shader 里做全局数字提亮——硬件 ISP 侧调整不会损失动态范围与信噪比，且具备 0 GPU 功耗优势。
     */
    private fun applyImageTuning(builder: CaptureRequest.Builder, tuning: ImageTuning) {
        val characteristics = try {
            cameraManager.getCameraCharacteristics(cameraDevice?.id ?: physicalCameraId)
        } catch (e: Exception) {
            null
        }

        val isRainyNight = SophonApp.preferences.isRainyNightModeEnabled

        // 1. 曝光补偿：若开启雨夜增强，自动向正向曝光补偿偏置（至少 +3 步进，约 +1.0 EV），并受设备上限约束
        val range = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val baseEv = tuning.evSteps
        val effectiveEv = if (isRainyNight) {
            maxOf(baseEv, 3)
        } else {
            baseEv
        }
        val clampedEv = if (range != null) {
            effectiveEv.coerceIn(range.lower, range.upper)
        } else {
            effectiveEv
        }
        try {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedEv)
        } catch (e: Exception) {
            Log.w(TAG, "AE exposure compensation unsupported on $position: ${e.message}")
        }

        // 2. 降噪算法：雨夜模式强制采用高质量多帧降噪 (HIGH_QUALITY)
        val noiseMode = if (isRainyNight) {
            CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
        } else {
            when (tuning.noiseReductionMode) {
                0 -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
                2 -> CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
                else -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
            }
        }
        try {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseMode)
        } catch (e: Exception) {
            Log.w(TAG, "Noise reduction mode unsupported on $position: ${e.message}")
        }

        // 3. 硬件边缘锐化 (EDGE_MODE)：雨夜增强时启用 HIGH_QUALITY，强化水雾与夜间车道边缘
        val edgeMode = if (isRainyNight) {
            CameraMetadata.EDGE_MODE_HIGH_QUALITY
        } else {
            CameraMetadata.EDGE_MODE_FAST
        }
        try {
            builder.set(CaptureRequest.EDGE_MODE, edgeMode)
        } catch (e: Exception) {
            Log.w(TAG, "Edge mode unsupported on $position: ${e.message}")
        }

        // 4. 色调映射 (TONEMAP_MODE)：雨夜增强时启用 HIGH_QUALITY，获得更平滑的动态范围与防眩光还原
        val tonemapMode = if (isRainyNight) {
            CameraMetadata.TONEMAP_MODE_HIGH_QUALITY
        } else {
            CameraMetadata.TONEMAP_MODE_FAST
        }
        try {
            builder.set(CaptureRequest.TONEMAP_MODE, tonemapMode)
        } catch (e: Exception) {
            Log.w(TAG, "Tonemap mode unsupported on $position: ${e.message}")
        }

        // 5. 场景模式 (CONTROL_SCENE_MODE)：若底层相机 HAL 声明支持 NIGHT 场景，则尝试激活夜景 ISP 调优曲线
        if (isRainyNight) {
            val sceneModes = characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
            if (sceneModes != null && sceneModes.contains(CameraMetadata.CONTROL_SCENE_MODE_NIGHT)) {
                try {
                    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                    builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_NIGHT)
                } catch (e: Exception) {
                    Log.w(TAG, "Night scene mode unsupported on $position: ${e.message}")
                }
            }
        }
    }

    /**
     * 设置中心变更影像参数后热更新本路采集请求（不重建 Session，避免 HAL 抖动）
     */
    fun refreshImageTuning() {
        cameraHandler?.post {
            val session = captureSession ?: return@post
            val device = cameraDevice ?: return@post
            if (!isSessionActive) return@post
            try {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    outputSurface?.let { if (it.isValid) addTarget(it) }
                    previewSurface?.let { if (it.isValid) addTarget(it) }
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    applyImageTuning(this, SophonApp.preferences.imageTuning)
                }
                session.setRepeatingRequest(builder.build(), null, cameraHandler)
                Log.i(TAG, "Image tuning refreshed on $position.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh image tuning on $position: ${e.message}")
            }
        }
    }

    /**
     * 安全释放相机与底层硬件资源
     */
    fun close() {
        try {
            openCloseLock.acquire()
            closeInternal()
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring lock during camera close for $position", e)
        } finally {
            openCloseLock.release()
        }
    }

    private fun closeInternal() {
        isSessionActive = false
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing capture session for $position", e)
        } finally {
            captureSession = null
        }

        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera device for $position", e)
        } finally {
            cameraDevice = null
        }

        outputSurface?.release()
        outputSurface = null

        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        Log.i(TAG, "Camera $position closed and threads stopped.")
    }
}
