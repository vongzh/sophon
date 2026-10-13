package com.vongz.sophon.ui.lan

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.vongz.sophon.R
import com.vongz.sophon.SophonApp
import com.vongz.sophon.core.lan.LanShareServer

/**
 * 车载免流快传二维码直连对话框 (LanShareDialog)
 *
 * 【车机互联极致体验】
 * 1. 动态探测车机热点局域网 IP；
 * 2. 实时生成高清晰 ZXing 二维码；
 * 3. 手机扫码即可免流量访问车载视频门户，支持点播与极速导出。
 */
class LanShareDialog(context: Context) : Dialog(context) {

    private lateinit var ivQrCode: ImageView
    private lateinit var tvShareUrl: TextView
    private lateinit var btnClose: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_lan_share)

        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        ivQrCode = findViewById(R.id.iv_qr_code)
        tvShareUrl = findViewById(R.id.tv_share_url)
        btnClose = findViewById(R.id.btn_dialog_close)

        btnClose.setOnClickListener {
            dismiss()
        }

        renderNetworkPortal()
    }

    private fun renderNetworkPortal() {
        val lanServer = LanShareServer(SophonApp.storageVault, 8088)
        val ip = lanServer.getLocalIpAddress() ?: "192.168.43.1"
        val shareUrl = "http://$ip:8088"

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
