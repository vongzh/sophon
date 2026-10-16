package com.vongzh.sophon.ui.lan

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.vongzh.sophon.R
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.core.lan.LanShareServer

/**
 * 车载局域网快传二维码直连对话框 (LanShareDialog)
 *
 * 【车机互联极致体验】
 * 1. 动态探测车机热点局域网 IP；
 * 2. 实时生成高清晰 ZXing 二维码；
 * 3. 手机扫码即可免流量访问车载视频门户，支持点播与极速导出；
 * 4. 未联网时提供智能诊断与车机网络配置快捷引导。
 */
class LanShareDialog(context: Context) : Dialog(context) {

    private lateinit var ivQrCode: ImageView
    private lateinit var layoutNoNetwork: View
    private lateinit var layoutUnconnectedActions: View
    private lateinit var tvShareUrl: TextView
    private lateinit var btnClose: ImageView
    private lateinit var btnRetryNetwork: TextView
    private lateinit var btnOpenHotspot: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_lan_share)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.65f)
        }

        ivQrCode = findViewById(R.id.iv_qr_code)
        layoutNoNetwork = findViewById(R.id.layout_no_network)
        layoutUnconnectedActions = findViewById(R.id.layout_unconnected_actions)
        tvShareUrl = findViewById(R.id.tv_share_url)
        btnClose = findViewById(R.id.btn_dialog_close)
        btnRetryNetwork = findViewById(R.id.btn_retry_network)
        btnOpenHotspot = findViewById(R.id.btn_open_hotspot)

        btnClose.setOnClickListener {
            dismiss()
        }

        btnRetryNetwork.setOnClickListener {
            renderNetworkPortal()
            Toast.makeText(context, "已重新检测车机局域网状态", Toast.LENGTH_SHORT).show()
        }

        btnOpenHotspot.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                } catch (e2: Exception) {
                    Toast.makeText(context, "无法直接打开系统网络设置，请在车机中控中开启热点", Toast.LENGTH_LONG).show()
                }
            }
        }

        renderNetworkPortal()
    }

    override fun onStart() {
        super.onStart()
        window?.let { win ->
            val displayMetrics = context.resources.displayMetrics
            val maxAllowedWidth = (displayMetrics.widthPixels * 0.90f).toInt()
            val preferredWidth = (520 * displayMetrics.density).toInt()
            val finalWidth = minOf(preferredWidth, maxAllowedWidth)
            win.setLayout(finalWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun renderNetworkPortal() {
        val port = SophonApp.preferences.lanSharePort
        val lanServer = LanShareServer(SophonApp.storageVault, port)
        val ip = lanServer.getLocalIpAddress()

        if (ip.isNullOrBlank()) {
            tvShareUrl.text = "请先开启车机热点或连接 Wi-Fi"
            tvShareUrl.setOnClickListener(null)
            ivQrCode.setImageDrawable(null)
            layoutNoNetwork.visibility = View.VISIBLE
            layoutUnconnectedActions.visibility = View.VISIBLE
            return
        }

        layoutNoNetwork.visibility = View.GONE
        layoutUnconnectedActions.visibility = View.GONE

        val shareUrl = "http://$ip:$port"
        tvShareUrl.text = shareUrl

        val qrBitmap = lanServer.generateQrCodeBitmap(shareUrl, 360)
        if (qrBitmap != null) {
            ivQrCode.setImageBitmap(qrBitmap)
        }

        tvShareUrl.setOnClickListener {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("LanShareUrl", shareUrl))
            Toast.makeText(context, "快传地址已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }
}
