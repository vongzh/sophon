package com.vongzh.sophon.ui.history

import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vongzh.sophon.R
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.domain.model.RecordCategory
import com.vongzh.sophon.ui.playback.QuadSyncPlayerActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车载历史录像文件资料库 (RecordingHistoryActivity)
 *
 * 【分日管理、防误删缓冲池与锁档操作】
 * 1. 扫描外置 U 盘 Normal/、Event/ 及 .Trash/ 待删回收站目录；
 * 2. 支持全部、常规循环、加锁事件、待删回收站多维度筛选；
 * 3. 待删回收站支持一键无损还原到 Normal/ 或彻底物理清除；
 * 4. 点击直达四路同步高能标记播放器。
 */
class RecordingHistoryActivity : AppCompatActivity() {

    enum class ViewCategory {
        NORMAL,
        EVENT,
        TRASH
    }

    data class RecordingItem(
        val file: File,
        val category: ViewCategory,
        val timestampMs: Long,
        val formattedTime: String,
        val sizeMb: String
    )

    private lateinit var rvRecordings: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnFilterAll: TextView
    private lateinit var btnFilterNormal: TextView
    private lateinit var btnFilterEvent: TextView
    private lateinit var btnFilterTrash: TextView

    private val allRecordings = mutableListOf<RecordingItem>()
    private val displayedRecordings = mutableListOf<RecordingItem>()
    private var currentFilter = 0 // 0: ALL, 1: NORMAL, 2: EVENT, 3: TRASH

