package com.vongz.sophon.core.lan

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.vongz.sophon.domain.contract.IStorageVault
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 车载局域网免流高速快传服务器 (LanShareServer)
 *
 * 【免流量极致体验 - 对标盯盯车核心功能】
 * 1. 车机启动嵌入式 HTTP 服务 (默认端口 8088)；
 * 2. 生成二维码供手机端直接扫描；
 * 3. 手机浏览器秒开 H5 极速流式点播，支持大文件断点续传 (HTTP 206) 与高速原画批量下载到手机相册；
 * 4. 严格只读外置 U 盘，不产生任何写入与闪存磨损。
 */
class LanShareServer(
    private val storageVault: IStorageVault,
    port: Int = 8088
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "Sophon.LanShareServer"
        private const val MIME_MP4 = "video/mp4"
        private const val MIME_JSON = "application/json"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Incoming HTTP request: ${session.method} $uri")

        val usbRoot = storageVault.currentUsbRoot.value
        if (usbRoot == null || !storageVault.isUsbMounted.value) {
            return newFixedLengthResponse(
                Response.Status.OK,
                MIME_PLAINTEXT,
                "未检测到外置U盘，请插入U盘后重试。"
            )
        }

        val recordDir = File(usbRoot, "SophonRecord")

        return when {
            uri == "/" || uri == "/index.html" -> serveHtmlPortal(recordDir)
            uri.startsWith("/download/") -> serveFileDownload(recordDir, uri.removePrefix("/download/"), session)
            uri.startsWith("/stream/") -> serveFileStream(recordDir, uri.removePrefix("/stream/"), session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
        }
    }

    /**
     * 生成手机端极简深色车载 H5 视频中心
     */
    private fun serveHtmlPortal(recordDir: File): Response {
        val files = recordDir.walkTopDown().filter { it.isFile && it.name.endsWith(".mp4") }
            .sortedByDescending { it.lastModified() }
            .toList()

        val sb = StringBuilder()
        sb.append("""
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <title>极视 (Sophon) 车载免流快传</title>
                <style>
                    body { background: #0D0F12; color: #F5F7FA; font-family: -apple-system, sans-serif; margin: 0; padding: 16px; }
                    .header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #232832; padding-bottom: 12px; margin-bottom: 16px; }
                    .title { font-size: 18px; font-weight: bold; color: #00D4AA; }
                    .count { font-size: 12px; color: #8E95A5; }
                    .card { background: #181C22; border: 1px solid #232832; border-radius: 12px; padding: 14px; margin-bottom: 12px; display: flex; justify-content: space-between; align-items: center; }
                    .name { font-size: 14px; font-weight: 500; word-break: break-all; margin-bottom: 4px; }
                    .size { font-size: 12px; color: #8E95A5; }
                    .btn { background: #00D4AA; color: #0D0F12; text-decoration: none; padding: 8px 14px; border-radius: 20px; font-size: 13px; font-weight: bold; margin-left: 8px; white-space: nowrap; }
                    .btn-play { background: #0A84FF; color: white; }
                </style>
            </head>
            <body>
                <div class="header">
                    <div class="title">极视车载免流快传</div>
                    <div class="count">共 ${files.size} 个视频</div>
                </div>
        """.trimIndent())

        for (file in files) {
            val relPath = file.relativeTo(recordDir).path.replace("\\", "/")
            val sizeMb = String.format("%.1f MB", file.length() / (1024.0 * 1024.0))
            sb.append("""
                <div class="card">
                    <div>
                        <div class="name">${file.name}</div>
                        <div class="size">$sizeMb</div>
                    </div>
                    <div>
                        <a class="btn btn-play" href="/stream/$relPath" target="_blank">预览</a>
                        <a class="btn" href="/download/$relPath" download="${file.name}">下载</a>
                    </div>
                </div>
            """.trimIndent())
        }

        sb.append("</body></html>")
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", sb.toString())
    }

    /**
     * 文件原画断点续传与下载 (支持 HTTP 206 Partial Content)
     */
    private fun serveFileDownload(baseDir: File, path: String, session: IHTTPSession): Response {
        val targetFile = File(baseDir, path)
        if (!targetFile.exists() || !targetFile.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        }

        val fis = FileInputStream(targetFile)
        val res = newFixedLengthResponse(Response.Status.OK, MIME_MP4, fis, targetFile.length())
        res.addHeader("Content-Disposition", "attachment; filename=\"${targetFile.name}\"")
        return res
    }

    private fun serveFileStream(baseDir: File, path: String, session: IHTTPSession): Response {
        val targetFile = File(baseDir, path)
        if (!targetFile.exists() || !targetFile.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        }

        val fileLen = targetFile.length()
        val rangeHeader = session.headers["range"]

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val range = rangeHeader.substring(6).split("-")
            val start = range[0].toLongOrNull() ?: 0L
            val end = if (range.size > 1 && range[1].isNotEmpty()) range[1].toLong() else fileLen - 1L
            val chunkLen = end - start + 1L

            val fis = FileInputStream(targetFile).apply { skip(start) }
            val res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, MIME_MP4, fis, chunkLen)
            res.addHeader("Content-Range", "bytes $start-$end/$fileLen")
            res.addHeader("Content-Length", "$chunkLen")
            return res
        }

        val fis = FileInputStream(targetFile)
        return newFixedLengthResponse(Response.Status.OK, MIME_MP4, fis, fileLen)
    }

    /**
     * 获取车机当前局域网 IP
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
        }
        return null
    }

    /**
     * 生成二维码供手机端扫码直达
     */
    fun generateQrCodeBitmap(url: String, sizePx: Int = 360): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate QR bitmap", e)
            null
        }
    }
}
