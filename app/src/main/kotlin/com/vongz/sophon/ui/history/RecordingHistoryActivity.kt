package com.vongz.sophon.ui.history

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
import com.vongz.sophon.R
import com.vongz.sophon.SophonApp
import com.vongz.sophon.domain.model.RecordCategory
import com.vongz.sophon.ui.playback.QuadSyncPlayerActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车载历史录像文件资料库 (RecordingHistoryActivity)
 *
 * 【分日管理与锁档操作】
 * 1. 扫描外置 U 盘 Normal/ 与 Event/ 目录；
 * 2. 支持全部、常规循环、加锁事件多条件筛选；
 * 3. 支持一键手动加锁（移动至 Event/ 目录永久保护免覆盖）与解锁；
 * 4. 点击直达四路同步高能标记播放器。
 */
class RecordingHistoryActivity : AppCompatActivity() {

    data class RecordingItem(
        val file: File,
        val category: RecordCategory,
        val timestampMs: Long,
        val formattedTime: String,
        val sizeMb: String
    )

    private lateinit var rvRecordings: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnFilterAll: TextView
    private lateinit var btnFilterNormal: TextView
    private lateinit var btnFilterEvent: TextView

    private val allRecordings = mutableListOf<RecordingItem>()
    private val displayedRecordings = mutableListOf<RecordingItem>()
    private var currentFilter = 0 // 0: ALL, 1: NORMAL, 2: EVENT

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
    }

    private fun initViews() {
        rvRecordings = findViewById(R.id.rv_recording_list)
        tvEmpty = findViewById(R.id.tv_history_empty)
        btnFilterAll = findViewById(R.id.btn_filter_all)
        btnFilterNormal = findViewById(R.id.btn_filter_normal)
        btnFilterEvent = findViewById(R.id.btn_filter_event)

        findViewById<TextView>(R.id.btn_history_back).setOnClickListener {
            finish()
        }

        rvRecordings.layoutManager = LinearLayoutManager(this)
        rvRecordings.adapter = adapter

        btnFilterAll.setOnClickListener { setFilter(0) }
        btnFilterNormal.setOnClickListener { setFilter(1) }
        btnFilterEvent.setOnClickListener { setFilter(2) }
    }

    private fun setFilter(filterType: Int) {
        currentFilter = filterType
        btnFilterAll.setBackgroundTintList(if (filterType == 0) getColorStateList(R.color.flyme_cyan) else null)
        btnFilterAll.setTextColor(if (filterType == 0) getColor(R.color.bg_cockpit_dark) else getColor(R.color.text_secondary))

        btnFilterNormal.setBackgroundTintList(if (filterType == 1) getColorStateList(R.color.flyme_cyan) else null)
        btnFilterNormal.setTextColor(if (filterType == 1) getColor(R.color.bg_cockpit_dark) else getColor(R.color.text_secondary))

        btnFilterEvent.setBackgroundTintList(if (filterType == 2) getColorStateList(R.color.flyme_cyan) else null)
        btnFilterEvent.setTextColor(if (filterType == 2) getColor(R.color.bg_cockpit_dark) else getColor(R.color.text_secondary))

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

        val parserFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val displayFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        val collectFiles = { dir: File, cat: RecordCategory ->
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

        collectFiles(normalDir, RecordCategory.NORMAL)
        collectFiles(eventDir, RecordCategory.EVENT)

        allRecordings.sortByDescending { it.timestampMs }
        applyCurrentFilter()
    }

    private fun applyCurrentFilter() {
        displayedRecordings.clear()
        when (currentFilter) {
            1 -> displayedRecordings.addAll(allRecordings.filter { it.category == RecordCategory.NORMAL })
            2 -> displayedRecordings.addAll(allRecordings.filter { it.category == RecordCategory.EVENT })
            else -> displayedRecordings.addAll(allRecordings)
        }

        tvEmpty.visibility = if (displayedRecordings.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun toggleLock(item: RecordingItem) {
        val usbRoot = SophonApp.storageVault.currentUsbRoot.value ?: return
        val baseDir = File(usbRoot, "SophonRecord")
        val targetCategory = if (item.category == RecordCategory.EVENT) RecordCategory.NORMAL else RecordCategory.EVENT
        val targetDir = File(baseDir, targetCategory.dirName).apply { mkdirs() }
        val targetFile = File(targetDir, item.file.name)

        if (item.file.renameTo(targetFile)) {
            val actionText = if (targetCategory == RecordCategory.EVENT) "已加锁保护" else "已取消加锁"
            Toast.makeText(this, actionText, Toast.LENGTH_SHORT).show()
            loadRecordingsFromUsb()
        } else {
            Toast.makeText(this, "文件操作失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteRecording(item: RecordingItem) {
        if (item.file.delete()) {
            Toast.makeText(this, "视频切片已删除", Toast.LENGTH_SHORT).show()
            loadRecordingsFromUsb()
        } else {
            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
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
            holder.tvDetails.text = "切片时长: 01:00  ·  大小: ${item.sizeMb}  ·  四路全景"

            if (item.category == RecordCategory.EVENT) {
                holder.tvBadgeLocked.visibility = View.VISIBLE
                holder.btnLock.text = "🔓 解锁"
                holder.btnLock.setTextColor(getColor(R.color.text_secondary))
            } else {
                holder.tvBadgeLocked.visibility = View.GONE
                holder.btnLock.text = "🔒 加锁"
                holder.btnLock.setTextColor(getColor(R.color.accent_warning))
            }

            holder.itemView.setOnClickListener {
                val intent = Intent(this@RecordingHistoryActivity, QuadSyncPlayerActivity::class.java).apply {
                    putExtra(QuadSyncPlayerActivity.EXTRA_VIDEO_PATH, item.file.absolutePath)
                }
                startActivity(intent)
            }

            holder.btnLock.setOnClickListener {
                toggleLock(item)
            }

            holder.btnDelete.setOnClickListener {
                deleteRecording(item)
            }
        }

        override fun getItemCount(): Int = displayedRecordings.size
    }

    private class RecordingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivThumb: ImageView = itemView.findViewById(R.id.iv_record_thumb)
        val tvBadgeLocked: TextView = itemView.findViewById(R.id.tv_badge_locked)
        val tvTime: TextView = itemView.findViewById(R.id.tv_record_time)
        val tvDetails: TextView = itemView.findViewById(R.id.tv_record_details)
        val btnLock: TextView = itemView.findViewById(R.id.btn_record_lock_toggle)
        val btnDelete: TextView = itemView.findViewById(R.id.btn_record_delete)
    }
}
