package com.vongzh.sophon.ui.history

import android.app.DatePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vongzh.sophon.R
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.domain.model.RecordCategory
import com.vongzh.sophon.ui.playback.QuadSyncPlayerActivity
import com.vongzh.sophon.ui.widget.UiMotionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 车载历史录像文件资料库组件 (RecordingHistoryFragment)
 *
 * 【顶部日期与批量筛选栏 + 左侧智能分类与今日数据卡 + 右侧切片列表与即时预览面板】
 */
class RecordingHistoryFragment : Fragment() {

    /**
     * 分类只保留真实可判的维度。
     *
     * 原实现有 急刹车 / 急加速 / 急转弯 三个分类，靠文件名里含 BRAKE / ACCEL / TURN
     * 判定；但录像文件的真实命名恒为 `{yyyyMMdd_HHmmss}_{F|B|L|R}.mp4`，从不含这些
     * 标记，因此这三个分类永远显示 0 —— 属于假筛选，已移除。
     */
    enum class HistoryCategory {
        ALL,
        EMERGENCY,
        NIGHT,
        TRASH
    }

    data class RecordingItem(
        val file: File,
        val category: HistoryCategory,
        val timestampMs: Long,
        val dateDay: String,
        val formattedTime: String,
        val sizeMb: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val camTag: String,
        val isLocked: Boolean,
        val isNight: Boolean,
        val codecLabel: String,
        val fpsLabel: String
    )

    // 顶部日期与批处理控件
    private lateinit var btnDatePicker: TextView
    private lateinit var btnQuickToday: TextView
    private lateinit var btnQuickYesterday: TextView
    private lateinit var btnQuickAllDate: TextView
    private lateinit var btnBatchManage: TextView
    private lateinit var btnRefresh: View
    private var ivRefreshIcon: ImageView? = null
    private lateinit var tvHistoryStats: TextView

    // 左侧分类项 View 及计数
    private lateinit var catItemAll: LinearLayout
    private lateinit var catItemEmergency: LinearLayout
    private lateinit var catItemNight: LinearLayout
    private lateinit var catItemTrash: LinearLayout

    private lateinit var tvCatAllCount: TextView
    private lateinit var tvCatEmergencyCount: TextView
    private lateinit var tvCatNightCount: TextView
    private lateinit var tvCatTrashCount: TextView

    // 今日数据多维指标卡
    private lateinit var tvKpiTodayClips: TextView
    private lateinit var tvKpiTodayTrend: TextView
    private lateinit var tvKpiEmergencyCount: TextView
    private lateinit var tvKpiEmergencySub: TextView
    private lateinit var tvKpiDurationVal: TextView
    private lateinit var tvKpiDurationSub: TextView
    private lateinit var tvKpiStoragePercent: TextView
    private lateinit var tvKpiTodayStorageBytes: TextView
    private lateinit var tvKpiTodayStorageTrend: TextView
    private lateinit var pbStorageCapacity: ProgressBar
    private lateinit var tvKpiStorageCapacitySub: TextView

    // 中间切片列表
    private lateinit var tvSectionDate: TextView
    private lateinit var rvRecordings: RecyclerView
    private lateinit var tvEmpty: TextView

    // 右侧 360dp 详情与即时预览面板
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

