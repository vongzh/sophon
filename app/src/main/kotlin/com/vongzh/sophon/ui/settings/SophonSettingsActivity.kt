package com.vongzh.sophon.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vongzh.sophon.R
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.core.remote.bark.BarkNotifier
import com.vongzh.sophon.core.remote.feishu.FeishuBotClient
import com.vongzh.sophon.core.remote.telegram.TelegramBotClient
import com.vongzh.sophon.core.storage.UsbBenchmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 极视座舱系统偏好设置界面 (SophonSettingsActivity)
 *
 * 【座舱级体验】
 * 1. 深度适配 FlymeAuto 深色主题；
 * 2. 实时读写 SophonPreferences，配置即时生效；
 * 3. 支持 Bark / Telegram / 飞书 连通性一键测试与多挡位行车安全策略定制；
 * 4. 内置车规级 U 盘物理写入基准测速与健康体检。
 */
class SophonSettingsActivity : AppCompatActivity() {

    private lateinit var etBarkServer: EditText
    private lateinit var etBarkKey: EditText
    private lateinit var btnTestBark: TextView

    private lateinit var rgChunkDuration: RadioGroup
    private lateinit var rbChunk1min: RadioButton
    private lateinit var rbChunk3min: RadioButton
    private lateinit var rbChunk5min: RadioButton

    private lateinit var rgHazardSpeed: RadioGroup
    private lateinit var rbHazard15: RadioButton
    private lateinit var rbHazard20: RadioButton
    private lateinit var rbHazardOff: RadioButton

    private lateinit var swAutoCrashLock: Switch
    private lateinit var swFisheye: Switch
    private lateinit var swOsdWatermark: Switch
    private lateinit var swRainyNightMode: Switch
    private lateinit var swDoorOpenWarning: Switch
    private lateinit var swBatteryProtection: Switch
    private lateinit var swSentryDeterrent: Switch
    private lateinit var swPipAuto: Switch

    // Telegram
    private lateinit var swTelegramEnabled: Switch
    private lateinit var etTelegramBotToken: EditText
    private lateinit var etTelegramChatId: EditText
    private lateinit var btnTestTelegram: TextView

    // Feishu
    private lateinit var swFeishuEnabled: Switch
    private lateinit var etFeishuWebhook: EditText
    private lateinit var etFeishuAppId: EditText
    private lateinit var etFeishuAppSecret: EditText
    private lateinit var etFeishuReceiveId: EditText
    private lateinit var btnTestFeishu: TextView

    // USB Benchmark
    private lateinit var tvBenchmarkResult: TextView
    private lateinit var btnRunUsbBenchmark: TextView

