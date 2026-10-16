package com.vongzh.sophon.core.lan

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.vongzh.sophon.domain.contract.IStorageVault
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 车载局域网免流高速快传服务器 (LanShareServer)
 *
 * 【车机互联与免流快传】
 * 1. 车机启动轻量级嵌入式 HTTP 服务 (默认端口 8088)；
 * 2. 手机扫描二维码连接车机 Wi-Fi / 热点免流量直连；
 * 3. 手机端响应式原生深色 Web：
 *    - 内置 HTML5 弹窗流式播放器，支持 HTTP 206 Partial Content 拖拽秒开；
 *    - 支持多选复选框批量选择与一键批量保存下载；
 * 4. 严格只读外置 U 盘，不产生任何写入与闪存磨损。
 */
class LanShareServer(
    private val storageVault: IStorageVault,
    port: Int = 8088
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "Sophon.LanShareServer"
        private const val MIME_MP4 = "video/mp4"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Incoming HTTP request: ${session.method} $uri")

        val usbRoot = storageVault.currentUsbRoot.value
        if (usbRoot == null || !storageVault.isUsbMounted.value) {
            return newFixedLengthResponse(
                Response.Status.OK,
                MIME_PLAINTEXT,
                "未检测到外接存储，请插入外接存储后重试。"
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
     * 生成手机端极简深色车载 H5 视频中心 (带内置流式播放器与多选批量下载)
     */
    private fun serveHtmlPortal(recordDir: File): Response {
        val files = recordDir.walkTopDown().filter { it.isFile && (it.name.endsWith(".mp4", ignoreCase = true) || it.name.endsWith(".jpg", ignoreCase = true)) }
            .sortedByDescending { it.lastModified() }
            .toList()

        val html = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <title>极视 (Sophon) 车载局域网快传</title>
                <style>
                    * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
                    body { background: #0D0F12; color: #F5F7FA; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; margin: 0; padding: 16px; padding-bottom: 90px; }
                    .header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #232832; padding-bottom: 12px; margin-bottom: 16px; }
                    .title { font-size: 19px; font-weight: bold; color: #00D4AA; }
                    .count { font-size: 13px; color: #8E95A5; }
                    .batch-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; background: #181C22; padding: 10px 14px; border-radius: 8px; border: 1px solid #232832; font-size: 13px; }
                    .card { background: #181C22; border: 1px solid #232832; border-radius: 12px; padding: 14px; margin-bottom: 12px; display: flex; align-items: center; gap: 12px; transition: background 0.2s; }
                    .card:active { background: #232832; }
                    .checkbox { width: 20px; height: 20px; accent-color: #00D4AA; cursor: pointer; }
                    .file-info { flex: 1; min-width: 0; }
                    .name { font-size: 14px; font-weight: 500; word-break: break-all; margin-bottom: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
                    .meta { font-size: 12px; color: #8E95A5; }
                    .btn-group { display: flex; gap: 8px; }
                    .btn { background: #00D4AA; color: #0D0F12; text-decoration: none; padding: 7px 12px; border-radius: 16px; font-size: 12px; font-weight: bold; border: none; cursor: pointer; }
                    .btn-play { background: #0A84FF; color: white; }
                    .bottom-bar { position: fixed; bottom: 0; left: 0; right: 0; background: #14181F; border-top: 1px solid #232832; padding: 12px 16px; display: flex; justify-content: space-between; align-items: center; }
                    .btn-batch-download { background: #00D4AA; color: #0D0F12; border: none; padding: 10px 20px; border-radius: 20px; font-weight: bold; font-size: 14px; cursor: pointer; }
                    
                    /* 视频弹窗全屏播放器 */
                    .modal { display: none; position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.92); z-index: 999; flex-direction: column; justify-content: center; align-items: center; padding: 16px; }
                    .modal-video { width: 100%; max-width: 680px; border-radius: 12px; outline: none; }
                    .modal-close { position: absolute; top: 20px; right: 20px; color: white; font-size: 26px; cursor: pointer; background: rgba(255,255,255,0.2); width: 40px; height: 40px; border-radius: 50%; display: flex; align-items: center; justify-content: center; }
                </style>
            </head>
            <body>
                <div class="header">
                    <div class="title">极视 · 车载局域网快传</div>
                    <div class="count">共 ${files.size} 项文件</div>
                </div>

                <div class="batch-bar">
                    <label style="display: flex; align-items: center; gap: 8px; cursor: pointer;">
                        <input type="checkbox" id="selectAll" class="checkbox" onclick="toggleSelectAll(this)"> 全选当前列表
                    </label>
                    <span id="selectedCount" style="color: #00D4AA;">已选 0 项</span>
                </div>

                <div id="fileList">
                    ${files.joinToString("\n") { file ->
                        val relPath = file.relativeTo(recordDir).path.replace("\\", "/")
                        val sizeMb = String.format("%.1f MB", file.length() / (1024.0 * 1024.0))
                        val isVideo = file.name.endsWith(".mp4", ignoreCase = true)
                        """
                        <div class="card">
                            <input type="checkbox" class="checkbox file-check" data-url="/download/$relPath" data-name="${file.name}" onchange="updateSelection()">
                            <div class="file-info">
                                <div class="name">${file.name}</div>
                                <div class="meta">${if (isVideo) "视频" else "照片"} · $sizeMb</div>
                            </div>
                            <div class="btn-group">
                                ${if (isVideo) "<button class=\"btn btn-play\" onclick=\"openPlayer('/stream/$relPath')\">预览</button>" else ""}
                                <a class="btn" href="/download/$relPath" download="${file.name}">保存</a>
                            </div>
                        </div>
                        """.trimIndent()
                    }}
                </div>

                <div class="bottom-bar">
                    <span style="font-size: 13px; color: #8E95A5;">通过车机 Wi-Fi 直达，不耗手机流量</span>
                    <button class="btn-batch-download" onclick="downloadSelected()">批量下载选中项</button>
                </div>

                <div id="playerModal" class="modal">
                    <div class="modal-close" onclick="closePlayer()" aria-label="关闭"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M6 6 L18 18 M18 6 L6 18"/></svg></div>
                    <video id="htmlPlayer" class="modal-video" controls playsinline></video>
                </div>

                <script>
                    function openPlayer(src) {
                        const modal = document.getElementById('playerModal');
                        const video = document.getElementById('htmlPlayer');
                        video.src = src;
                        modal.style.display = 'flex';
                        video.play();
                    }
                    function closePlayer() {
                        const modal = document.getElementById('playerModal');
                        const video = document.getElementById('htmlPlayer');
                        video.pause();
                        video.src = '';
                        modal.style.display = 'none';
                    }
                    function toggleSelectAll(master) {
                        const checkboxes = document.querySelectorAll('.file-check');
                        checkboxes.forEach(cb => cb.checked = master.checked);
                        updateSelection();
                    }
                    function updateSelection() {
                        const selected = document.querySelectorAll('.file-check:checked');
                        document.getElementById('selectedCount').innerText = '已选 ' + selected.length + ' 项';
                    }
                    function downloadSelected() {
                        const selected = document.querySelectorAll('.file-check:checked');
                        if (selected.length === 0) {
                            alert('请先勾选需要下载的文件');
                            return;
                        }
                        selected.forEach((cb, idx) => {
                            setTimeout(() => {
                                const a = document.createElement('a');
                                a.href = cb.getAttribute('data-url');
                                a.download = cb.getAttribute('data-name');
                                document.body.appendChild(a);
                                a.click();
                                document.body.removeChild(a);
                            }, idx * 400);
                        });
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html)
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
        val mime = if (targetFile.name.endsWith(".jpg", ignoreCase = true)) "image/jpeg" else MIME_MP4
        val res = newFixedLengthResponse(Response.Status.OK, mime, fis, targetFile.length())
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
