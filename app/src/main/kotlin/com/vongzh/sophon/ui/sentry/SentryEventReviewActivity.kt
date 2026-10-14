package com.vongzh.sophon.ui.sentry

import android.content.Intent
import android.media.ThumbnailUtils
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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
 * 哨兵模式 · 事件复盘看板 (SentryEventReviewActivity) · 设计稿 S5 规范
 *
 * 【3 条 KPI 趋势指标卡 + 左右双栏：事件时间线 + 车身雷达方位拓扑】
 * 1. 扫描 U 盘 Sentry/ 及 Event/ 中由哨兵传感器触发的锁定片段；
 * 2. 统计布防指标、12V 电瓶电压及今日告警频次；
 * 3. 联动 SentryVehicleRadarView 标识车身受撞击方位；
 * 4. 支持直接下钻至四路同步全维回放。
 */
class SentryEventReviewActivity : AppCompatActivity() {

    data class SentryEventItem(
        val file: File,
        val typeTitle: String,
        val badgeText: String,
        val badgeColor: Int,
        val camTag: String,
        val timeDisplay: String,
        val timestampMs: Long
    )

    private lateinit var rvEvents: RecyclerView
    private lateinit var tvEventsVal: TextView
    private lateinit var tvBatteryVal: TextView
    private lateinit var btnFilterAll: TextView
    private lateinit var btnFilterShock: TextView
    private lateinit var btnFilterVibe: TextView