    private val barkNotifier = BarkNotifier()
    private val telegramClient = TelegramBotClient()
    private val feishuClient = FeishuBotClient()
    private val usbBenchmark = UsbBenchmark()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        loadCurrentPreferences()
        setupListeners()
    }

    private fun initViews() {
        etBarkServer = findViewById(R.id.et_bark_server)
        etBarkKey = findViewById(R.id.et_bark_key)
        btnTestBark = findViewById(R.id.btn_test_bark)

        rgChunkDuration = findViewById(R.id.rg_chunk_duration)
        rbChunk1min = findViewById(R.id.rb_chunk_1min)
        rbChunk3min = findViewById(R.id.rb_chunk_3min)
        rbChunk5min = findViewById(R.id.rb_chunk_5min)

        rgHazardSpeed = findViewById(R.id.rg_hazard_speed)
        rbHazard15 = findViewById(R.id.rb_hazard_15kmh)
        rbHazard20 = findViewById(R.id.rb_hazard_20kmh)
        rbHazardOff = findViewById(R.id.rb_hazard_off)

        swAutoCrashLock = findViewById(R.id.sw_auto_crash_lock)
        swFisheye = findViewById(R.id.sw_fisheye_correction)
        swOsdWatermark = findViewById(R.id.sw_osd_watermark)
        swRainyNightMode = findViewById(R.id.sw_rainy_night_shader)
        swDoorOpenWarning = findViewById(R.id.sw_door_open_warning)
        swBatteryProtection = findViewById(R.id.sw_battery_protection)
        swSentryDeterrent = findViewById(R.id.sw_sentry_deterrent)
        swPipAuto = findViewById(R.id.sw_pip_auto)

        swTelegramEnabled = findViewById(R.id.sw_telegram_enabled)
        etTelegramBotToken = findViewById(R.id.et_telegram_bot_token)
        etTelegramChatId = findViewById(R.id.et_telegram_chat_id)
        btnTestTelegram = findViewById(R.id.btn_test_telegram)

        swFeishuEnabled = findViewById(R.id.sw_feishu_enabled)
        etFeishuWebhook = findViewById(R.id.et_feishu_webhook)
        etFeishuAppId = findViewById(R.id.et_feishu_app_id)
        etFeishuAppSecret = findViewById(R.id.et_feishu_app_secret)
        etFeishuReceiveId = findViewById(R.id.et_feishu_receive_id)
        btnTestFeishu = findViewById(R.id.btn_test_feishu)

        tvBenchmarkResult = findViewById(R.id.tv_benchmark_result)
        btnRunUsbBenchmark = findViewById(R.id.btn_run_usb_benchmark)

        findViewById<TextView>(R.id.btn_settings_back).setOnClickListener {
            finish()
        }

        findViewById<TextView>(R.id.btn_settings_save).setOnClickListener {
            savePreferences()
            Toast.makeText(this, "配置已保存并生效", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadCurrentPreferences() {
        val prefs = SophonApp.preferences

        etBarkServer.setText(prefs.barkServerUrl)
        etBarkKey.setText(prefs.barkDeviceKey)

        when (prefs.chunkDurationSec) {
            180 -> rbChunk3min.isChecked = true
            300 -> rbChunk5min.isChecked = true
            else -> rbChunk1min.isChecked = true
        }

        when (prefs.hazardSpeedThresholdKmh) {
            20f -> rbHazard20.isChecked = true
            0f -> rbHazardOff.isChecked = true
            else -> rbHazard15.isChecked = true
        }

        swAutoCrashLock.isChecked = prefs.isAutoCrashLockEnabled
        swFisheye.isChecked = prefs.isFisheyeCorrectionEnabled
        swOsdWatermark.isChecked = prefs.isOsdWatermarkEnabled
        swRainyNightMode.isChecked = prefs.isRainyNightModeEnabled
        swDoorOpenWarning.isChecked = prefs.isDoorOpenWarningEnabled
        swBatteryProtection.isChecked = prefs.isBatteryProtectionEnabled
        swSentryDeterrent.isChecked = prefs.isSentryDeterrentEnabled
        swPipAuto.isChecked = prefs.isPipAutoEnabled

        // Telegram
        swTelegramEnabled.isChecked = prefs.isTelegramEnabled
        etTelegramBotToken.setText(prefs.telegramBotToken)
        etTelegramChatId.setText(prefs.telegramChatId)

        // Feishu
        swFeishuEnabled.isChecked = prefs.isFeishuEnabled
        etFeishuWebhook.setText(prefs.feishuWebhookUrl)
        etFeishuAppId.setText(prefs.feishuAppId)
        etFeishuAppSecret.setText(prefs.feishuAppSecret)
        etFeishuReceiveId.setText(prefs.feishuReceiveId)
    }

    private fun setupListeners() {
        // Bark 测试
        btnTestBark.setOnClickListener {
            val server = etBarkServer.text.toString().trim()
            val key = etBarkKey.text.toString().trim()

            if (key.isBlank()) {
                Toast.makeText(this, "请先填写 Bark Device Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnTestBark.text = "发送中..."
            barkNotifier.sendPush(
                serverUrl = if (server.isNotBlank()) server else null,
                deviceKey = key,
                title = "极视 (Sophon) 测试推送",
                body = "领克08 车载行车记录仪推送通道配置成功！"
            ) { success, msg ->
                runOnUiThread {
                    btnTestBark.text = "测试推送"
                    if (success) {
                        Toast.makeText(this, "测试通知已成功送达手机！", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "推送失败: $msg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Telegram 测试
        btnTestTelegram.setOnClickListener {
            val token = etTelegramBotToken.text.toString().trim()
            val chatId = etTelegramChatId.text.toString().trim()

            if (token.isBlank() || chatId.isBlank()) {
                Toast.makeText(this, "请先填写 Telegram Bot Token 与 Chat ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnTestTelegram.text = "发送中..."
            lifecycleScope.launch(Dispatchers.IO) {
                telegramClient.sendTextMessage(
                    botToken = token,
                    chatId = chatId,
                    text = "🛰️ <b>极视 (Sophon) 车载电报机器人连通性测试</b>\n领克08 通信链路正常，已就绪接收远程控制！"
                ) { success ->
                    runOnUiThread {
                        btnTestTelegram.text = "测试电报"
                        if (success) {
                            Toast.makeText(this@SophonSettingsActivity, "Telegram 测试消息已成功送达！", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@SophonSettingsActivity, "Telegram 发送失败，请检查网络或 Token", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        // 飞书测试
        btnTestFeishu.setOnClickListener {
            val webhook = etFeishuWebhook.text.toString().trim()
            val appId = etFeishuAppId.text.toString().trim()
            val appSecret = etFeishuAppSecret.text.toString().trim()
            val receiveId = etFeishuReceiveId.text.toString().trim()

            if (webhook.isBlank() && (appId.isBlank() || appSecret.isBlank() || receiveId.isBlank())) {
                Toast.makeText(this, "请先填写飞书群 Webhook 或自建应用 AppID/Secret/ReceiveID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnTestFeishu.text = "发送中..."
            lifecycleScope.launch(Dispatchers.IO) {
                val success = if (webhook.isNotBlank()) {
                    feishuClient.sendWebhookMessage(
                        webhookUrl = webhook,
                        text = "🛰️ 极视 (Sophon) 飞书机器人测试\n领克08 车载行车记录仪通信通道连接正常！"
                    )
                } else {
                    feishuClient.sendAppMessage(
                        appId = appId,
                        appSecret = appSecret,
                        receiveId = receiveId,
                        text = "🛰️ 极视 (Sophon) 飞书企业应用直发测试\n领克08 通信链路正常！"
                    )
                }

                withContext(Dispatchers.Main) {
                    btnTestFeishu.text = "测试飞书"
                    if (success) {
                        Toast.makeText(this@SophonSettingsActivity, "飞书测试消息已成功送达！", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@SophonSettingsActivity, "飞书发送失败，请检查配置或网络", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // USB 写入测速基准测试
        btnRunUsbBenchmark.setOnClickListener {
            val usbRoot = SophonApp.storageVault.currentUsbRoot.value
            if (usbRoot == null) {
                Toast.makeText(this, "未检测到已挂载的外置 U 盘，无法测速", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnRunUsbBenchmark.text = "⏳ 正在执行 8MB 顺序写入测速..."
            btnRunUsbBenchmark.isEnabled = false
            tvBenchmarkResult.text = "正在向外置 U 盘写入基准测试块并强制下刷物理 NAND，请稍候..."

            lifecycleScope.launch {
                val report = usbBenchmark.runBenchmark(usbRoot)
                btnRunUsbBenchmark.text = "⚡ 开始 U 盘写入基准测速"
                btnRunUsbBenchmark.isEnabled = true

                if (report.isSuccess) {
                    val gradeText = when (report.grade) {
                        UsbBenchmark.UsbHealthGrade.EXCELLENT -> "🟢 【优秀】"
                        UsbBenchmark.UsbHealthGrade.GOOD -> "🟡 【良好】"
                        UsbBenchmark.UsbHealthGrade.POOR -> "🔴 【警告】"
                    }
                    tvBenchmarkResult.text = """
                        $gradeText 顺序写入速度: ${String.format("%.1f", report.writeSpeedMBs)} MB/s
                        • U 盘容量: 可用 ${String.format("%.1f", report.freeSpaceGB)} GB / 总计 ${String.format("%.1f", report.totalSpaceGB)} GB (已用 ${report.usedSpacePercent}%)
                        • 车规建议: ${report.summary}
                    """.trimIndent()
                } else {
                    tvBenchmarkResult.text = "❌ 测速失败: ${report.errorMessage ?: "未知错误"}\n${report.summary}"
                }
            }
        }
    }

    private fun savePreferences() {
        val prefs = SophonApp.preferences

        prefs.barkServerUrl = etBarkServer.text.toString().trim()
        prefs.barkDeviceKey = etBarkKey.text.toString().trim()

        prefs.chunkDurationSec = when {
            rbChunk3min.isChecked -> 180
            rbChunk5min.isChecked -> 300
            else -> 60
        }

        prefs.hazardSpeedThresholdKmh = when {
            rbHazard20.isChecked -> 20f
            rbHazardOff.isChecked -> 0f
            else -> 15f
        }

        prefs.isAutoCrashLockEnabled = swAutoCrashLock.isChecked
        prefs.isFisheyeCorrectionEnabled = swFisheye.isChecked
        prefs.isOsdWatermarkEnabled = swOsdWatermark.isChecked
        prefs.isRainyNightModeEnabled = swRainyNightMode.isChecked
        prefs.isDoorOpenWarningEnabled = swDoorOpenWarning.isChecked
        prefs.isBatteryProtectionEnabled = swBatteryProtection.isChecked
        prefs.isSentryDeterrentEnabled = swSentryDeterrent.isChecked
        prefs.isPipAutoEnabled = swPipAuto.isChecked

        // Telegram
        prefs.isTelegramEnabled = swTelegramEnabled.isChecked
        prefs.telegramBotToken = etTelegramBotToken.text.toString().trim()
        prefs.telegramChatId = etTelegramChatId.text.toString().trim()

        // Feishu
        prefs.isFeishuEnabled = swFeishuEnabled.isChecked
        prefs.feishuWebhookUrl = etFeishuWebhook.text.toString().trim()
        prefs.feishuAppId = etFeishuAppId.text.toString().trim()
        prefs.feishuAppSecret = etFeishuAppSecret.text.toString().trim()
        prefs.feishuReceiveId = etFeishuReceiveId.text.toString().trim()

        // 重启远程服务通道以应用新配置
        SophonApp.remoteDispatcher.startRemoteServices()
    }
}
