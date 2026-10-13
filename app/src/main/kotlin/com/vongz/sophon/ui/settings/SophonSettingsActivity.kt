package com.vongz.sophon.ui.settings

import android.os.Bundle
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vongz.sophon.R
import com.vongz.sophon.SophonApp
import com.vongz.sophon.core.remote.bark.BarkNotifier

/**
 * 极视座舱系统偏好设置界面 (SophonSettingsActivity)
 *
 * 【座舱级体验】
 * 1. 深度适配 FlymeAuto 深色主题；
 * 2. 实时读写 SophonPreferences，配置即时生效；
 * 3. 支持 Bark 连通性一键测试与多挡位行车安全策略定制。
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

    private val barkNotifier = BarkNotifier()

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
    }

    private fun setupListeners() {
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
    }
}
