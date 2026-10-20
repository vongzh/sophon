package com.vongzh.sophon.ui.sentry

import android.content.Intent
import android.content.res.ColorStateList
import android.media.ThumbnailUtils
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.DatePickerDialog
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 哨兵模式 · 事件复盘看板组件 (SentryEventReviewFragment)
 *
 * 【顶部日期横向滑动筛选 + 风险等级过滤 + 3 条核心 KPI 卡 + 雷达方位态势 + 底部守护提示】
 */
class SentryEventReviewFragment : Fragment() {

    enum class RiskLevel {
        ALL,
        HIGH,
        LOW
    }

    data class SentryEventItem(
        val file: File,
        val typeTitle: String,
        val badgeText: String,
        val badgeColor: Int,
        val camTag: String,
        val timeDisplay: String,
        val dateDay: String,
        val timestampMs: Long,
        val direction: SentryVehicleRadarView.RadarDirection,
        val hazardLevel: String,
        val isHighRisk: Boolean
    )

    // 顶部工具
    private lateinit var btnRefresh: View
    private var ivRefreshIcon: ImageView? = null
    private lateinit var btnChooseDir: TextView
    private lateinit var btnSelectAll: TextView
    private lateinit var btnMultiSelect: TextView
    private lateinit var tvHeaderSubtitle: TextView

    // 日期胶囊
    private lateinit var layoutDatePills: LinearLayout
    private lateinit var pillAllDate: TextView
    private lateinit var pillToday: TextView
    private lateinit var pillYesterday: TextView
    private lateinit var pillDayBefore: TextView

    // 风险级别 Pills
    private lateinit var btnRiskAll: TextView
    private lateinit var btnRiskHigh: TextView
    private lateinit var btnRiskLow: TextView

    // KPI 指标卡
    private lateinit var tvEventsVal: TextView
    private lateinit var tvEventsTrend: TextView
    private lateinit var tvBatteryVal: TextView
    private lateinit var tvDurationVal: TextView
    private lateinit var tvDurationSub: TextView

    // 事件列表
    private lateinit var rvEvents: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var layoutEmpty: View
    private lateinit var btnEmptyRefresh: View

    // 雷达态势面板
    private lateinit var radarView: SentryVehicleRadarView
    private lateinit var tvRadarTitle: TextView
    private lateinit var tvRadarLevel: TextView
    private lateinit var tvLegendCollisionCount: TextView
    private lateinit var tvLegendApproachCount: TextView
    private lateinit var tvLegendVibeCount: TextView
    private lateinit var btnViewAllClips: TextView

