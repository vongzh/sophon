package com.vongz.sophon.domain.contract

import android.graphics.SurfaceTexture
import android.view.Surface
import com.vongz.sophon.domain.model.CameraPosition

/**
 * GPU 离屏渲染合成与光学矫正管线契约 (Unified GPU Compositor)
 *
 * 【性能红线 - 底线一 (Zero Impact on IVI)】
 * 1. 龙鹰一号 GPU 按需休眠：当悬浮窗未显示且录制休眠时，渲染线程完全挂起；
 * 2. Brown-Conrady 广角矫正片元着色器实时消除左右后视镜鱼眼畸变；
 * 3. 动态时间戳与车身遥测数据 OSD 水印压制。
 */
interface ICompositor {

    /**
     * 初始化 EGL 上下文及离屏帧缓冲 (FBO)
     */
    fun initialize(outputWidth: Int, outputHeight: Int)

    /**
     * 为指定摄像头通道分配并绑定专属的 OES SurfaceTexture
     * 架构约束：单路摄像头仅绑定单一 SurfaceTexture，严禁动态增删 Surface
     */
    fun bindCameraSurfaceTexture(position: CameraPosition): SurfaceTexture

    /**
     * 绑定编码器的 Input Surface (MediaCodec 录制消费端)
     */
    fun setCodecInputSurface(surface: Surface)

    /**
     * 设置盲区补盲悬浮窗的目标 Surface (悬浮窗可见时动态绑定)
     */
    fun setOverlayPreviewSurface(surface: Surface?)

    /**
     * 动态控制补盲侧向视角渲染
     */
    fun setBlindSpotView(visible: Boolean, position: CameraPosition)

    /**
     * 挂起 UI 渲染线程 (节能休眠态)
     */
    fun pauseUiRendering()

    /**
     * 恢复 UI 渲染线程
     */
    fun resumeUiRendering()

    /**
     * 更新当前视频帧叠加的 OSD 遥测水印数据 (车速、挡位)
     */
    fun updateOsdMetadata(speedKmh: Float, gearText: String)

    /**
     * 彻底释放 EGL 上下文与纹理显存资源
     */
    fun release()
}
