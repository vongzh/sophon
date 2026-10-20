package com.vongzh.sophon.core.storage

import android.content.Context
import android.os.Build
import android.util.Log
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.domain.contract.IStorageVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车规全维离线诊断导出器 (DiagnosticExporter)
 *
 * 【车规安全红线 - 底线二 (No USB, No Write)】
 * 1. 严格禁止在内部闪存创建临时日志文件，杜绝闪存磨损与存储占满风险；
 * 2. 仅在已插入可用外接 U 盘时，将车机 SoC 硬件、VHAL 权限报告、摄像头健康度与实时整车状态汇总导出；
 * 3. 产物路径：/SophonRecord/Diagnostics/sophon_diag_YYYYMMDD_HHMMSS.txt
 */
class DiagnosticExporter(
    private val context: Context,
    private val storage: IStorageVault? = null
) {
    companion object {
        private const val TAG = "Sophon.DiagnosticExporter"
    }

    private val activeStorage: IStorageVault?
        get() = storage ?: runCatching { SophonApp.storageVault }.getOrNull()

    data class ExportResult(
        val success: Boolean,
        val filePath: String? = null,
        val errorMessage: String? = null
    )

    suspend fun exportToUsb(): ExportResult = withContext(Dispatchers.IO) {
        val currentStorage = activeStorage
        val root = currentStorage?.currentUsbRoot?.value
        val isMounted = currentStorage?.isUsbMounted?.value ?: false

        if (!isMounted || root == null || !root.exists()) {
            return@withContext ExportResult(
                success = false,
                errorMessage = "未检测到可用 U 盘！根据车规安全红线二（零闪存写入），严禁写入机身闪存。"
            )
        }

        val diagDir = File(File(root, "SophonRecord"), "Diagnostics")
        try {
            if (!diagDir.exists()) {
                diagDir.mkdirs()
            }
        } catch (e: Exception) {
            return@withContext ExportResult(
                success = false,
                errorMessage = "无法创建诊断日志目录: ${e.message}"
            )
        }

        val timeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val targetFile = File(diagDir, "sophon_diag_${timeFormat}.txt")

        try {
            val content = buildDiagnosticReport()
            targetFile.writeText(content, Charsets.UTF_8)
            Log.i(TAG, "Diagnostic dump written to ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            ExportResult(
                success = true,
                filePath = targetFile.absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write diagnostic file", e)
            ExportResult(
                success = false,
                errorMessage = "写入诊断文件失败: ${e.message}"
            )
        }
    }

    fun buildDiagnosticReport(): String {
        val bus = runCatching { SophonApp.vehicleSignalBus }.getOrNull()
        val power = runCatching { SophonApp.powerStateMachine }.getOrNull()
        val prefs = runCatching { SophonApp.preferences }.getOrNull()
        val storage = activeStorage

        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())

        @Suppress("DEPRECATION")
        val pInfo = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }
        val versionName = pInfo?.versionName ?: "1.0.0"
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo?.longVersionCode?.toString() ?: "1" else pInfo?.versionCode?.toString() ?: "1"

        sb.appendLine("=================================================================")
        sb.appendLine("           SOPHON (极视) 车规座舱记录仪 · 离线系统诊断报告         ")
        sb.appendLine("=================================================================")
        sb.appendLine("生成时间: $now")
        sb.appendLine("软件版本: $versionName ($versionCode)")
        sb.appendLine("应用包名: ${context.packageName}")
        sb.appendLine()

        sb.appendLine("--- [1. 车机硬件与操作系统] ---")
        sb.appendLine("设备品牌 (MANUFACTURER): ${Build.MANUFACTURER}")
        sb.appendLine("设备型号 (MODEL): ${Build.MODEL}")
        sb.appendLine("主板名称 (BOARD): ${Build.BOARD}")
        sb.appendLine("硬件平台 (HARDWARE): ${Build.HARDWARE}")
        sb.appendLine("Android 版本: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("指纹信息: ${Build.FINGERPRINT}")
        sb.appendLine()

        sb.appendLine("--- [2. 车规 VHAL 总线与整车信号状态] ---")
        if (bus != null) {
            sb.appendLine("车速 (vehicleSpeed): %.1f km/h".format(bus.vehicleSpeed.value))
            sb.appendLine("挡位 (gearState): ${bus.gearState.value}")
            sb.appendLine("刹车制动 (isBraking): ${bus.isBraking.value}")
            sb.appendLine("转向信号 (turnSignalState): ${bus.turnSignalState.value}")
            sb.appendLine("车门状态 (doorState): ${bus.doorState.value}")
            sb.appendLine("12V 蓄电池电压 (batteryVoltage): %.2f V".format(bus.batteryVoltage.value))
            sb.appendLine("车外环境温度 (outsideTemperature): ${bus.outsideTemperature.value ?: "--"} °C")
            sb.appendLine("VHAL 权限状态 (vhalAuthError): ${bus.vhalAuthError.value ?: "授权正常 (无异常)"}")
            sb.appendLine("鸣笛状态 (isHornActive): ${bus.isHornActive.value}")
            val gnss = bus.gnssTelemetry.value
            sb.appendLine("GNSS 定位状态: ${gnss.toCoordinateString()} (Fix=${gnss.hasFix}, 卫星车速=%.1f km/h)".format(gnss.speedKmh))
        } else {
            sb.appendLine("VHAL 信号总线: 未初始化 / 离线")
        }
        if (power != null) {
            sb.appendLine("电源状态机 (powerState): ${power.currentState.value}")
            sb.appendLine("哨兵能耗梯次 (currentSentryTier): ${power.currentSentryTier.value}")
        } else {
            sb.appendLine("电源状态机: 未初始化 / 离线")
        }
        sb.appendLine()

        sb.appendLine("--- [3. 摄像头与影像渲染流水线] ---")
        val service = runCatching { com.vongzh.sophon.service.SophonCoreService.instance }.getOrNull()
        if (service != null) {
            val avail = service.availableCamerasFlow?.value ?: emptySet()
            sb.appendLine("可用摄像头通道 (Available): $avail")
            sb.appendLine("录制活跃状态: ${service.isRecordingFlow.value}")
            sb.appendLine("录制时长 (秒): ${service.recordingDurationSecFlow.value}")
            sb.appendLine("座舱麦克风活跃: ${service.isCabinAudioActive()}")
        } else {
            sb.appendLine("SophonCoreService: 未运行 / 离线")
        }
        if (prefs != null) {
            sb.appendLine("雨夜增强模式: ${prefs.isRainyNightModeEnabled}")
            sb.appendLine("曝光补偿档位: ${prefs.exposureCompensationSteps}")
        }
        sb.appendLine()

        sb.appendLine("--- [4. 存储架构与安全红线] ---")
        if (storage != null) {
            sb.appendLine("U 盘挂载状态: ${storage.isUsbMounted.value}")
            sb.appendLine("U 盘挂载路径: ${storage.currentUsbRoot.value?.absolutePath ?: "无"}")
            sb.appendLine("写入安全准入 (isWriteAllowed): ${storage.isWriteAllowed} (底线二: No USB, No Write)")
            val breakdown = storage.queryStorageBreakdown()
            sb.appendLine("总容量: %.2f GB".format(breakdown.totalBytes / (1024.0 * 1024 * 1024)))
            sb.appendLine("可用容量: %.2f GB".format(breakdown.freeBytes / (1024.0 * 1024 * 1024)))
            sb.appendLine("行车视频占用: %.2f GB".format(breakdown.drivingBytes / (1024.0 * 1024 * 1024)))
            sb.appendLine("哨兵视频占用: %.2f GB".format(breakdown.sentryBytes / (1024.0 * 1024 * 1024)))
            sb.appendLine("抓拍图片占用: %.2f GB".format(breakdown.photoBytes / (1024.0 * 1024 * 1024)))
        } else {
            sb.appendLine("存储模块: 未初始化")
        }
        sb.appendLine()

        sb.appendLine("--- [5. 用户偏好与规则] ---")
        if (prefs != null) {
            sb.appendLine("切片分段时长: ${prefs.chunkDurationSec} 秒")
            sb.appendLine("碰撞感应灵敏度: ${prefs.crashSensitivity}")
            sb.appendLine("碰撞自动加锁: ${prefs.isAutoCrashLockEnabled}")
            sb.appendLine("哨兵模式开关: ${prefs.isSentryModeEnabled}")
            sb.appendLine("哨兵声光威慑: ${prefs.isSentryDeterrentEnabled}")
            sb.appendLine("小电瓶低压保护: ${prefs.isBatteryProtectionEnabled} (阈值: ${prefs.batteryProtectionThresholdVolts}V)")
            sb.appendLine("盲区打灯补盲: ${prefs.isBlindSpotEnabled}")
            sb.appendLine("双闪警示限速: ${prefs.hazardSpeedThresholdKmh} km/h")
            sb.appendLine("开门防碰撞预警 (DOW): ${prefs.isDoorOpenWarningEnabled}")
            sb.appendLine("安全区地理围栏免巡逻: ${prefs.isGeofenceExemptionEnabled} (已配置 ${prefs.safeGeofences.size} 处安全点)")
            sb.appendLine("车规影像隐私合规脱敏导出: ${prefs.isPrivacyMaskingExportEnabled} (车牌/人脸马赛克)")
            sb.appendLine("三级漏斗超低功耗哨兵: ${prefs.isSentryLowPowerTierEnabled} (L0 预录待机 -> L1 戒备 -> L2 告警)")
        } else {
            sb.appendLine("用户偏好配置: 离线")
        }
        sb.appendLine("=================================================================")
        sb.appendLine("报告结束")

        return sb.toString()
    }
}