    // 数据与过滤状态
    private val allEvents = mutableListOf<SentryEventItem>()
    private val displayedEvents = mutableListOf<SentryEventItem>()
    private var selectedDate: String? = null // null: 全部
    private var currentRisk: RiskLevel = RiskLevel.ALL
    private var selectedEvent: SentryEventItem? = null
    private var includeEventDir = true
    private var isMultiSelectMode = false
    private val selectedEvents = mutableSetOf<SentryEventItem>()
    private val adapter = SentryEventAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_sentry_event_review, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupListeners()
        loadSentryEvents()
    }

    private fun initViews(root: View) {
        btnRefresh = root.findViewById(R.id.btn_sentry_refresh)
        ivRefreshIcon = root.findViewById(R.id.iv_sentry_refresh_icon)
        btnChooseDir = root.findViewById(R.id.btn_sentry_choose_dir)
        btnSelectAll = root.findViewById(R.id.btn_sentry_select_all)
        btnMultiSelect = root.findViewById(R.id.btn_sentry_multi_select)
        tvHeaderSubtitle = root.findViewById(R.id.tv_sentry_header_subtitle)

        root.findViewById<View>(R.id.btn_sentry_lan_share)?.setOnClickListener {
            activity?.let { act ->
                com.vongzh.sophon.ui.lan.LanShareDialog(act).show()
            }
        }

        layoutDatePills = root.findViewById(R.id.layout_sentry_date_pills)
        pillAllDate = root.findViewById(R.id.pill_sentry_all_date)
        pillToday = root.findViewById(R.id.pill_sentry_today)
        pillYesterday = root.findViewById(R.id.pill_sentry_yesterday)
        pillDayBefore = root.findViewById(R.id.pill_sentry_day_before)

        btnRiskAll = root.findViewById(R.id.btn_sentry_filter_risk_all)
        btnRiskHigh = root.findViewById(R.id.btn_sentry_filter_risk_high)
        btnRiskLow = root.findViewById(R.id.btn_sentry_filter_risk_low)

        tvEventsVal = root.findViewById(R.id.tv_kpi_events_val)
        tvEventsTrend = root.findViewById(R.id.tv_kpi_events_trend)
        tvBatteryVal = root.findViewById(R.id.tv_kpi_battery_val)
        tvDurationVal = root.findViewById(R.id.tv_kpi_duration_val)
        tvDurationSub = root.findViewById(R.id.tv_kpi_duration_sub)

        rvEvents = root.findViewById(R.id.rv_sentry_events)
        tvEmpty = root.findViewById(R.id.tv_sentry_empty)
        layoutEmpty = root.findViewById(R.id.layout_sentry_empty)
        btnEmptyRefresh = root.findViewById(R.id.btn_empty_sentry_refresh)
        btnEmptyRefresh.setOnClickListener { loadSentryEvents() }
        rvEvents.layoutManager = LinearLayoutManager(requireContext())
        rvEvents.adapter = adapter

        radarView = root.findViewById(R.id.view_sentry_radar)
        tvRadarTitle = root.findViewById(R.id.tv_sentry_radar_title)
        tvRadarLevel = root.findViewById(R.id.tv_sentry_radar_level)
        tvLegendCollisionCount = root.findViewById(R.id.tv_legend_collision_count)
        tvLegendApproachCount = root.findViewById(R.id.tv_legend_approach_count)
        tvLegendVibeCount = root.findViewById(R.id.tv_legend_vibe_count)
        btnViewAllClips = root.findViewById(R.id.btn_sentry_view_all_clips)
    }

    private fun setupListeners() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val cal1 = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yesterdayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal1.time)
        val cal2 = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -2) }
        val dayBeforeStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal2.time)

        pillToday.text = "今天 (${SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date())})"
        pillYesterday.text = "昨天 (${SimpleDateFormat("MM-dd", Locale.getDefault()).format(cal1.time)})"
        pillDayBefore.text = "自定义…"

        pillAllDate.setOnClickListener {
            selectedDate = null
            updateDatePillsUi(0)
            applyFilter()
        }

        pillToday.setOnClickListener {
            selectedDate = todayStr
            updateDatePillsUi(1)
            applyFilter()
        }

        pillYesterday.setOnClickListener {
            selectedDate = yesterdayStr
            updateDatePillsUi(2)
            applyFilter()
        }

        pillDayBefore.setOnClickListener { openCustomDatePicker() }

        btnRiskAll.setOnClickListener {
            currentRisk = RiskLevel.ALL
            updateRiskPillsUi(0)
            applyFilter()
        }

        btnRiskHigh.setOnClickListener {
            currentRisk = RiskLevel.HIGH
            updateRiskPillsUi(1)
            applyFilter()
        }

        btnRiskLow.setOnClickListener {
            currentRisk = RiskLevel.LOW
            updateRiskPillsUi(2)
            applyFilter()
        }

        btnRefresh.setOnClickListener {
            UiMotionUtils.startRefreshAnimation(btnRefresh, ivRefreshIcon) {
                loadSentryEvents()
                Toast.makeText(context, "已刷新哨兵事件", Toast.LENGTH_SHORT).show()
            }
        }

        btnChooseDir.setOnClickListener {
            includeEventDir = !includeEventDir
            btnChooseDir.text = if (includeEventDir) "扫描范围: 驻车 + 锁存 ▾" else "扫描范围: 仅驻车 ▾"
            loadSentryEvents()
        }
        btnChooseDir.text = "扫描范围: 驻车 + 锁存 ▾"

        btnSelectAll.setOnClickListener {
            if (!isMultiSelectMode) return@setOnClickListener
            val isAllSelected = displayedEvents.isNotEmpty() && selectedEvents.size == displayedEvents.size
            if (isAllSelected) {
                selectedEvents.clear()
            } else {
                selectedEvents.clear()
                selectedEvents.addAll(displayedEvents)
            }
            updateSentryMultiSelectUi()
            adapter.notifyDataSetChanged()
        }

        btnMultiSelect.setOnClickListener {
            if (isMultiSelectMode && selectedEvents.isNotEmpty()) {
                showBatchDialog()
            } else {
                isMultiSelectMode = !isMultiSelectMode
                selectedEvents.clear()
                updateSentryMultiSelectUi()
                adapter.notifyDataSetChanged()
            }
        }

        btnViewAllClips.setOnClickListener {
            val target = selectedEvent ?: displayedEvents.firstOrNull()
            if (target != null) {
                val intent = Intent(requireContext(), QuadSyncPlayerActivity::class.java).apply {
                    putExtra(QuadSyncPlayerActivity.EXTRA_VIDEO_PATH, target.file.absolutePath)
                }
                startActivity(intent)
            } else {
                Toast.makeText(context, "暂无可选事件视频进行四路回看", Toast.LENGTH_SHORT).show()
            }
        }
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnViewAllClips, 0.95f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(pillAllDate, 0.95f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(pillToday, 0.95f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(pillYesterday, 0.95f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(pillDayBefore, 0.95f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnRiskAll, 0.95f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnRiskHigh, 0.95f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnRiskLow, 0.95f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnRefresh, 0.94f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnChooseDir, 0.95f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnSelectAll, 0.94f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnMultiSelect, 0.94f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnEmptyRefresh, 0.94f)

        // 12V 蓄电池电压：持续订阅真实信号源，读不到时显示 "--" 而不是编一个 12.6
        viewLifecycleOwner.lifecycleScope.launch {
            SophonApp.vehicleSignalBus.batteryVoltage.collectLatest { volts ->
                tvBatteryVal.text = if (volts > 0f) String.format(Locale.getDefault(), "%.1f", volts) else "--"
            }
        }

        // 副标题：由真实电源状态机驱动，不再写死「熄火持续布防中」
        viewLifecycleOwner.lifecycleScope.launch {
            SophonApp.powerStateMachine.currentState.collectLatest { state ->
                val desc = when (state) {
                    com.vongzh.sophon.core.power.PowerState.SENTRY_STANDBY -> "哨兵布防中"
                    com.vongzh.sophon.core.power.PowerState.SENTRY_ALERT -> "哨兵告警中"
                    com.vongzh.sophon.core.power.PowerState.AWAKE_DRIVING -> "行车录制中"
                    com.vongzh.sophon.core.power.PowerState.AWAKE_PARKED -> "驻车待机"
                    com.vongzh.sophon.core.power.PowerState.EMERGENCY_POWER_CUT -> "低压保护·已断电"
                }
                tvHeaderSubtitle.text = "$desc · 今日新增 ${allEvents.count { it.dateDay == todayStr }} 条"
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) {
            loadSentryEvents()
        }
    }

    private fun updateDatePillsUi(activeIndex: Int) {
        val pills = listOf(pillAllDate, pillToday, pillYesterday, pillDayBefore)
        for (i in pills.indices) {
            val p = pills[i]
            val isSelected = (i == activeIndex)
            p.setBackgroundResource(if (isSelected) R.drawable.sp_bg_pill_accent else R.drawable.sp_bg_pill)
            p.setTextColor(requireContext().getColor(if (isSelected) R.color.sp_ac else R.color.sp_tx2))
            p.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun updateRiskPillsUi(riskIndex: Int) {
        btnRiskAll.setBackgroundResource(if (riskIndex == 0) R.drawable.sp_bg_pill_accent else R.drawable.sp_bg_pill)
        btnRiskAll.setTextColor(requireContext().getColor(if (riskIndex == 0) R.color.sp_ac else R.color.sp_tx2))

        btnRiskHigh.setBackgroundResource(if (riskIndex == 1) R.drawable.sp_bg_pill_accent else R.drawable.sp_bg_pill)
        btnRiskHigh.setTextColor(requireContext().getColor(if (riskIndex == 1) R.color.sp_dg else R.color.sp_tx2))

        btnRiskLow.setBackgroundResource(if (riskIndex == 2) R.drawable.sp_bg_pill_accent else R.drawable.sp_bg_pill)
        btnRiskLow.setTextColor(requireContext().getColor(if (riskIndex == 2) R.color.sp_wn else R.color.sp_tx2))
    }

    fun loadSentryEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { scanSentryFiles() }
            allEvents.clear()
            allEvents.addAll(items)
            updateKpiCards()
            applyFilter()
        }
    }

    /**
     * 扫描哨兵影像。
     *
     * 【重要修正】原实现靠文件名里含 CRASH / DOW / APPROACH / VIBE / SHOCK 来判定
     * 事件类型，但录像文件的真实命名恒为 `{yyyyMMdd_HHmmss}_{F|B|L|R}.mp4`，从不写
     * 入这些标记 —— 结果是所有事件永远落到 else 分支（「哨兵 / 驻车异动监测」），
     * 碰撞、靠近、震动三个图例计数恒为 0，风险等级筛选「高」永远筛不出任何东西。
     *
     * 现改为仅使用真实存在的判据：
     * - 目录：Event 目录 = 碰撞/紧急触发后被提升加锁的片段（严重）；Sentry 目录 = 常规驻车监测（注意）
     * - 文件名末尾的镜头标记 F/B/L/R：决定雷达方位与图例分布
     */
    private fun scanSentryFiles(): List<SentryEventItem> {
        val usbRoot = SophonApp.storageVault.currentUsbRoot.value
        if (usbRoot == null || !SophonApp.storageVault.isUsbMounted.value) return emptyList()

        val result = mutableListOf<SentryEventItem>()
        val baseDir = File(usbRoot, "SophonRecord")
        val sentryDir = File(baseDir, RecordCategory.SENTRY.dirName)
        val eventDir = File(baseDir, RecordCategory.EVENT.dirName)

        val parser = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val display = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        val processFolder = { folder: File, isEvent: Boolean ->
            if (folder.exists() && folder.isDirectory) {
                folder.listFiles()?.filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }?.forEach { f ->
                    val name = f.name
                    val timePart = name.substringBefore(".mp4")
                    val timeMs = try {
                        parser.parse(timePart)?.time ?: f.lastModified()
                    } catch (e: Exception) {
                        f.lastModified()
                    }

                    val ctx = context ?: return@forEach
                    val camTag = parseCamTag(name)

                    val typeTitle: String
                    val badgeText: String
                    val badgeColor: Int
                    val hazardLevel: String
                    val isHigh: Boolean

                    if (isEvent) {
                        // Event 目录：碰撞传感器或手动触发后提升加锁的片段
                        badgeText = "锁存"
                        badgeColor = ctx.getColor(R.color.sp_dg)
                        typeTitle = "碰撞 / 紧急触发锁存"
                        hazardLevel = "严重"
                        isHigh = true
                    } else {
                        badgeText = "哨兵"
                        badgeColor = ctx.getColor(R.color.sp_ac)
                        typeTitle = "驻车异动监测"
                        hazardLevel = "注意"
                        isHigh = false
                    }

                    result.add(
                        SentryEventItem(
                            file = f,
                            typeTitle = typeTitle,
                            badgeText = badgeText,
                            badgeColor = badgeColor,
                            camTag = camTag,
                            timeDisplay = display.format(Date(timeMs)),
                            dateDay = dayFmt.format(Date(timeMs)),
                            timestampMs = timeMs,
                            direction = directionOfCam(camTag),
                            hazardLevel = hazardLevel,
                            isHighRisk = isHigh
                        )
                    )
                }
            }
        }

        processFolder(sentryDir, false)
        if (includeEventDir) processFolder(eventDir, true)

        return result.sortedByDescending { it.timestampMs }
    }

    /**
     * 镜头方位 -> 雷达象限。
     * 雷达视图只有四个象限 + 全域，故前摄映射到前方左侧象限、右摄映射到前方右侧象限，
     * 左摄/后摄分别映射到后方左/右象限，仅用于示意方位分布。
     */
    private fun directionOfCam(camTag: String): SentryVehicleRadarView.RadarDirection =
        when {
            camTag.contains("前") -> SentryVehicleRadarView.RadarDirection.FRONT_LEFT
            camTag.contains("后") -> SentryVehicleRadarView.RadarDirection.REAR_RIGHT
            camTag.contains("左") -> SentryVehicleRadarView.RadarDirection.REAR_LEFT
            camTag.contains("右") -> SentryVehicleRadarView.RadarDirection.FRONT_RIGHT
            else -> SentryVehicleRadarView.RadarDirection.ALL_AROUND
        }

    private fun parseCamTag(fileName: String): String {
        val base = fileName.substringBeforeLast(".mp4")
        return when {
            base.endsWith("_ALL") -> "四路全景"
            base.endsWith("_F") -> "前路主摄"
            base.endsWith("_B") -> "后路广角"
            base.endsWith("_L") -> "左路盲区"
            base.endsWith("_R") -> "右路盲区"
            else -> "四向记录"
        }
    }

    private fun updateKpiCards() {
        val totalCount = allEvents.size
        tvEventsVal.text = totalCount.toString()

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yesterdayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)

        val todayCount = allEvents.count { it.dateDay == todayStr }
        if (todayCount == 0) {
            tvEventsTrend.text = "全车受护"
            tvEventsTrend.setBackgroundResource(R.drawable.sp_bg_pill)
            tvEventsTrend.setTextColor(requireContext().getColor(R.color.sp_ok))
        } else {
            tvEventsTrend.text = "今日触发 ${todayCount}起"
            tvEventsTrend.setBackgroundResource(R.drawable.sp_bg_pill_accent)
            tvEventsTrend.setTextColor(requireContext().getColor(R.color.sp_wn))
        }


        if (allEvents.isNotEmpty()) {
            val oldest = allEvents.minOf { it.timestampMs }
            val newest = allEvents.maxOf { it.timestampMs }
            val hours = maxOf(0.1, (newest - oldest).toDouble() / (1000.0 * 3600.0))
            tvDurationVal.text = String.format(Locale.getDefault(), "%.1f", hours)
        } else {
            tvDurationVal.text = "0.0"
        }
    }

    private fun applyFilter() {
        val filtered = allEvents.filter { item ->
            val matchDate = (selectedDate == null || item.dateDay == selectedDate)
            val matchRisk = when (currentRisk) {
                RiskLevel.ALL -> true
                RiskLevel.HIGH -> item.isHighRisk
                RiskLevel.LOW -> !item.isHighRisk
            }
            matchDate && matchRisk
        }

        displayedEvents.clear()
        displayedEvents.addAll(filtered)

        tvEmpty.visibility = if (displayedEvents.isEmpty()) View.VISIBLE else View.GONE

        // 更新雷达方位统计
        // 图例统计真实可判的镜头方位分布（原「碰撞/靠近/震动」依赖不存在的命名标记，恒为 0）
        val frontCount = displayedEvents.count { it.camTag.contains("前") }
        val rearCount = displayedEvents.count { it.camTag.contains("后") }
        val sideCount = displayedEvents.count { it.camTag.contains("左") || it.camTag.contains("右") }

        tvLegendCollisionCount.text = "前方 $frontCount 起"
        tvLegendApproachCount.text = "后方 $rearCount 起"
        tvLegendVibeCount.text = "侧向 $sideCount 起"

        if (selectedEvent == null || !displayedEvents.contains(selectedEvent)) {
            selectedEvent = displayedEvents.firstOrNull()
        }
        if (selectedEvent != null) {
            updateRadarPanel(selectedEvent!!)
            btnViewAllClips.isEnabled = true
            btnViewAllClips.alpha = 1.0f
        } else {
            tvRadarTitle.text = "暂无哨兵警报"
            tvRadarLevel.text = "待机就绪"
            tvRadarLevel.setTextColor(requireContext().getColor(R.color.sp_tx3))
            radarView.resetToSafeState()
            btnViewAllClips.isEnabled = false
            btnViewAllClips.alpha = 0.40f
        }

        adapter.notifyDataSetChanged()
    }

    private fun updateRadarPanel(event: SentryEventItem) {
        tvRadarTitle.text = "${event.camTag} · ${event.typeTitle}"
        tvRadarLevel.text = event.hazardLevel
        tvRadarLevel.setTextColor(event.badgeColor)
        radarView.highlightDirection(event.direction, event.badgeColor)
    }

    inner class SentryEventAdapter : RecyclerView.Adapter<SentryEventAdapter.ViewHolder>() {

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tv_sentry_title)
            val tvBadge: TextView = v.findViewById(R.id.tv_sentry_badge)
            val tvCamTag: TextView = v.findViewById(R.id.tv_sentry_cam_tag)
            val tvDetails: TextView = v.findViewById(R.id.tv_sentry_details)
            val ivThumb: ImageView = v.findViewById(R.id.iv_sentry_thumb)
            val btnViewClip: TextView? = v.findViewById(R.id.btn_sentry_view_clip)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_sentry_event_card, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = displayedEvents[position]
            val isSelected = (item == selectedEvent)

            holder.itemView.isSelected = isSelected
            holder.itemView.setBackgroundResource(
                if (isSelected) R.drawable.sp_bg_card_selected else R.drawable.sp_bg_card
            )

            holder.tvTitle.text = "${item.camTag} · ${item.typeTitle}"
            holder.tvBadge.text = item.badgeText
            holder.tvBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(item.badgeColor)
            holder.tvCamTag.text = item.camTag
            holder.tvDetails.text = "${item.dateDay} ${item.timeDisplay} · 状态锁定"

            holder.btnViewClip?.setOnClickListener {
                val intent = Intent(requireContext(), QuadSyncPlayerActivity::class.java).apply {
                    putExtra(QuadSyncPlayerActivity.EXTRA_VIDEO_PATH, item.file.absolutePath)
                }
                startActivity(intent)
            }
            holder.btnViewClip?.let { com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(it, 0.92f) }

            holder.itemView.setBackgroundResource(
                if (selectedEvents.contains(item)) R.drawable.sp_bg_card_selected
                else if (isSelected) R.drawable.sp_bg_card_selected
                else R.drawable.sp_bg_card
            )

            holder.itemView.setOnClickListener {
                if (isMultiSelectMode) {
                    if (selectedEvents.contains(item)) selectedEvents.remove(item) else selectedEvents.add(item)
                    updateSentryMultiSelectUi()
                    notifyItemChanged(position)
                } else {
                    selectedEvent = item
                    updateRadarPanel(item)
                    notifyDataSetChanged()
                }
            }
        }

        override fun getItemCount(): Int = displayedEvents.size
    }

    /** 自定义日期筛选：补足只有 全部/今天/昨天 三个固定胶囊的不足 */
    private fun openCustomDatePicker() {
        val now = Calendar.getInstance()
        val picked = Calendar.getInstance()
        if (selectedDate != null) {
            val parts = selectedDate!!.split("-")
            if (parts.size == 3) picked.set(parts[0].toIntOrNull() ?: now.get(Calendar.YEAR),
                (parts[1].toIntOrNull() ?: 1) - 1, parts[2].toIntOrNull() ?: 1)
        }
        DatePickerDialog(requireContext(), { _, y, m, d ->
            val cal = Calendar.getInstance().apply { set(y, m, d) }
            selectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
            pillDayBefore.text = SimpleDateFormat("MM-dd", Locale.getDefault()).format(cal.time)
            updateDatePillsUi(3)
            applyFilter()
        }, picked.get(Calendar.YEAR), picked.get(Calendar.MONTH), picked.get(Calendar.DAY_OF_MONTH)).show()
    }

    /** 批量归档 / 删除：对当前多选结果执行真实文件操作 */
    private fun showBatchDialog() {
        val items = selectedEvents.toList()
        val labels = arrayOf("批量加锁保护（${items.size} 段）", "批量删除（${items.size} 段）", "取消选择")
        AlertDialog.Builder(requireContext())
            .setTitle("批量管理 · 已选 ${items.size} 段")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> batchMoveToEvent(items)
                    1 -> confirmBatchDelete(items)
                    else -> {
                        selectedEvents.clear()
                        isMultiSelectMode = false
                        updateSentryMultiSelectUi()
                        adapter.notifyDataSetChanged()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateSentryMultiSelectUi() {
        if (isMultiSelectMode) {
            btnSelectAll.visibility = View.VISIBLE
            val isAllSelected = displayedEvents.isNotEmpty() && selectedEvents.size == displayedEvents.size
            btnSelectAll.text = if (isAllSelected) "取消全选" else "全选"
            val selColor = if (isAllSelected) requireContext().getColor(R.color.sp_ac) else requireContext().getColor(R.color.sp_tx)
            btnSelectAll.setTextColor(selColor)
            btnSelectAll.compoundDrawableTintList = ColorStateList.valueOf(if (isAllSelected) requireContext().getColor(R.color.sp_ac) else requireContext().getColor(R.color.sp_tx2))
            btnSelectAll.setBackgroundResource(if (isAllSelected) R.drawable.sp_bg_pill_accent else R.drawable.sp_bg_pill)

            btnMultiSelect.text = "批量管理 · 已选 ${selectedEvents.size}"
            btnMultiSelect.setBackgroundResource(R.drawable.sp_bg_pill_accent)
            btnMultiSelect.setTextColor(requireContext().getColor(R.color.sp_ac))
            btnMultiSelect.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_action_check, 0, 0, 0)
            btnMultiSelect.compoundDrawableTintList = ColorStateList.valueOf(requireContext().getColor(R.color.sp_ac))
        } else {
            btnSelectAll.visibility = View.GONE
            btnMultiSelect.text = "批量管理"
            btnMultiSelect.setBackgroundResource(R.drawable.sp_bg_pill)
            btnMultiSelect.setTextColor(requireContext().getColor(R.color.sp_tx))
            btnMultiSelect.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_action_check, 0, 0, 0)
            btnMultiSelect.compoundDrawableTintList = ColorStateList.valueOf(requireContext().getColor(R.color.sp_tx2))
        }
    }

    private fun batchMoveToEvent(items: List<SentryEventItem>) {
        val usbRoot = SophonApp.storageVault.currentUsbRoot.value ?: return
        val dir = File(File(usbRoot, "SophonRecord"), RecordCategory.EVENT.dirName).apply { mkdirs() }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var ok = 0
            for (item in items) if (item.file.renameTo(File(dir, item.file.name))) ok++
            val moved = ok
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "已加锁 $moved / ${items.size} 段", Toast.LENGTH_SHORT).show()
                selectedEvents.clear()
                isMultiSelectMode = false
                updateSentryMultiSelectUi()
                loadSentryEvents()
            }
        }
    }

    private fun confirmBatchDelete(items: List<SentryEventItem>) {
        AlertDialog.Builder(requireContext())
            .setTitle("确认删除？")
            .setMessage("将永久抹除 ${items.size} 段哨兵影像，不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    var ok = 0
                    for (item in items) if (item.file.delete()) ok++
                    val deleted = ok
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "已删除 $deleted / ${items.size} 段", Toast.LENGTH_SHORT).show()
                        selectedEvents.clear()
                        isMultiSelectMode = false
                        updateSentryMultiSelectUi()
                        loadSentryEvents()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
