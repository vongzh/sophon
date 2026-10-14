package com.vongzh.sophon.domain.contract

import kotlinx.coroutines.flow.StateFlow

/**
 * 连续硬件编码与无感切片流水线契约 (Recording Pipeline)
 *
 * 【性能红线 - 零掉帧无感切换】
 * 彻底消除前代 Stop/Start 导致的 500ms 录像盲区：
 * MediaCodec 实例持续运行，利用 IDR 帧在流式复用器 (Muxer) 间实现 0 毫秒热切换。
 */
interface IRecordingPipeline {

    /**
     * 当前是否正在编码录制中
     */
    val isRecording: StateFlow<Boolean>

    /**
     * 当前连续录制持续时长 (秒)
     */
    val recordingDurationSec: StateFlow<Long>

    /**
     * 启动硬件编码录制管线
     */
    fun startRecording(): Boolean

    /**
     * 安全停止录制管线 (刷新尾部数据并释放资源)
     */
    fun stopRecording()

    /**
     * 触发紧急碰撞 / 手动锁存 (将当前切片及后续切片归类为 Event 目录，防循环覆盖)
     */
    fun triggerEventLock(reason: String)

    /**
     * 周期性无感切片触发 (由定时器调用，请求 IDR 关键帧并切换新文件)
     */
    fun requestChunkSlice()
}