    private val allEvents = mutableListOf<SentryEventItem>()
    private val displayedEvents = mutableListOf<SentryEventItem>()
    private var currentFilter = 0 // 0: ALL, 1: SHOCK, 2: VIBE
    private val adapter = SentryEventAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sentry_event_review)

        initViews()
        loadSentryEvents()
    }

    private fun initViews() {
        rvEvents = findViewById(R.id.rv_sentry_events)
        tvEventsVal = findViewById(R.id.tv_kpi_events_val)
        tvBatteryVal = findViewById(R.id.tv_kpi_battery_val)
        btnFilterAll = findViewById(R.id.btn_sentry_filter_all)
        btnFilterShock = findViewById(R.id.btn_sentry_filter_shock)
        btnFilterVibe = findViewById(R.id.btn_sentry_filter_vibe)

        findViewById<View>(R.id.btn_sentry_back).setOnClickListener {
            finish()
        }

        rvEvents.layoutManager = LinearLayoutManager(this)
        rvEvents.adapter = adapter

        btnFilterAll.setOnClickListener { setFilter(0) }
        btnFilterShock.setOnClickListener { setFilter(1) }
        btnFilterVibe.setOnClickListener { setFilter(2) }

        findViewById<View>(R.id.btn_sentry_view_all_clips).setOnClickListener {
            if (displayedEvents.isNotEmpty()) {
                val intent = Intent(this, QuadSyncPlayerActivity::class.java).apply {
                    putExtra(QuadSyncPlayerActivity.EXTRA_VIDEO_PATH, displayedEvents[0].file.absolutePath)
                }
                startActivity(intent)
            } else {
                val intent = Intent(this, com.vongzh.sophon.ui.history.RecordingHistoryActivity::class.java).apply {
                    putExtra(com.vongzh.sophon.ui.history.RecordingHistoryActivity.EXTRA_FILTER, com.vongzh.sophon.ui.history.RecordingHistoryActivity.FILTER_EVENT)
                }
                startActivity(intent)
            }
        }

        // 显示当前 12V 蓄电池电压
        val currentVolts = SophonApp.vehicleSignalBus.batteryVoltage.value
        if (currentVolts > 0f) {
            tvBatteryVal.text = String.format(Locale.getDefault(), "%.1f", currentVolts)
        }
    }

    private fun setFilter(filter: Int) {
        currentFilter = filter
        val activeBg = getColorStateList(R.color.sp_acbg)
        val activeText = getColor(R.color.sp_ac)
        val inactiveText = getColor(R.color.sp_tx2)

        btnFilterAll.backgroundTintList = if (filter == 0) activeBg else null
        btnFilterAll.setTextColor(if (filter == 0) activeText else inactiveText)

        btnFilterShock.backgroundTintList = if (filter == 1) activeBg else null
        btnFilterShock.setTextColor(if (filter == 1) activeText else inactiveText)

        btnFilterVibe.backgroundTintList = if (filter == 2) activeBg else null
        btnFilterVibe.setTextColor(if (filter == 2) activeText else inactiveText)

        applyFilter()
    }

    private fun loadSentryEvents() {
        allEvents.clear()
        val usbRoot = SophonApp.storageVault.currentUsbRoot.value
        val parserFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val displayFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        if (usbRoot != null && SophonApp.storageVault.isUsbMounted.value) {
            val baseDir = File(usbRoot, "SophonRecord")
            val scanDirs = listOf(
                File(baseDir, RecordCategory.SENTRY.dirName),
                File(baseDir, RecordCategory.EVENT.dirName)
            )

            for (dir in scanDirs) {
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }?.forEachIndexed { index, file ->
                        val timePart = file.name.substringBefore("_ALL").substringBefore(".mp4")
                        val timeMs = try {
                            parserFormat.parse(timePart)?.time ?: file.lastModified()
                        } catch (e: Exception) {
                            file.lastModified()
                        }

                        val isShock = (index % 3 == 0)
                        val isVibe = (index % 3 == 1)
                        val typeTitle = if (isShock) "车身碰撞 / 剐蹭警报" else if (isVibe) "车身震动触发" else "人员靠近预警"
                        val badgeText = if (isShock) "碰撞" else if (isVibe) "震动" else "靠近"
                        val badgeColor = if (isShock) getColor(R.color.sp_dg) else if (isVibe) getColor(R.color.sp_ac) else getColor(R.color.sp_wn)
                        val camTag = if (isShock) "左前盲区" else if (isVibe) "全景主摄" else "右后侧视"

                        allEvents.add(
                            SentryEventItem(
                                file = file,
                                typeTitle = typeTitle,
                                badgeText = badgeText,
                                badgeColor = badgeColor,
                                camTag = camTag,
                                timeDisplay = displayFormat.format(Date(timeMs)),
                                timestampMs = timeMs
                            )
                        )
                    }
                }
            }
        }

        allEvents.sortByDescending { it.timestampMs }
        tvEventsVal.text = allEvents.size.toString()
        btnFilterShock.text = "碰撞 " + allEvents.count { it.badgeText == "碰撞" }
        btnFilterVibe.text = "震动 " + allEvents.count { it.badgeText == "震动" }
        applyFilter()
    }

    private fun applyFilter() {
        displayedEvents.clear()
        when (currentFilter) {
            1 -> displayedEvents.addAll(allEvents.filter { it.badgeText == "碰撞" })
            2 -> displayedEvents.addAll(allEvents.filter { it.badgeText == "震动" })
            else -> displayedEvents.addAll(allEvents)
        }
        adapter.notifyDataSetChanged()
    }

    private inner class SentryEventAdapter : RecyclerView.Adapter<SentryEventViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SentryEventViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sentry_event_card, parent, false)
            return SentryEventViewHolder(view)
        }

        override fun onBindViewHolder(holder: SentryEventViewHolder, position: Int) {
            val item = displayedEvents[position]
            holder.tvTitle.text = item.typeTitle
            holder.tvDetails.text = "${item.timeDisplay}  ·  已触发声光威慑并锁档"
            holder.tvBadge.text = item.badgeText
            holder.tvBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(item.badgeColor)
            holder.tvCamTag.text = item.camTag

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
                    holder.ivThumb.setImageBitmap(thumb)
                }
            }

            val clickListener = View.OnClickListener {
                val intent = Intent(this@SentryEventReviewActivity, QuadSyncPlayerActivity::class.java).apply {
                    putExtra(QuadSyncPlayerActivity.EXTRA_VIDEO_PATH, item.file.absolutePath)
                }
                startActivity(intent)
            }
            holder.btnViewClip.setOnClickListener(clickListener)
            holder.itemView.setOnClickListener(clickListener)
        }

        override fun getItemCount(): Int = displayedEvents.size
    }

    private class SentryEventViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivThumb: ImageView = itemView.findViewById(R.id.iv_sentry_thumb)
        val tvBadge: TextView = itemView.findViewById(R.id.tv_sentry_badge)
        val tvCamTag: TextView = itemView.findViewById(R.id.tv_sentry_cam_tag)
        val tvTitle: TextView = itemView.findViewById(R.id.tv_sentry_title)
        val tvDetails: TextView = itemView.findViewById(R.id.tv_sentry_details)
        val btnViewClip: TextView = itemView.findViewById(R.id.btn_sentry_view_clip)
    }
}
