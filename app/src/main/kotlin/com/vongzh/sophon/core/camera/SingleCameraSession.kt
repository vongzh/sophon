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
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
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

    /**
     * 建立预览与采集会话 (单路单 Surface 约束)
     */
    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val texture = surfaceTexture ?: return

        try {
            // 设置底层采集分辨率 (以 1920x1080 30fps 为基准)
            texture.setDefaultBufferSize(1920, 1080)
            val surface = Surface(texture)
            outputSurface = surface

            val surfaces = listOf(surface)

            @Suppress("DEPRECATION")
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    isSessionActive = true
                    Log.i(TAG, "CaptureSession configured for camera $position.")
                    startRepeatingRequest(session, surface)
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

    private fun startRepeatingRequest(session: CameraCaptureSession, surface: Surface) {
        val device = cameraDevice ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                // 车载环境建议锁定或连续自动曝光
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
            Log.d(TAG, "Repeating capture request active for camera $position.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set repeating request for camera $position", e)
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
