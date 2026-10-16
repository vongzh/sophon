package com.vongzh.sophon.core.config

/**
 * 影像参数调节 (ImageTuning)
 *
 * 走 Camera2 标准硬件采集参数，而非后处理数字提亮：
 * - 曝光补偿 evSteps：以 1/3 EV 为单位的整数步进，受设备 CONTROL_AE_COMPENSATION_RANGE 夹紧；
 * - 降噪档位 noiseReductionMode：0=关闭 / 1=快速 / 2=高质量。
 *
 * 夜视为组合预设：+2 档曝光补偿 + 关闭降噪（保留暗部细节，避免涂抹运动物体）。
 */
data class ImageTuning(
    val evSteps: Int = 0,
    val noiseReductionMode: Int = DEFAULT_NOISE_MODE
) {
    companion object {
        const val DEFAULT_NOISE_MODE = 1

        /** 夜视预设：提亮两档并关闭降噪，避免运动目标被时域降噪涂抹 */
        val NIGHT_VISION = ImageTuning(evSteps = 2, noiseReductionMode = 0)

        /** 标准行车预设 */
        val STANDARD = ImageTuning(evSteps = 0, noiseReductionMode = DEFAULT_NOISE_MODE)
    }
}
