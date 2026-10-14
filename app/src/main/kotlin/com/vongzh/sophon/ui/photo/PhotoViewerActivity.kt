package com.vongzh.sophon.ui.photo

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vongzh.sophon.R
import com.vongzh.sophon.SophonApp
import java.io.File

/**
 * 车载相册抓拍大图查看器 (PhotoViewerActivity)
 *
 * 【车机大屏高清呈现】
 * 1. 扫描外置 U 盘 Snapshot/ 目录下的所有高清照片；
 * 2. 支持大屏左右滑动切换与一键安全删除；
 * 3. 严格只读外置存储，绝不向内置闪存做任何多余缓存拷贝。
 */
class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var ivDisplay: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvIndex: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var btnDelete: TextView

    private val photoFiles = mutableListOf<File>()
    private var currentIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

        initViews()
        loadPhotosFromUsb()
    }

    private fun initViews() {
        ivDisplay = findViewById(R.id.iv_photo_display)
        tvTitle = findViewById(R.id.tv_photo_title)
        tvIndex = findViewById(R.id.tv_photo_index)
        tvEmpty = findViewById(R.id.tv_empty_hint)
        btnPrev = findViewById(R.id.btn_photo_prev)
        btnNext = findViewById(R.id.btn_photo_next)
        btnDelete = findViewById(R.id.btn_photo_delete)

        findViewById<TextView>(R.id.btn_photo_back).setOnClickListener {
            finish()
        }

        btnPrev.setOnClickListener {
            if (currentIndex > 0) {
                currentIndex--
                displayCurrentPhoto()
            }
        }

        btnNext.setOnClickListener {
            if (currentIndex < photoFiles.size - 1) {
                currentIndex++
                displayCurrentPhoto()
            }
        }

        btnDelete.setOnClickListener {
            deleteCurrentPhoto()
        }
    }

    private fun loadPhotosFromUsb() {
        val usbRoot = SophonApp.storageVault.currentUsbRoot.value
        photoFiles.clear()

        if (usbRoot != null && SophonApp.storageVault.isUsbMounted.value) {
            val snapshotDir = File(File(usbRoot, "SophonRecord"), "Snapshot")
            if (snapshotDir.exists() && snapshotDir.isDirectory) {
                val files = snapshotDir.listFiles()
                    ?.filter { it.isFile && (it.name.endsWith(".jpg") || it.name.endsWith(".png")) }
                    ?.sortedByDescending { it.lastModified() }
                if (files != null) {
                    photoFiles.addAll(files)
                }
            }
        }

        if (photoFiles.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            ivDisplay.visibility = View.GONE
            btnPrev.visibility = View.GONE
            btnNext.visibility = View.GONE
            btnDelete.visibility = View.GONE
            tvIndex.text = "(0 / 0)"
        } else {
            tvEmpty.visibility = View.GONE
            ivDisplay.visibility = View.VISIBLE
            currentIndex = 0
            displayCurrentPhoto()
        }
    }

    private fun displayCurrentPhoto() {
        if (photoFiles.isEmpty()) return

        val file = photoFiles[currentIndex]
        tvTitle.text = file.name
        tvIndex.text = "(${currentIndex + 1} / ${photoFiles.size})"

        btnPrev.visibility = if (currentIndex > 0) View.VISIBLE else View.INVISIBLE
        btnNext.visibility = if (currentIndex < photoFiles.size - 1) View.VISIBLE else View.INVISIBLE
        btnDelete.visibility = View.VISIBLE

        try {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            ivDisplay.setImageBitmap(bmp)
        } catch (e: Exception) {
            Toast.makeText(this, "图片解析失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteCurrentPhoto() {
        if (photoFiles.isEmpty()) return

        val file = photoFiles[currentIndex]
        if (file.delete()) {
            Toast.makeText(this, "照片已删除", Toast.LENGTH_SHORT).show()
            photoFiles.removeAt(currentIndex)
            if (currentIndex >= photoFiles.size) {
                currentIndex = maxOf(0, photoFiles.size - 1)
            }
            if (photoFiles.isEmpty()) {
                loadPhotosFromUsb()
            } else {
                displayCurrentPhoto()
            }
        } else {
            Toast.makeText(this, "删除失败，存储可能处于只读状态", Toast.LENGTH_SHORT).show()
        }
    }
}