    // 筛选状态
    private val allRecordings = mutableListOf<RecordingItem>()
    private val displayedRecordings = mutableListOf<RecordingItem>()
    private var currentCategory = HistoryCategory.ALL
    private var selectedDateFilter: String? = null // null: 全部, 或 "yyyy-MM-dd"
    private var selectedItem: RecordingItem? = null
    private val thumbCache = android.util.LruCache<String, Bitmap>(50)

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CLIP = 1
    }

    /** 列表行：日期分组头 + 切片，用于按日期组织内容 */
    sealed class Row {
        data class Header(val dateDay: String, val count: Int, val bytes: Long) : Row()
        data class Clip(val item: RecordingItem) : Row()
    }
    private val displayRows = mutableListOf<Row>()
    private val adapter = RecordingAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_recording_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupListeners()
        // 默认选中“今天”
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        selectedDateFilter = todayStr
        btnDatePicker.text = "日期 $todayStr ▾"
        loadRecordingsFromUsb()
    }

    private fun initViews(root: View) {
        btnDatePicker = root.findViewById(R.id.btn_date_picker)
        btnQuickToday = root.findViewById(R.id.btn_quick_today)
        btnQuickYesterday = root.findViewById(R.id.btn_quick_yesterday)
        btnQuickAllDate = root.findViewById(R.id.btn_quick_all_date)
        btnBatchManage = root.findViewById(R.id.btn_history_batch_manage)
        btnRefresh = root.findViewById(R.id.btn_history_refresh)
        ivRefreshIcon = root.findViewById(R.id.iv_history_refresh_icon)
        tvHistoryStats = root.findViewById(R.id.tv_history_stats)

        // 左侧分类项
        catItemAll = root.findViewById(R.id.cat_item_all)
        catItemEmergency = root.findViewById(R.id.cat_item_emergency)
        catItemNight = root.findViewById(R.id.cat_item_night)
        catItemTrash = root.findViewById(R.id.cat_item_trash)

        tvCatAllCount = root.findViewById(R.id.tv_cat_all_count)
        tvCatEmergencyCount = root.findViewById(R.id.tv_cat_emergency_count)
        tvCatNightCount = root.findViewById(R.id.tv_cat_night_count)
        tvCatTrashCount = root.findViewById(R.id.tv_cat_trash_count)

        // 今日数据卡
        tvKpiTodayClips = root.findViewById(R.id.tv_kpi_driving_today_clips)
        tvKpiTodayTrend = root.findViewById(R.id.tv_kpi_driving_today_trend)
        tvKpiEmergencyCount = root.findViewById(R.id.tv_kpi_driving_emergency_count)
        tvKpiEmergencySub = root.findViewById(R.id.tv_kpi_driving_emergency_sub)
        tvKpiDurationVal = root.findViewById(R.id.tv_kpi_driving_duration_val)
        tvKpiDurationSub = root.findViewById(R.id.tv_kpi_driving_duration_sub)
        tvKpiStoragePercent = root.findViewById(R.id.tv_kpi_storage_percent)
        tvKpiTodayStorageBytes = root.findViewById(R.id.tv_kpi_today_storage_bytes)
        tvKpiTodayStorageTrend = root.findViewById(R.id.tv_kpi_today_storage_trend)
        pbStorageCapacity = root.findViewById(R.id.pb_storage_capacity)
        tvKpiStorageCapacitySub = root.findViewById(R.id.tv_kpi_storage_capacity_sub)

        // 中间列表
        tvSectionDate = root.findViewById(R.id.tv_section_date)
        rvRecordings = root.findViewById(R.id.rv_recording_list)
        tvEmpty = root.findViewById(R.id.tv_history_empty)
        rvRecordings.layoutManager = LinearLayoutManager(requireContext())
        rvRecordings.adapter = adapter

        // 右侧详情预览面板
        layoutDetailPanel = root.findViewById(R.id.layout_detail_panel)
        ivDetailPreview = root.findViewById(R.id.iv_detail_preview)
        tvDetailCameraTag = root.findViewById(R.id.tv_detail_camera_tag)
        tvDetailTimestamp = root.findViewById(R.id.tv_detail_timestamp)
        tvDetailDurationBadge = root.findViewById(R.id.tv_detail_duration_badge)
        btnPreviewPlayCenter = root.findViewById(R.id.btn_preview_play_center)
        tvDetailTitle = root.findViewById(R.id.tv_detail_title)
        tvDetailTriggerReason = root.findViewById(R.id.tv_detail_trigger_reason)
        tvSpecResolution = root.findViewById(R.id.tv_spec_resolution)
        tvSpecCodec = root.findViewById(R.id.tv_spec_codec)
        tvSpecDuration = root.findViewById(R.id.tv_spec_duration)
        tvSpecSize = root.findViewById(R.id.tv_spec_size)
        tvSpecLocation = root.findViewById(R.id.tv_spec_location)
        btnActionPlay = root.findViewById(R.id.btn_action_play)
        btnActionLock = root.findViewById(R.id.btn_action_lock)
        btnActionDelete = root.findViewById(R.id.btn_action_delete)
    }

    private fun setupListeners() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yesterdayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)

        btnQuickToday.setOnClickListener {
            selectedDateFilter = todayStr
            btnDatePicker.text = "日期 $todayStr ▾"
            updateDateButtonsUi(0)
            applyFilter()
        }

        btnQuickYesterday.setOnClickListener {
            selectedDateFilter = yesterdayStr
            btnDatePicker.text = "日期 $yesterdayStr ▾"
            updateDateButtonsUi(1)
            applyFilter()
        }

        btnQuickAllDate.setOnClickListener {
            selectedDateFilter = null
            btnDatePicker.text = "全部日期 ▾"
            updateDateButtonsUi(2)
            applyFilter()
        }

        btnDatePicker.setOnClickListener {
            val nowCal = Calendar.getInstance()
            DatePickerDialog(requireContext(), { _, year, month, dayOfMonth ->
                val pickedCal = Calendar.getInstance().apply { set(year, month, dayOfMonth) }
                val pickedStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(pickedCal.time)
                selectedDateFilter = pickedStr
                btnDatePicker.text = "日期 $pickedStr ▾"
                updateDateButtonsUi(-1)
                applyFilter()
            }, nowCal.get(Calendar.YEAR), nowCal.get(Calendar.MONTH), nowCal.get(Calendar.DAY_OF_MONTH)).show()
        }

        btnRefresh.setOnClickListener {
            UiMotionUtils.startRefreshAnimation(btnRefresh, ivRefreshIcon) {
                loadRecordingsFromUsb()
                Toast.makeText(context, "已刷新录像列表", Toast.LENGTH_SHORT).show()
            }
        }

        view?.findViewById<View>(R.id.btn_history_lan_share)?.setOnClickListener {
            activity?.let { act ->
                com.vongzh.sophon.ui.lan.LanShareDialog(act).show()
            }
        }

        btnBatchManage.setOnClickListener { showBatchManageDialog() }

        // 分类点击切换
        val catViews = listOf(
            HistoryCategory.ALL to catItemAll,
            HistoryCategory.EMERGENCY to catItemEmergency,
            HistoryCategory.NIGHT to catItemNight,
            HistoryCategory.TRASH to catItemTrash
        )

        for ((cat, v) in catViews) {
            v.setOnClickListener {
                currentCategory = cat
                updateCategoryUi()
                applyFilter()
            }
        }

        // 右侧详情面板操作
        btnPreviewPlayCenter.setOnClickListener { selectedItem?.let { playRecording(it) } }
        btnActionPlay.setOnClickListener { selectedItem?.let { playRecording(it) } }
        btnActionLock.setOnClickListener { selectedItem?.let { toggleLock(it) } }
        btnActionDelete.setOnClickListener { selectedItem?.let { deleteRecording(it) } }
    }

    private fun updateDateButtonsUi(selectedIndex: Int) {
        btnQuickToday.setBackgroundResource(if (selectedIndex == 0) R.drawable.sp_bg_pill_accent else R.drawable.sp_bg_pill)
        btnQuickToday.setTextColor(requireContext().getColor(if (selectedIndex == 0) R.color.sp_ac else R.color.sp_tx2))

        btnQuickYesterday.setBackgroundResource(if (selectedIndex == 1) R.drawable.sp_bg_pill_accent else R.drawable.sp_bg_pill)
        btnQuickYesterday.setTextColor(requireContext().getColor(if (selectedIndex == 1) R.color.sp_ac else R.color.sp_tx2))

        btnQuickAllDate.setBackgroundResource(if (selectedIndex == 2) R.drawable.sp_bg_pill_accent else R.drawable.sp_bg_pill)
        btnQuickAllDate.setTextColor(requireContext().getColor(if (selectedIndex == 2) R.color.sp_ac else R.color.sp_tx2))
    }

    private fun updateCategoryUi() {
        val catMap = listOf(
            HistoryCategory.ALL to (catItemAll to tvCatAllCount),
            HistoryCategory.EMERGENCY to (catItemEmergency to tvCatEmergencyCount),
            HistoryCategory.NIGHT to (catItemNight to tvCatNightCount),
            HistoryCategory.TRASH to (catItemTrash to tvCatTrashCount)
        )

        for ((cat, pair) in catMap) {
            val (layout, tvCount) = pair
            val isActive = (cat == currentCategory)
            layout.setBackgroundResource(if (isActive) R.drawable.sp_bg_pill_accent else R.drawable.sp_bg_pill)
            val titleTv = layout.getChildAt(0) as? TextView
            titleTv?.setTextColor(requireContext().getColor(if (isActive) R.color.sp_ac else R.color.sp_tx2))
            titleTv?.setTypeface(null, if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tvCount.setTextColor(requireContext().getColor(if (isActive) R.color.sp_ac else R.color.sp_tx3))
            titleTv?.compoundDrawableTintList = ColorStateList.valueOf(
                requireContext().getColor(if (isActive) R.color.sp_ac else R.color.sp_tx2)
            )
        }
    }

    fun loadRecordingsFromUsb() {
        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { scanRecordings() }
            allRecordings.clear()
            allRecordings.addAll(items)
            updateCategoryCounts()
            updateTodayKpiCard()
            applyFilter()
        }
    }

    private fun scanRecordings(): List<RecordingItem> {
        val usbRoot = SophonApp.storageVault.currentUsbRoot.value
        if (usbRoot == null || !SophonApp.storageVault.isUsbMounted.value) return emptyList()

        val baseDir = File(usbRoot, "SophonRecord")
        val normalDir = File(baseDir, RecordCategory.NORMAL.dirName)
        val eventDir = File(baseDir, RecordCategory.EVENT.dirName)
        val sentryDir = File(baseDir, RecordCategory.SENTRY.dirName)
        val trashDir = File(baseDir, ".Trash")

        val result = mutableListOf<RecordingItem>()
        val parserFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val displayFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val hourFormat = SimpleDateFormat("HH", Locale.getDefault())

        val parseFiles = { folder: File, isTrash: Boolean ->
            if (folder.exists() && folder.isDirectory) {
                folder.listFiles()?.filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }?.forEach { f ->
                    val name = f.name
                    val timePart = name.substringBefore("_ALL").substringBefore(".mp4")
                    val timeMs = try {
                        parserFormat.parse(timePart)?.time ?: f.lastModified()
                    } catch (e: Exception) {
                        f.lastModified()
                    }

                    val date = Date(timeMs)
                    val dateDay = dayFormat.format(date)
                    val hour = hourFormat.format(date).toIntOrNull() ?: 12
                    val isNight = (hour >= 19 || hour < 6)

                    val nameUpper = name.uppercase()
                    val isEmergency = nameUpper.contains("LOCK") || nameUpper.contains("EVENT") || nameUpper.contains("CRASH") || folder == eventDir

                    val cat = when {
                        isTrash -> HistoryCategory.TRASH
                        isEmergency -> HistoryCategory.EMERGENCY
                        isNight -> HistoryCategory.NIGHT
                        else -> HistoryCategory.ALL
                    }

                    val camTag = when {
                        nameUpper.endsWith("_F.MP4") -> "前路"
                        nameUpper.endsWith("_B.MP4") -> "后路"
                        nameUpper.endsWith("_L.MP4") -> "左路"
                        nameUpper.endsWith("_R.MP4") -> "右路"
                        else -> "四向"
                    }

                    val sizeBytes = f.length()
                    val sizeMb = String.format(Locale.getDefault(), "%.1f MB", sizeBytes.toDouble() / (1024 * 1024))

                    var durMs = 0L
                    var w = 0
                    var h = 0
                    var codecLabel = "未知"
                    var fpsLabel = ""
                    try {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(f.absolutePath)
                        durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        // 编码格式取自容器真实 MIME，不再硬编码「H.265 / HEVC」
                        val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                        codecLabel = when {
                            mime.isNullOrBlank() -> "未知"
                            mime.contains("hevc", ignoreCase = true) -> "H.265 / HEVC"
                            mime.contains("avc", ignoreCase = true) -> "H.264 / AVC"
                            mime.contains("mp4v", ignoreCase = true) -> "MPEG-4"
                            else -> mime
                        }
                        // 帧率仅在系统能给出时展示，拿不到就不写死 30fps
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                                ?.toFloatOrNull()
                            if (fps != null && fps > 0f) fpsLabel = " · %.0f fps".format(fps)
                        }
                        retriever.release()
                    } catch (e: Exception) {
                        // 元数据不可读时保留 0 / 未知，不做兜底造假
                    }

                    result.add(
                        RecordingItem(
                            file = f,
                            category = cat,
                            timestampMs = timeMs,
                            dateDay = dateDay,
                            formattedTime = displayFormat.format(date),
                            sizeMb = sizeMb,
                            sizeBytes = sizeBytes,
                            durationMs = durMs,
                            width = w,
                            height = h,
                            camTag = camTag,
                            isLocked = isEmergency || folder == eventDir,
                            isNight = isNight,
                            codecLabel = codecLabel,
                            fpsLabel = fpsLabel
                        )
                    )
                }
            }
        }

        parseFiles(normalDir, false)
        parseFiles(eventDir, false)
        parseFiles(sentryDir, false)
        parseFiles(trashDir, true)

        return result.sortedByDescending { it.timestampMs }
    }

    private fun updateCategoryCounts() {
        val nonTrash = allRecordings.filter { it.category != HistoryCategory.TRASH }
        tvCatAllCount.text = nonTrash.size.toString()
        tvCatEmergencyCount.text = nonTrash.count { it.isLocked || it.category == HistoryCategory.EMERGENCY }.toString()
        tvCatNightCount.text = nonTrash.count { it.isNight }.toString()
        tvCatTrashCount.text = allRecordings.count { it.category == HistoryCategory.TRASH }.toString()

        val totalGb = nonTrash.sumOf { it.sizeBytes }.toDouble() / (1024.0 * 1024 * 1024)
        tvHistoryStats.text = "${nonTrash.size} 段 · 占用 ${String.format(Locale.getDefault(), "%.1f", totalGb)} GB · 循环覆盖已开启"
    }

    private fun updateTodayKpiCard() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yesterdayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)

        val todayClips = allRecordings.filter { it.dateDay == todayStr && it.category != HistoryCategory.TRASH }
        val yesterdayClips = allRecordings.filter { it.dateDay == yesterdayStr && it.category != HistoryCategory.TRASH }

        // 1. 录像总数与趋势
        val todayCount = todayClips.size
        val yesterdayCount = yesterdayClips.size
        val diffCount = todayCount - yesterdayCount
        tvKpiTodayClips.text = "$todayCount 段"
        tvKpiTodayTrend.text = when {
            diffCount > 0 -> "较昨日 +$diffCount"
            diffCount < 0 -> "较昨日 $diffCount"
            else -> "较昨日持平"
        }

        val hasTrend1 = diffCount != 0
        tvKpiTodayTrend.setBackgroundResource(if (hasTrend1) R.drawable.sp_bg_pill_accent else R.drawable.sp_bg_pill)
        tvKpiTodayTrend.setTextColor(requireContext().getColor(if (hasTrend1) R.color.sp_ac else R.color.sp_tx2))

        // 2. 紧急加锁
        val todayEmergency = todayClips.count { it.isLocked || it.category == HistoryCategory.EMERGENCY }
        val yesterdayEmergency = yesterdayClips.count { it.isLocked || it.category == HistoryCategory.EMERGENCY }
        val diffEmergency = todayEmergency - yesterdayEmergency
        tvKpiEmergencyCount.text = "$todayEmergency 段"
        tvKpiEmergencySub.text = when {
            diffEmergency > 0 -> "较昨日 +$diffEmergency"
            diffEmergency < 0 -> "较昨日 $diffEmergency"
            else -> "较昨日持平"
        }
        val hasTrend2 = diffEmergency != 0
        tvKpiEmergencySub.setBackgroundResource(if (hasTrend2) R.drawable.sp_bg_pill_accent else R.drawable.sp_bg_pill)
        tvKpiEmergencySub.setTextColor(requireContext().getColor(if (hasTrend2) R.color.sp_wn else R.color.sp_tx2))

        // 3. 录制时长
        val todayDurSec = todayClips.sumOf { it.durationMs } / 1000
        val yesterdayDurSec = yesterdayClips.sumOf { it.durationMs } / 1000
        val diffDurSec = todayDurSec - yesterdayDurSec

        val todayMin = todayDurSec / 60
        val todayHours = todayMin / 60
        tvKpiDurationVal.text = if (todayHours > 0) {
            "${todayHours}h ${todayMin % 60}m"
        } else {
            "${todayMin}m"
        }

        val absDiffSec = kotlin.math.abs(diffDurSec)
        val absDiffMin = absDiffSec / 60
        val absDiffH = absDiffMin / 60
        val diffMinStr = if (absDiffH > 0) "${absDiffH}h ${absDiffMin % 60}m" else "${absDiffMin}m"
        tvKpiDurationSub.text = when {
            diffDurSec > 0 -> "较昨日 +$diffMinStr"
            diffDurSec < 0 -> "较昨日 -$diffMinStr"
            else -> "较昨日持平"
        }
        val hasTrend3 = diffDurSec != 0L
        tvKpiDurationSub.setBackgroundResource(if (hasTrend3) R.drawable.sp_bg_pill_accent else R.drawable.sp_bg_pill)
        tvKpiDurationSub.setTextColor(requireContext().getColor(if (hasTrend3) R.color.sp_ac else R.color.sp_tx2))

        // 4. 外接存储写入量 & 全盘占用监控
        val todayBytes = todayClips.sumOf { it.sizeBytes }
        val yesterdayBytes = yesterdayClips.sumOf { it.sizeBytes }
        val diffBytes = todayBytes - yesterdayBytes

        tvKpiTodayStorageBytes.text = formatByteSize(todayBytes)
        tvKpiTodayStorageTrend.text = when {
            diffBytes > 0 -> "较昨日 +${formatByteSize(diffBytes)}"
            diffBytes < 0 -> "较昨日 -${formatByteSize(kotlin.math.abs(diffBytes))}"
            else -> "较昨日持平"
        }

        val breakdown = try {
            SophonApp.storageVault.queryStorageBreakdown()
        } catch (e: Exception) {
            null
        }

        if (breakdown != null && breakdown.totalBytes > 0) {
            val usedRatio = (breakdown.usedBytes.toDouble() / breakdown.totalBytes.toDouble()).coerceIn(0.0, 1.0)
            val usedPercent = (usedRatio * 100).toInt()
            tvKpiStoragePercent.text = "$usedPercent%"
            tvKpiStoragePercent.setBackgroundResource(R.drawable.sp_bg_pill_accent)
            tvKpiStoragePercent.setTextColor(requireContext().getColor(R.color.sp_ac))
            pbStorageCapacity.progress = usedPercent

            val freeGb = breakdown.freeBytes.toDouble() / (1024.0 * 1024 * 1024)
            val totalGb = breakdown.totalBytes.toDouble() / (1024.0 * 1024 * 1024)
            tvKpiStorageCapacitySub.text = String.format(
                Locale.getDefault(),
                "可用 %.1f GB / 总计 %.0f GB",
                freeGb,
                totalGb
            )
        } else {
            tvKpiStoragePercent.text = "--%"
            tvKpiStoragePercent.setBackgroundResource(R.drawable.sp_bg_pill)
            tvKpiStoragePercent.setTextColor(requireContext().getColor(R.color.sp_tx2))
            pbStorageCapacity.progress = 0
            tvKpiStorageCapacitySub.text = if (SophonApp.storageVault.isUsbMounted.value) {
                "外接存储就绪 · 计算中"
            } else {
                "未检测到外接存储"
            }
        }
    }

    private fun formatByteSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f GB", bytes.toDouble() / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes.toDouble() / (1024.0 * 1024))
            bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes.toDouble() / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun applyFilter() {
        val filtered = allRecordings.filter { item ->
            // 1. 日期过滤
            val matchDate = (selectedDateFilter == null || item.dateDay == selectedDateFilter)
            // 2. 分类过滤
            val matchCat = when (currentCategory) {
                HistoryCategory.ALL -> item.category != HistoryCategory.TRASH
                HistoryCategory.EMERGENCY -> item.isLocked || item.category == HistoryCategory.EMERGENCY
                HistoryCategory.NIGHT -> item.isNight
                HistoryCategory.TRASH -> item.category == HistoryCategory.TRASH
            }
            matchDate && matchCat
        }

        displayedRecordings.clear()
        displayedRecordings.addAll(filtered)
        rebuildRows()

        val dateLabel = when (selectedDateFilter) {
            null -> "全部日期"
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) -> "今天 ($selectedDateFilter)"
            else -> selectedDateFilter
        }
        tvSectionDate.text = "$dateLabel · 录像切片列表 (共 ${displayedRecordings.size} 段)"
        tvEmpty.visibility = if (displayRows.isEmpty()) View.VISIBLE else View.GONE

        // 默认选中第一个
        if (selectedItem == null || !displayedRecordings.contains(selectedItem)) {
            selectedItem = displayedRecordings.firstOrNull()
        }
        selectedItem?.let { updateDetailPanel(it) }

        adapter.notifyDataSetChanged()
    }

    private fun updateDetailPanel(item: RecordingItem) {
        tvDetailTitle.text = item.file.name
        tvDetailCameraTag.text = item.camTag
        tvDetailTimestamp.text = item.formattedTime
        tvDetailDurationBadge.text = formatDuration(item.durationMs)
        tvDetailTriggerReason.text = if (item.isLocked) "关键行车事件已加锁保护 · 禁循环删除" else "日常巡航录制 · 循环覆盖就绪"

        tvSpecResolution.text = if (item.height > 0) "${item.height}P${item.fpsLabel}" else "--"
        tvSpecCodec.text = item.codecLabel
        tvSpecDuration.text = formatDuration(item.durationMs)
        tvSpecSize.text = item.sizeMb
        tvSpecLocation.text = item.file.parent ?: "USB-OTG"

        // 回收站内的切片：加锁位复用为「还原」入口，避免误以为只能粉碎
        if (item.category == HistoryCategory.TRASH) {
            tvDetailTriggerReason.text = "触发方式 · 待删回收站缓冲池"
            tvDetailCameraTag.text = "待清理"
            btnActionLock.text = "还原"
            btnActionLock.setTextColor(requireContext().getColor(R.color.sp_ac))
            btnActionDelete.text = "彻底粉碎"
        } else {
            btnActionLock.text = if (item.isLocked) "取消加锁" else "紧急加锁"
            btnActionLock.setTextColor(requireContext().getColor(R.color.sp_tx))
            btnActionDelete.text = "移入回收站"
        }

        // 异步缩略图
        ivDetailPreview.tag = item.file.absolutePath
        val cached = thumbCache.get(item.file.absolutePath)
        if (cached != null) {
            ivDetailPreview.setImageBitmap(cached)
        } else {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val bmp = getVideoThumbnail(item.file, 480, 270)
                bmp?.let { thumbCache.put(item.file.absolutePath, it) }
                withContext(Dispatchers.Main) {
                    if (ivDetailPreview.tag == item.file.absolutePath) {
                        ivDetailPreview.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun getVideoThumbnail(file: File, width: Int = 320, height: Int = 180): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createVideoThumbnail(file, android.util.Size(width, height), null)
            } else {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun playRecording(item: RecordingItem) {
        val intent = Intent(requireContext(), QuadSyncPlayerActivity::class.java).apply {
            putExtra(QuadSyncPlayerActivity.EXTRA_VIDEO_PATH, item.file.absolutePath)
        }
        startActivity(intent)
    }

    private fun toggleLock(item: RecordingItem) {
        // 回收站切片：此处语义为「还原回常规录像」，不是加锁
        if (item.category == HistoryCategory.TRASH) {
            restoreFromTrash(item)
            return
        }
        val usbRoot = SophonApp.storageVault.currentUsbRoot.value ?: item.file.parentFile?.parentFile ?: return
        val baseDir = File(usbRoot, "SophonRecord")
        if (item.isLocked) {
            val targetDir = File(baseDir, RecordCategory.NORMAL.dirName).apply { mkdirs() }
            val newFile = File(targetDir, item.file.name)
            if (item.file.renameTo(newFile)) {
                Toast.makeText(context, "已解除视频加锁", Toast.LENGTH_SHORT).show()
                loadRecordingsFromUsb()
            }
        } else {
            val targetDir = File(baseDir, RecordCategory.EVENT.dirName).apply { mkdirs() }
            val newFile = File(targetDir, item.file.name)
            if (item.file.renameTo(newFile)) {
                Toast.makeText(context, "视频已成功加锁保护", Toast.LENGTH_SHORT).show()
                loadRecordingsFromUsb()
            }
        }
    }

    private fun restoreFromTrash(item: RecordingItem) {
        val usbRoot = SophonApp.storageVault.currentUsbRoot.value
            ?: item.file.parentFile?.parentFile ?: return
        val baseDir = File(usbRoot, "SophonRecord")
        val normalDir = File(baseDir, RecordCategory.NORMAL.dirName).apply { mkdirs() }
        val targetFile = File(normalDir, item.file.name)
        if (item.file.renameTo(targetFile)) {
            Toast.makeText(context, "视频已从回收站还原到常规录像", Toast.LENGTH_SHORT).show()
            loadRecordingsFromUsb()
        } else {
            Toast.makeText(context, "还原失败，请检查 U 盘读写权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteRecording(item: RecordingItem) {
        if (item.category == HistoryCategory.TRASH) {
            if (item.file.delete()) {
                Toast.makeText(context, "已从 U 盘彻底粉碎抹除", Toast.LENGTH_SHORT).show()
                loadRecordingsFromUsb()
            }
        } else {
            val usbRoot = SophonApp.storageVault.currentUsbRoot.value ?: item.file.parentFile?.parentFile ?: return
            val trashDir = File(File(usbRoot, "SophonRecord"), ".Trash").apply { mkdirs() }
            val newFile = File(trashDir, item.file.name)
            if (item.file.renameTo(newFile)) {
                Toast.makeText(context, "已安全移入待删回收站", Toast.LENGTH_SHORT).show()
                loadRecordingsFromUsb()
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val sec = ms / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", sec / 60, sec % 60)
    }

    /** 按日期把切片组织成分组列表 */
    private fun rebuildRows() {
        displayRows.clear()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = dayFmt.format(Date())
        displayedRecordings.groupBy { it.dateDay }.forEach { (day, items) ->
            displayRows.add(Row.Header(day, items.size, items.sumOf { it.sizeBytes }))
            items.forEach { displayRows.add(Row.Clip(it)) }
        }
        if (displayRows.isNotEmpty()) {
            val first = displayRows.first() as? Row.Header
            if (first != null && first.dateDay == todayStr) {
                displayRows[0] = first.copy(dateDay = "今天 · $todayStr")
            }
        }
    }

    /** 批量管理：对当前筛选结果执行真实的文件操作 */
    private fun showBatchManageDialog() {
        val list = displayedRecordings.toList()
        if (list.isEmpty()) {
            Toast.makeText(context, "当前筛选结果为空，无可批量操作的切片", Toast.LENGTH_SHORT).show()
            return
        }
        val trashed = list.filter { it.category == HistoryCategory.TRASH }
        val alive = list.filter { it.category != HistoryCategory.TRASH }
        val lockable = alive.filter { !it.isLocked }

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (lockable.isNotEmpty()) {
            labels.add("批量加锁保护（${lockable.size} 段）")
            actions.add { batchMove(lockable, RecordCategory.EVENT) }
        }
        if (alive.isNotEmpty()) {
            labels.add("批量移入回收站（${alive.size} 段）")
            actions.add { batchMove(alive, null) }
        }
        if (trashed.isNotEmpty()) {
            labels.add("彻底粉碎（${trashed.size} 段）")
            actions.add { batchPurge(trashed) }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("批量管理 · 当前 ${list.size} 段")
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** category 为 null 表示移入回收站 */
    private fun batchMove(items: List<RecordingItem>, category: RecordCategory?) {
        val usbRoot = SophonApp.storageVault.currentUsbRoot.value
        if (usbRoot == null) {
            Toast.makeText(context, "未检测到 U 盘，无法移动切片", Toast.LENGTH_SHORT).show()
            return
        }
        val targetDir = if (category != null) {
            File(File(usbRoot, "SophonRecord"), category.dirName)
        } else {
            File(File(usbRoot, "SophonRecord"), ".Trash")
        }.apply { mkdirs() }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var ok = 0
            for (item in items) {
                val target = File(targetDir, item.file.name)
                if (item.file.exists() && item.file.renameTo(target)) ok++
            }
            val moved = ok
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "已完成 $moved / ${items.size} 段", Toast.LENGTH_SHORT).show()
                loadRecordingsFromUsb()
            }
        }
    }

    private fun batchPurge(items: List<RecordingItem>) {
        AlertDialog.Builder(requireContext())
            .setTitle("确认彻底删除？")
            .setMessage("将永久抹除 ${items.size} 段切片，不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    var ok = 0
                    for (item in items) if (item.file.delete()) ok++
                    val deleted = ok
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "已抹除 $deleted / ${items.size} 段", Toast.LENGTH_SHORT).show()
                        loadRecordingsFromUsb()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    inner class RecordingAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int =
            when (displayRows[position]) {
                is Row.Header -> TYPE_HEADER
                is Row.Clip -> TYPE_CLIP
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderViewHolder(inflater.inflate(R.layout.item_date_header, parent, false))
            } else {
                ClipViewHolder(inflater.inflate(R.layout.item_recording_card, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = displayRows[position]) {
                is Row.Header -> (holder as HeaderViewHolder).bind(row)
                is Row.Clip -> (holder as ClipViewHolder).bind(row.item)
            }
        }

        override fun getItemCount(): Int = displayRows.size

        inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvDate: TextView = view.findViewById(R.id.tv_header_date)
            private val tvMeta: TextView = view.findViewById(R.id.tv_header_meta)

            fun bind(row: Row.Header) {
                tvDate.text = row.dateDay
                val mb = row.bytes.toDouble() / (1024 * 1024)
                tvMeta.text = if (mb >= 1024) {
                    "${row.count} 段 · ${String.format(Locale.getDefault(), "%.2f", mb / 1024)} GB"
                } else {
                    "${row.count} 段 · ${String.format(Locale.getDefault(), "%.1f", mb)} MB"
                }
            }
        }

        inner class ClipViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val ivThumb: ImageView = view.findViewById(R.id.iv_record_thumb)
            private val tvTime: TextView = view.findViewById(R.id.tv_record_time)
            private val tvDetails: TextView = view.findViewById(R.id.tv_record_details)
            private val tvBadgeDuration: TextView? = view.findViewById(R.id.tv_badge_duration)
            private val tvBadgeRes: TextView? = view.findViewById(R.id.tv_badge_res)
            private val tvBadgeCam: TextView? = view.findViewById(R.id.tv_badge_cam)
            private val tvBadgeLocked: TextView? = view.findViewById(R.id.tv_badge_locked)

            fun bind(item: RecordingItem) {
                val isSelected = (item == selectedItem)
                itemView.isSelected = isSelected
                itemView.setBackgroundResource(
                    if (isSelected) R.drawable.sp_bg_card_selected else R.drawable.sp_bg_card
                )

                tvTime.text = item.formattedTime.substringAfter(" ")
                tvDetails.text = "${item.camTag} · ${item.sizeMb}"
                tvBadgeDuration?.text = formatDuration(item.durationMs)
                tvBadgeRes?.text = if (item.height > 0) "${item.height}P" else "--"
                tvBadgeCam?.text = item.camTag
                tvBadgeLocked?.visibility = if (item.isLocked) View.VISIBLE else View.GONE

                ivThumb.tag = item.file.absolutePath
                ivThumb.setImageDrawable(null)
                val cached = thumbCache.get(item.file.absolutePath)
                if (cached != null) {
                    ivThumb.setImageBitmap(cached)
                } else {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val bmp = getVideoThumbnail(item.file, 240, 135)
                        bmp?.let { thumbCache.put(item.file.absolutePath, it) }
                        withContext(Dispatchers.Main) {
                            if (ivThumb.tag == item.file.absolutePath) {
                                ivThumb.setImageBitmap(bmp)
                            }
                        }
                    }
                }

                itemView.setOnClickListener {
                    selectedItem = item
                    updateDetailPanel(item)
                    notifyDataSetChanged()
                }
            }
        }
    }
}