    private val adapter = RecordingAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording_history)

        initViews()
        val initialFilter = intent.getIntExtra(EXTRA_FILTER, 0)
        setFilter(initialFilter)
        loadRecordingsFromUsb()
    }

    companion object {
        const val EXTRA_FILTER = "extra_filter"
        const val FILTER_ALL = 0
        const val FILTER_NORMAL = 1
        const val FILTER_EVENT = 2
        const val FILTER_TRASH = 3
    }

    private fun initViews() {
        rvRecordings = findViewById(R.id.rv_recording_list)
        tvEmpty = findViewById(R.id.tv_history_empty)
        btnFilterAll = findViewById(R.id.btn_filter_all)
        btnFilterNormal = findViewById(R.id.btn_filter_normal)
        btnFilterEvent = findViewById(R.id.btn_filter_event)
        btnFilterTrash = findViewById(R.id.btn_filter_trash)

        findViewById<TextView>(R.id.btn_history_back).setOnClickListener {
            finish()
        }

        rvRecordings.layoutManager = LinearLayoutManager(this)
        rvRecordings.adapter = adapter

        btnFilterAll.setOnClickListener { setFilter(0) }
        btnFilterNormal.setOnClickListener { setFilter(1) }
        btnFilterEvent.setOnClickListener { setFilter(2) }
        btnFilterTrash.setOnClickListener { setFilter(3) }
    }

    private fun setFilter(filterType: Int) {
        currentFilter = filterType
        btnFilterAll.setBackgroundTintList(if (filterType == 0) getColorStateList(R.color.flyme_cyan) else null)
        btnFilterAll.setTextColor(if (filterType == 0) getColor(R.color.bg_cockpit_dark) else getColor(R.color.text_secondary))

        btnFilterNormal.setBackgroundTintList(if (filterType == 1) getColorStateList(R.color.flyme_cyan) else null)
        btnFilterNormal.setTextColor(if (filterType == 1) getColor(R.color.bg_cockpit_dark) else getColor(R.color.text_secondary))

        btnFilterEvent.setBackgroundTintList(if (filterType == 2) getColorStateList(R.color.flyme_cyan) else null)
        btnFilterEvent.setTextColor(if (filterType == 2) getColor(R.color.bg_cockpit_dark) else getColor(R.color.text_secondary))

        btnFilterTrash.setBackgroundTintList(if (filterType == 3) getColorStateList(R.color.flyme_cyan) else null)
        btnFilterTrash.setTextColor(if (filterType == 3) getColor(R.color.bg_cockpit_dark) else getColor(R.color.text_secondary))

        applyCurrentFilter()
    }

    private fun loadRecordingsFromUsb() {
        allRecordings.clear()
        val usbRoot = SophonApp.storageVault.currentUsbRoot.value
        if (usbRoot == null || !SophonApp.storageVault.isUsbMounted.value) {
            tvEmpty.visibility = View.VISIBLE
            return
        }

        val baseDir = File(usbRoot, "SophonRecord")
        val normalDir = File(baseDir, RecordCategory.NORMAL.dirName)
        val eventDir = File(baseDir, RecordCategory.EVENT.dirName)
        val trashDir = File(baseDir, ".Trash")

        val parserFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val displayFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        val collectFiles = { dir: File, cat: ViewCategory ->
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }?.forEach { file ->
                    val timePart = file.name.substringBefore("_ALL").substringBefore(".mp4")
                    val timeMs = try {
                        parserFormat.parse(timePart)?.time ?: file.lastModified()
                    } catch (e: Exception) {
                        file.lastModified()
                    }
                    val sizeMb = String.format("%.1f MB", file.length() / (1024.0 * 1024.0))
                    allRecordings.add(
                        RecordingItem(
                            file = file,
                            category = cat,
                            timestampMs = timeMs,
                            formattedTime = displayFormat.format(Date(timeMs)),
                            sizeMb = sizeMb
                        )
                    )
                }
            }
        }

        collectFiles(normalDir, ViewCategory.NORMAL)
        collectFiles(eventDir, ViewCategory.EVENT)
        collectFiles(trashDir, ViewCategory.TRASH)

        allRecordings.sortByDescending { it.timestampMs }
        applyCurrentFilter()
    }

    private fun applyCurrentFilter() {
        displayedRecordings.clear()
        when (currentFilter) {
            1 -> displayedRecordings.addAll(allRecordings.filter { it.category == ViewCategory.NORMAL })
            2 -> displayedRecordings.addAll(allRecordings.filter { it.category == ViewCategory.EVENT })
            3 -> displayedRecordings.addAll(allRecordings.filter { it.category == ViewCategory.TRASH })
            else -> displayedRecordings.addAll(allRecordings.filter { it.category != ViewCategory.TRASH })
        }

        tvEmpty.visibility = if (displayedRecordings.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun toggleLock(item: RecordingItem) {
        val usbRoot = SophonApp.storageVault.currentUsbRoot.value ?: return
        val baseDir = File(usbRoot, "SophonRecord")
        val targetDir = if (item.category == ViewCategory.EVENT) {
            File(baseDir, RecordCategory.NORMAL.dirName)
        } else {
            File(baseDir, RecordCategory.EVENT.dirName)
        }.apply { mkdirs() }

        val targetFile = File(targetDir, item.file.name)

        if (item.file.renameTo(targetFile)) {
            val actionText = if (item.category == ViewCategory.EVENT) "已取消加锁" else "已加锁保护"
            Toast.makeText(this, actionText, Toast.LENGTH_SHORT).show()
            loadRecordingsFromUsb()
        } else {
            Toast.makeText(this, "文件操作失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreFromTrash(item: RecordingItem) {
        val usbRoot = SophonApp.storageVault.currentUsbRoot.value ?: return
        val baseDir = File(usbRoot, "SophonRecord")
        val normalDir = File(baseDir, RecordCategory.NORMAL.dirName).apply { mkdirs() }
        val targetFile = File(normalDir, item.file.name)

        if (item.file.renameTo(targetFile)) {
            Toast.makeText(this, "♻ 视频已成功从回收站还原到常规录像", Toast.LENGTH_SHORT).show()
            loadRecordingsFromUsb()
        } else {
            Toast.makeText(this, "还原失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteRecording(item: RecordingItem) {
        if (item.category == ViewCategory.TRASH) {
            // 在回收站中彻底抹除
            if (item.file.delete()) {
                Toast.makeText(this, "视频切片已彻底删除", Toast.LENGTH_SHORT).show()
                loadRecordingsFromUsb()
            } else {
                Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            // 普通列表中删除，先移入回收站缓冲池提供后悔药
            val usbRoot = SophonApp.storageVault.currentUsbRoot.value ?: return
            val trashDir = File(File(usbRoot, "SophonRecord"), ".Trash").apply { mkdirs() }
            val targetFile = File(trashDir, item.file.name)
            if (item.file.renameTo(targetFile)) {
                Toast.makeText(this, "已移入待删回收站 (可在回收站随时还原)", Toast.LENGTH_SHORT).show()
                loadRecordingsFromUsb()
            } else if (item.file.delete()) {
                Toast.makeText(this, "视频切片已删除", Toast.LENGTH_SHORT).show()
                loadRecordingsFromUsb()
            }
        }
    }

    private inner class RecordingAdapter : RecyclerView.Adapter<RecordingViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recording_card, parent, false)
            return RecordingViewHolder(view)
        }

        override fun onBindViewHolder(holder: RecordingViewHolder, position: Int) {
            val item = displayedRecordings[position]
            holder.tvTime.text = item.formattedTime

            when (item.category) {
                ViewCategory.EVENT -> {
                    holder.tvDetails.text = "切片时长: 01:00  ·  大小: ${item.sizeMb}  ·  🔒 加锁保护"
                    holder.tvBadgeLocked.visibility = View.VISIBLE
                    holder.tvBadgeLocked.text = "🔒 已加锁"
                    holder.btnLock.text = "🔓 解锁"
                    holder.btnLock.setTextColor(getColor(R.color.text_secondary))
                    holder.btnLock.setOnClickListener { toggleLock(item) }
                    holder.btnDelete.text = "删除"
                    holder.btnDelete.setOnClickListener { deleteRecording(item) }
                }
                ViewCategory.TRASH -> {
                    holder.tvDetails.text = "待删缓冲池  ·  大小: ${item.sizeMb}  ·  可还原"
                    holder.tvBadgeLocked.visibility = View.VISIBLE
                    holder.tvBadgeLocked.text = "🗑 待清理"
                    holder.btnLock.text = "♻ 还原"
                    holder.btnLock.setTextColor(getColor(R.color.flyme_cyan))
                    holder.btnLock.setOnClickListener { restoreFromTrash(item) }
                    holder.btnDelete.text = "彻底清除"
                    holder.btnDelete.setOnClickListener { deleteRecording(item) }
                }
                ViewCategory.NORMAL -> {
                    holder.tvDetails.text = "切片时长: 01:00  ·  大小: ${item.sizeMb}  ·  四路全景"
                    holder.tvBadgeLocked.visibility = View.GONE
                    holder.btnLock.text = "🔒 加锁"
                    holder.btnLock.setTextColor(getColor(R.color.accent_warning))
                    holder.btnLock.setOnClickListener { toggleLock(item) }
                    holder.btnDelete.text = "删除"
                    holder.btnDelete.setOnClickListener { deleteRecording(item) }
                }
            }

            holder.itemView.setOnClickListener {
                val intent = Intent(this@RecordingHistoryActivity, QuadSyncPlayerActivity::class.java).apply {
                    putExtra(QuadSyncPlayerActivity.EXTRA_VIDEO_PATH, item.file.absolutePath)
                }
                startActivity(intent)
            }
        }

        override fun getItemCount(): Int = displayedRecordings.size
    }

    private class RecordingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTime: TextView = itemView.findViewById(R.id.tv_record_time)
        val tvDetails: TextView = itemView.findViewById(R.id.tv_record_details)
        val tvBadgeLocked: TextView = itemView.findViewById(R.id.tv_badge_locked)
        val btnLock: TextView = itemView.findViewById(R.id.btn_record_lock_toggle)
        val btnDelete: TextView = itemView.findViewById(R.id.btn_record_delete)
        val ivThumb: ImageView = itemView.findViewById(R.id.iv_record_thumb)
    }
}
