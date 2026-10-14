package com.vongzh.sophon.ui.history

import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vongzh.sophon.R
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.domain.model.RecordCategory
import com.vongzh.sophon.ui.playback.QuadSyncPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车载历史录像文件资料库 (RecordingHistoryActivity) · 设计稿 S3 规范
 *
 * 【双栏架构：左侧片段列表 + 右侧 392dp 详情与即时预览面板】
 * 1. 扫描外置 U 盘 Normal/、Event/ 及 .Trash/ 待删回收站目录；
 * 2. 实时刷新列表，点击选中项同步驱动右侧 222dp 视频预览窗口与详细规格参数；
 * 3. 联动右侧 4 联按钮（播放、加锁/解锁、删除/彻底清除）；
 * 4. 严格遵循 "No USB, No Write" 及 1280x800 车机尺度规范。
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
        val sizeMb: String,
        val sizeBytes: Long
    )

    private lateinit var rvRecordings: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvHistoryStats: TextView
    private lateinit var btnFilterAll: TextView
    private lateinit var btnFilterNormal: TextView
    private lateinit var btnFilterEvent: TextView
    private lateinit var btnFilterTrash: TextView

    // 右侧 392dp 详情与预览组件
    private lateinit var layoutDetailPanel: View
    private lateinit var ivDetailPreview: ImageView
    private lateinit var tvDetailCameraTag: TextView
    private lateinit var tvDetailTimestamp: TextView
    private lateinit var tvDetailDurationBadge: TextView
    private lateinit var btnPreviewPlayCenter: FrameLayout
    private lateinit var tvDetailTitle: TextView
    private lateinit var tvDetailTriggerReason: TextView
    private lateinit var tvSpecResolution: TextView
    private lateinit var tvSpecCodec: TextView
    private lateinit var tvSpecDuration: TextView
    private lateinit var tvSpecSize: TextView
    private lateinit var tvSpecLocation: TextView
    private lateinit var btnActionPlay: TextView
    private lateinit var btnActionLock: TextView
    private lateinit var btnActionDelete: TextView

    private val allRecordings = mutableListOf<RecordingItem>()
    private val displayedRecordings = mutableListOf<RecordingItem>()
    private var currentFilter = 0 // 0: ALL, 1: NORMAL, 2: EVENT, 3: TRASH
    private var selectedItem: RecordingItem? = null

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
        tvHistoryStats = findViewById(R.id.tv_history_stats)
        btnFilterAll = findViewById(R.id.btn_filter_all)
        btnFilterNormal = findViewById(R.id.btn_filter_normal)
        btnFilterEvent = findViewById(R.id.btn_filter_event)
        btnFilterTrash = findViewById(R.id.btn_filter_trash)

        // 右侧面板
        layoutDetailPanel = findViewById(R.id.layout_detail_panel)
        ivDetailPreview = findViewById(R.id.iv_detail_preview)
        tvDetailCameraTag = findViewById(R.id.tv_detail_camera_tag)
        tvDetailTimestamp = findViewById(R.id.tv_detail_timestamp)
        tvDetailDurationBadge = findViewById(R.id.tv_detail_duration_badge)
        btnPreviewPlayCenter = findViewById(R.id.btn_preview_play_center)
        tvDetailTitle = findViewById(R.id.tv_detail_title)
        tvDetailTriggerReason = findViewById(R.id.tv_detail_trigger_reason)
        tvSpecResolution = findViewById(R.id.tv_spec_resolution)
        tvSpecCodec = findViewById(R.id.tv_spec_codec)
        tvSpecDuration = findViewById(R.id.tv_spec_duration)
        tvSpecSize = findViewById(R.id.tv_spec_size)
        tvSpecLocation = findViewById(R.id.tv_spec_location)
        btnActionPlay = findViewById(R.id.btn_action_play)
        btnActionLock = findViewById(R.id.btn_action_lock)
        btnActionDelete = findViewById(R.id.btn_action_delete)

        findViewById<TextView>(R.id.btn_history_back).setOnClickListener {
            finish()
        }

        rvRecordings.layoutManager = LinearLayoutManager(this)
        rvRecordings.adapter = adapter

        btnFilterAll.setOnClickListener { setFilter(0) }
        btnFilterNormal.setOnClickListener { setFilter(1) }
        btnFilterEvent.setOnClickListener { setFilter(2) }
        btnFilterTrash.setOnClickListener { setFilter(3) }

        // 右侧 4 联按钮操作监听
        btnActionPlay.setOnClickListener {
            selectedItem?.let { playRecording(it) }
        }
        btnPreviewPlayCenter.setOnClickListener {
            selectedItem?.let { playRecording(it) }
        }
        btnActionLock.setOnClickListener {
            selectedItem?.let { toggleLock(it) }
        }
        btnActionDelete.setOnClickListener {
            selectedItem?.let { deleteRecording(it) }
        }
    }

    private fun setFilter(filterType: Int) {
        currentFilter = filterType
        val activeBg = getColorStateList(R.color.sp_acbg)
        val activeText = getColor(R.color.sp_ac)
        val inactiveText = getColor(R.color.sp_tx2)

        btnFilterAll.backgroundTintList = if (filterType == 0) activeBg else null
        btnFilterAll.setTextColor(if (filterType == 0) activeText else inactiveText)

        btnFilterNormal.backgroundTintList = if (filterType == 1) activeBg else null
        btnFilterNormal.setTextColor(if (filterType == 1) activeText else inactiveText)

        btnFilterEvent.backgroundTintList = if (filterType == 2) activeBg else null
        btnFilterEvent.setTextColor(if (filterType == 2) activeText else inactiveText)

        btnFilterTrash.backgroundTintList = if (filterType == 3) activeBg else null
        btnFilterTrash.setTextColor(if (filterType == 3) activeText else inactiveText)

        applyCurrentFilter()
    }

    private fun loadRecordingsFromUsb() {
        allRecordings.clear()
        val usbRoot = SophonApp.storageVault.currentUsbRoot.value
        if (usbRoot == null || !SophonApp.storageVault.isUsbMounted.value) {
            tvEmpty.visibility = View.VISIBLE
            updateDetailPanel(null)
            return
        }

        val baseDir = File(usbRoot, "SophonRecord")
        val normalDir = File(baseDir, RecordCategory.NORMAL.dirName)
        val eventDir = File(baseDir, RecordCategory.EVENT.dirName)
        val sentryDir = File(baseDir, RecordCategory.SENTRY.dirName)
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
                    val sizeMb = String.format(Locale.getDefault(), "%.1f MB", file.length() / (1024.0 * 1024.0))
                    allRecordings.add(
                        RecordingItem(
                            file = file,
                            category = cat,
                            timestampMs = timeMs,
                            formattedTime = displayFormat.format(Date(timeMs)),
                            sizeMb = sizeMb,
                            sizeBytes = file.length()
                        )
                    )
                }
            }
        }

        collectFiles(normalDir, ViewCategory.NORMAL)
        collectFiles(eventDir, ViewCategory.EVENT)
        collectFiles(sentryDir, ViewCategory.EVENT)
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

        // 刷新页头统计文字 (S3 规范: 今日 X 段 · 占用 XX.X GB)
        val totalBytes = displayedRecordings.sumOf { it.sizeBytes }
        val totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0)
        tvHistoryStats.text = String.format(
            Locale.getDefault(),
            "共 %d 段 · 占用 %.1f GB · 循环覆盖已开启",
            displayedRecordings.size,
            totalGb
        )

        // 默认选中第一项或保持选中
        if (displayedRecordings.isNotEmpty()) {
            if (selectedItem == null || !displayedRecordings.contains(selectedItem)) {
                updateDetailPanel(displayedRecordings[0])
            }
        } else {
            updateDetailPanel(null)
        }
    }

    /**
     * 响应式刷新右侧 392dp 详情面板
     */
    private fun updateDetailPanel(item: RecordingItem?) {
        selectedItem = item
        if (item == null) {
            layoutDetailPanel.alpha = 0.4f
            tvDetailTitle.text = "暂无选中视频"
            tvDetailTriggerReason.text = "--"
            tvSpecResolution.text = "--"
            tvSpecCodec.text = "--"
            tvSpecDuration.text = "--"
            tvSpecSize.text = "--"
            tvSpecLocation.text = "--"
            ivDetailPreview.setImageDrawable(null)
            return
        }

        layoutDetailPanel.alpha = 1.0f
        val timeDisplay = item.formattedTime.substringAfter(" ")
        tvDetailTitle.text = "四路合成 · ${item.file.name.substringBefore(".mp4")}"
        tvDetailTimestamp.text = timeDisplay
        tvSpecResolution.text = "1920 × 1080 · 30fps"
        tvSpecCodec.text = "H.264 · 10 Mbps"
        tvSpecDuration.text = "01:00"
        tvSpecSize.text = item.sizeMb
        tvSpecLocation.text = item.file.parentFile?.name ?: "U盘根目录"

        when (item.category) {
            ViewCategory.EVENT -> {
                tvDetailTriggerReason.text = "触发方式 · 碰撞加锁或手动标记"
                tvDetailCameraTag.text = "🔒 加锁事件"
                btnActionLock.text = "解锁"
                btnActionLock.setTextColor(getColor(R.color.sp_tx2))
                btnActionDelete.text = "删除"
            }
            ViewCategory.TRASH -> {
                tvDetailTriggerReason.text = "触发方式 · 待删回收站缓冲池"
                tvDetailCameraTag.text = "🗑 待清理"
                btnActionLock.text = "还原"
                btnActionLock.setTextColor(getColor(R.color.sp_ac))
                btnActionDelete.text = "彻底清除"
            }
            ViewCategory.NORMAL -> {
                tvDetailTriggerReason.text = "触发方式 · 连续循环录像切片"
                tvDetailCameraTag.text = "全景 · 主摄"
                btnActionLock.text = "加锁"
                btnActionLock.setTextColor(getColor(R.color.sp_wn))
                btnActionDelete.text = "删除"
            }
        }

        // 异步提取视频缩略图渲染到右侧预览
        lifecycleScope.launch(Dispatchers.IO) {
            val thumb = try {
                ThumbnailUtils.createVideoThumbnail(
                    item.file.absolutePath,
                    MediaStore.Images.Thumbnails.MINI_KIND
                )
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (selectedItem == item) {
                    ivDetailPreview.setImageBitmap(thumb)
                }
            }
        }
    }

    private fun playRecording(item: RecordingItem) {
        val intent = Intent(this, QuadSyncPlayerActivity::class.java).apply {
            putExtra(QuadSyncPlayerActivity.EXTRA_VIDEO_PATH, item.file.absolutePath)
        }
        startActivity(intent)
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
            if (item.file.delete()) {
                Toast.makeText(this, "视频切片已彻底删除", Toast.LENGTH_SHORT).show()
                loadRecordingsFromUsb()
            } else {
                Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
            }
        } else {
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

            // 高亮选中态 (Selected Item Highlight)
            val isSelected = (selectedItem == item)
            holder.itemView.setBackgroundResource(
                if (isSelected) R.drawable.sp_bg_nav_item_active else R.drawable.sp_bg_card
            )

            when (item.category) {
                ViewCategory.EVENT -> {
                    holder.tvDetails.text = "时长 01:00  ·  ${item.sizeMb}  ·  🔒 已加锁"
                    holder.tvBadgeLocked.visibility = View.VISIBLE
                    holder.tvBadgeLocked.text = "🔒"
                    holder.tvTagCategory.text = "加锁事件"
                    holder.btnLock.text = "解锁"
                    holder.btnLock.setTextColor(getColor(R.color.sp_tx2))
                    holder.btnLock.setOnClickListener { toggleLock(item) }
                    holder.btnDelete.text = "删除"
                    holder.btnDelete.setOnClickListener { deleteRecording(item) }
                }
                ViewCategory.TRASH -> {
                    holder.tvDetails.text = "待删缓冲池  ·  ${item.sizeMb}  ·  可还原"
                    holder.tvBadgeLocked.visibility = View.VISIBLE
                    holder.tvBadgeLocked.text = "🗑"
                    holder.tvTagCategory.text = "回收站"
                    holder.btnLock.text = "还原"
                    holder.btnLock.setTextColor(getColor(R.color.sp_ac))
                    holder.btnLock.setOnClickListener { restoreFromTrash(item) }
                    holder.btnDelete.text = "清除"
                    holder.btnDelete.setOnClickListener { deleteRecording(item) }
                }
                ViewCategory.NORMAL -> {
                    holder.tvDetails.text = "时长 01:00  ·  ${item.sizeMb}  ·  H.264"
                    holder.tvBadgeLocked.visibility = View.GONE
                    holder.tvTagCategory.text = "常规行车"
                    holder.btnLock.text = "加锁"
                    holder.btnLock.setTextColor(getColor(R.color.sp_wn))
                    holder.btnLock.setOnClickListener { toggleLock(item) }
                    holder.btnDelete.text = "删除"
                    holder.btnDelete.setOnClickListener { deleteRecording(item) }
                }
            }

            holder.itemView.setOnClickListener {
                updateDetailPanel(item)
                notifyDataSetChanged()
            }
        }

        override fun getItemCount(): Int = displayedRecordings.size
    }

    private class RecordingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTime: TextView = itemView.findViewById(R.id.tv_record_time)
        val tvDetails: TextView = itemView.findViewById(R.id.tv_record_details)
        val tvBadgeLocked: TextView = itemView.findViewById(R.id.tv_badge_locked)
        val tvTagCategory: TextView = itemView.findViewById(R.id.tv_tag_category)
        val btnLock: TextView = itemView.findViewById(R.id.btn_record_lock_toggle)
        val btnDelete: TextView = itemView.findViewById(R.id.btn_record_delete)
        val ivThumb: ImageView = itemView.findViewById(R.id.iv_record_thumb)
    }
}
