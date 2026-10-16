package com.vongzh.sophon.ui.photo

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vongzh.sophon.R
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.domain.model.RecordCategory
import com.vongzh.sophon.service.SophonCoreService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极视座舱相册图库组件 (PhotoViewerFragment)
 *
 * 【4 大 KPI 指标卡 + 实时搜索/刷新/多选 + 响应式网格流 + 沉浸式大图浮层】
 */
class PhotoViewerFragment : Fragment() {

    data class PhotoItem(
        val file: File,
        val camTag: String,
        val formattedTime: String,
        val dateDay: String,
        val sizeFormatted: String,
        val sizeBytes: Long,
        val timestampMs: Long
    )

    // KPI 视图
    private lateinit var tvKpiTotal: TextView
    private lateinit var tvKpiToday: TextView
    private lateinit var tvKpiSize: TextView
    private lateinit var tvKpiTrash: TextView

    // 搜索与工具操作
    private lateinit var etSearch: EditText
    private lateinit var btnRefresh: View
    private lateinit var ivRefreshIcon: ImageView
    private lateinit var btnSelectAll: TextView
    private lateinit var btnMultiSelect: TextView
    private lateinit var tvListHeader: TextView
    private lateinit var btnSort: TextView

    // 网格列表与空状态
    private lateinit var rvPhotos: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var layoutEmpty: View
    private lateinit var btnEmptyTakePhoto: View
    private val allPhotos = mutableListOf<PhotoItem>()
    private val displayedPhotos = mutableListOf<PhotoItem>()
    private val selectedItems = mutableSetOf<PhotoItem>()
    private var isMultiSelectMode = false
    private var isSortDescending = true

    // 大图预览浮层
    private lateinit var layoutFullscreen: FrameLayout
    private lateinit var ivFullscreen: ImageView
    private lateinit var tvFullscreenTitle: TextView
    private lateinit var tvFullscreenIndex: TextView
    private lateinit var btnFullscreenClose: TextView
    private lateinit var btnFullscreenDelete: TextView
    private lateinit var btnFullscreenPrev: View
    private lateinit var btnFullscreenNext: View
    private var currentFullscreenIndex = 0

    private val thumbCache = LruCache<String, Bitmap>(60)

    /** 列表行：日期分组头 + 照片 */
    sealed class Row {
        data class Header(val dateDay: String, val count: Int, val bytes: Long) : Row()
        data class Photo(val item: PhotoItem) : Row()
    }
    private val displayRows = mutableListOf<Row>()
    private val adapter = PhotoGridAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_photo_viewer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupListeners()
        observeSnapshotEvents()
        loadPhotosFromUsb()
    }

    override fun onResume() {
        super.onResume()
        loadPhotosFromUsb()
    }

    private fun observeSnapshotEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SophonCoreService.snapshotEventFlow.collect { result ->
                    if (result.success) {
                        loadPhotosFromUsb()
                    }
                }
            }
        }
    }

    private fun initViews(root: View) {
        tvKpiTotal = root.findViewById(R.id.tv_kpi_photo_total)
        tvKpiToday = root.findViewById(R.id.tv_kpi_photo_today)
        tvKpiSize = root.findViewById(R.id.tv_kpi_photo_size)
        tvKpiTrash = root.findViewById(R.id.tv_kpi_photo_trash)

        etSearch = root.findViewById(R.id.et_photo_search)
        btnRefresh = root.findViewById(R.id.btn_photo_refresh)
        ivRefreshIcon = root.findViewById(R.id.iv_photo_refresh_icon)
        btnSelectAll = root.findViewById(R.id.btn_photo_select_all)
        btnMultiSelect = root.findViewById(R.id.btn_photo_multi_select)
        tvListHeader = root.findViewById(R.id.tv_photo_list_header)
        btnSort = root.findViewById(R.id.btn_photo_sort)

        rvPhotos = root.findViewById(R.id.rv_photos)
        tvEmpty = root.findViewById(R.id.tv_empty_hint)
        layoutEmpty = root.findViewById(R.id.layout_empty_photos)
        btnEmptyTakePhoto = root.findViewById(R.id.btn_empty_take_photo)

        // 大图浮层
        layoutFullscreen = root.findViewById(R.id.layout_fullscreen_viewer)
        ivFullscreen = root.findViewById(R.id.iv_fullscreen_display)
        tvFullscreenTitle = root.findViewById(R.id.tv_fullscreen_title)
        tvFullscreenIndex = root.findViewById(R.id.tv_fullscreen_index)
        btnFullscreenClose = root.findViewById(R.id.btn_fullscreen_close)
        btnFullscreenDelete = root.findViewById(R.id.btn_fullscreen_delete)
        btnFullscreenPrev = root.findViewById(R.id.btn_fullscreen_prev)
        btnFullscreenNext = root.findViewById(R.id.btn_fullscreen_next)

        // 网格布局：每行 4 列
        val glm = GridLayoutManager(requireContext(), 4)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (displayRows.getOrNull(position) is Row.Header) 4 else 1
        }
        rvPhotos.layoutManager = glm
        rvPhotos.adapter = adapter
    }

    private fun setupListeners() {
        btnRefresh.setOnClickListener {
            com.vongzh.sophon.ui.widget.UiMotionUtils.startRefreshAnimation(btnRefresh, ivRefreshIcon) {
                loadPhotosFromUsb()
                Toast.makeText(context, "已刷新图库", Toast.LENGTH_SHORT).show()
            }
        }

        btnEmptyTakePhoto.setOnClickListener {
            val intent = Intent(requireContext(), com.vongzh.sophon.service.SophonCoreService::class.java).apply {
                action = com.vongzh.sophon.service.SophonCoreService.ACTION_TRIGGER_SNAPSHOT
            }
            requireContext().startService(intent)
            Toast.makeText(context, "已触发抓拍，照片生成中...", Toast.LENGTH_SHORT).show()
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(1200)
                loadPhotosFromUsb()
            }
        }

        view?.findViewById<View>(R.id.btn_photo_lan_share)?.setOnClickListener {
            activity?.let { act ->
                com.vongzh.sophon.ui.lan.LanShareDialog(act).show()
            }
        }

        btnSelectAll.setOnClickListener {
            if (!isMultiSelectMode) return@setOnClickListener
            val isAllSelected = displayedPhotos.isNotEmpty() && selectedItems.size == displayedPhotos.size
            if (isAllSelected) {
                selectedItems.clear()
            } else {
                selectedItems.clear()
                selectedItems.addAll(displayedPhotos)
            }
            updateMultiSelectUi()
            adapter.notifyDataSetChanged()
        }

        btnMultiSelect.setOnClickListener {
            if (isMultiSelectMode && selectedItems.isNotEmpty()) {
                showBatchActionDialog()
            } else {
                isMultiSelectMode = !isMultiSelectMode
                selectedItems.clear()
                updateMultiSelectUi()
                adapter.notifyDataSetChanged()
            }
        }

        btnSort.setOnClickListener {
            isSortDescending = !isSortDescending
            btnSort.text = if (isSortDescending) "按时间倒序 ▾" else "按时间正序 ▴"
            applyFilterAndSort()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilterAndSort()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 大图操作
        btnFullscreenClose.setOnClickListener {
            layoutFullscreen.visibility = View.GONE
        }

        btnFullscreenPrev.setOnClickListener {
            if (displayedPhotos.isNotEmpty()) {
                // 触觉弹性反馈
                it.animate().cancel()
                it.scaleX = 0.88f
                it.scaleY = 0.88f
                it.animate().scaleX(1.0f).scaleY(1.0f).setDuration(160).start()

                currentFullscreenIndex = if (currentFullscreenIndex > 0) currentFullscreenIndex - 1 else displayedPhotos.size - 1
                renderFullscreenImage(direction = -1)
            }
        }

        btnFullscreenNext.setOnClickListener {
            if (displayedPhotos.isNotEmpty()) {
                // 触觉弹性反馈
                it.animate().cancel()
                it.scaleX = 0.88f
                it.scaleY = 0.88f
                it.animate().scaleX(1.0f).scaleY(1.0f).setDuration(160).start()

                currentFullscreenIndex = if (currentFullscreenIndex < displayedPhotos.size - 1) currentFullscreenIndex + 1 else 0
                renderFullscreenImage(direction = 1)
            }
        }

        btnFullscreenDelete.setOnClickListener {
            if (displayedPhotos.isNotEmpty() && currentFullscreenIndex in displayedPhotos.indices) {
                val item = displayedPhotos[currentFullscreenIndex]
                if (item.file.delete()) {
                    Toast.makeText(context, "照片已删除", Toast.LENGTH_SHORT).show()
                    loadPhotosFromUsb()
                    layoutFullscreen.visibility = View.GONE
                }
            }
        }
    }

    private fun showBatchActionDialog() {
        val items = selectedItems.toList()
        val labels = arrayOf("批量删除（${items.size} 张照片）", "取消选择")
        AlertDialog.Builder(requireContext())
            .setTitle("批量管理 · 已选 ${items.size} 张")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> confirmBatchDelete(items)
                    else -> {
                        selectedItems.clear()
                        isMultiSelectMode = false
                        updateMultiSelectUi()
                        adapter.notifyDataSetChanged()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmBatchDelete(items: List<PhotoItem>) {
        AlertDialog.Builder(requireContext())
            .setTitle("确认批量删除？")
            .setMessage("将永久抹除选中的 ${items.size} 张抓拍照片，操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    var ok = 0
                    for (item in items) {
                        if (item.file.delete()) ok++
                    }
                    val deleted = ok
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "已删除 $deleted / ${items.size} 张照片", Toast.LENGTH_SHORT).show()
                        selectedItems.clear()
                        isMultiSelectMode = false
                        updateMultiSelectUi()
                        loadPhotosFromUsb()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateMultiSelectUi() {
        if (isMultiSelectMode) {
            btnSelectAll.visibility = View.VISIBLE
            val isAllSelected = displayedPhotos.isNotEmpty() && selectedItems.size == displayedPhotos.size
            btnSelectAll.text = if (isAllSelected) "取消全选" else "全选"
            val selColor = if (isAllSelected) requireContext().getColor(R.color.sp_ac) else requireContext().getColor(R.color.sp_tx)
            btnSelectAll.setTextColor(selColor)
            btnSelectAll.compoundDrawableTintList = ColorStateList.valueOf(if (isAllSelected) requireContext().getColor(R.color.sp_ac) else requireContext().getColor(R.color.sp_tx2))
            btnSelectAll.setBackgroundResource(if (isAllSelected) R.drawable.sp_bg_pill_accent else R.drawable.sp_bg_pill)

            btnMultiSelect.text = "批量管理 · 已选 ${selectedItems.size}"
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
        updateSelectedKpi()
    }

    /** 第四张 KPI 卡改为真实指标「本月新增」，原实现显示的是当前选中项数，属语义错配 */
    private fun updateSelectedKpi() {
        val monthPrefix = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        tvKpiTrash.text = "${allPhotos.count { it.dateDay.startsWith(monthPrefix) }} 张"
    }

    fun loadPhotosFromUsb() {
        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { scanPhotos() }
            allPhotos.clear()
            allPhotos.addAll(items)
            updateKpiCards()
            applyFilterAndSort()
        }
    }

    private fun scanPhotos(): List<PhotoItem> {
        val storage = SophonApp.storageVault
        val usbRoot = storage.currentUsbRoot.value
        val photoDirs = mutableListOf<File>()

        // 1. 优先扫描外接 U 盘目录
        if (usbRoot != null && storage.isUsbMounted.value) {
            val usbSnapshotDir = File(File(usbRoot, "SophonRecord"), RecordCategory.SNAPSHOT.dirName)
            if (usbSnapshotDir.exists() && usbSnapshotDir.isDirectory) {
                photoDirs.add(usbSnapshotDir)
            }
        }

        // 2. 扫描应用私有沙盒目录（无外接存储或测试环境下的抓拍）
        context?.let { ctx ->
            val internalSnapshotDir = File(ctx.getExternalFilesDir(null), "SophonRecord/${RecordCategory.SNAPSHOT.dirName}")
            if (internalSnapshotDir.exists() && internalSnapshotDir.isDirectory) {
                photoDirs.add(internalSnapshotDir)
            }
        }

        if (photoDirs.isEmpty()) return emptyList()

        val files = photoDirs.flatMap { dir ->
            dir.listFiles()?.filter {
                it.isFile && (it.extension.equals("jpg", ignoreCase = true) || it.extension.equals("png", ignoreCase = true))
            } ?: emptyList()
        }.distinctBy { it.absolutePath }

        val parser = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        return files.map { file ->
            val name = file.name
            val timePart = name.removePrefix("SNAP_").substringBeforeLast(".")
            val timeMs = try {
                parser.parse(timePart)?.time ?: file.lastModified()
            } catch (e: Exception) {
                file.lastModified()
            }

            val isSandbox = file.absolutePath.contains("Android/data") ||
                    (context?.packageName?.let { file.absolutePath.contains(it) } == true)
            val camTag = if (isSandbox) "四路合成 (机身沙盒)" else "四路合成抓拍"


            val sizeBytes = file.length()
            val sizeMb = String.format(Locale.getDefault(), "%.1f MB", sizeBytes.toDouble() / (1024 * 1024))

            PhotoItem(
                file = file,
                camTag = camTag,
                formattedTime = timeFmt.format(Date(timeMs)),
                dateDay = dayFmt.format(Date(timeMs)),
                sizeFormatted = sizeMb,
                sizeBytes = sizeBytes,
                timestampMs = timeMs
            )
        }
    }

    private fun updateKpiCards() {
        val totalCount = allPhotos.size
        tvKpiTotal.text = "$totalCount 张"

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val todayCount = allPhotos.count { it.dateDay == todayStr }
        tvKpiToday.text = "$todayCount 张"

        val totalBytes = allPhotos.sumOf { it.sizeBytes }
        val mb = totalBytes.toDouble() / (1024 * 1024)
        tvKpiSize.text = if (mb >= 1024) {
            String.format(Locale.getDefault(), "%.2f GB", mb / 1024)
        } else {
            String.format(Locale.getDefault(), "%.1f MB", mb)
        }

    }

    private fun applyFilterAndSort() {
        val query = etSearch.text.toString().trim()
        val filtered = allPhotos.filter {
            query.isEmpty() || it.camTag.contains(query, ignoreCase = true) || it.formattedTime.contains(query) || it.dateDay.contains(query)
        }

        displayedPhotos.clear()
        if (isSortDescending) {
            displayedPhotos.addAll(filtered.sortedByDescending { it.timestampMs })
        } else {
            displayedPhotos.addAll(filtered.sortedBy { it.timestampMs })
        }
        rebuildRows()

        tvListHeader.text = "全部照片 (共 ${displayedPhotos.size} 张)"
        layoutEmpty.visibility = if (displayRows.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun showFullscreenPhoto(index: Int) {
        if (index !in displayedPhotos.indices) return
        currentFullscreenIndex = index
        layoutFullscreen.visibility = View.VISIBLE
        renderFullscreenImage(direction = 0)
    }

    private fun renderFullscreenImage(direction: Int = 0) {
        val item = displayedPhotos.getOrNull(currentFullscreenIndex) ?: return
        tvFullscreenTitle.text = "${item.camTag} · ${item.dateDay} ${item.formattedTime}"
        tvFullscreenIndex.text = "${currentFullscreenIndex + 1} / ${displayedPhotos.size}"

        val filePath = item.file.absolutePath
        ivFullscreen.tag = filePath

        // 仅当多于 1 张图片时允许翻页，并更新翻页按钮可用透明度
        btnFullscreenPrev.alpha = if (displayedPhotos.size > 1) 1.0f else 0.35f
        btnFullscreenNext.alpha = if (displayedPhotos.size > 1) 1.0f else 0.35f

        // 切换动效：如果有翻页方向，先做细微平移动画与淡入
        if (direction != 0) {
            val startTranslationX = if (direction > 0) 40f else -40f
            ivFullscreen.translationX = startTranslationX
            ivFullscreen.alpha = 0.5f
            ivFullscreen.animate()
                .translationX(0f)
                .alpha(1.0f)
                .setDuration(180)
                .start()
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val bmp = try {
                BitmapFactory.decodeFile(filePath)
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (ivFullscreen.tag == filePath) {
                    ivFullscreen.setImageBitmap(bmp)
                }
            }
        }
    }

    /** 按日期把照片组织成分组列表 */
    private fun rebuildRows() {
        displayRows.clear()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = dayFmt.format(Date())
        displayedPhotos.groupBy { it.dateDay }.forEach { (day, items) ->
            val label = if (day == todayStr) "今天 · $day" else day
            displayRows.add(Row.Header(label, items.size, items.sumOf { it.sizeBytes }))
            items.forEach { displayRows.add(Row.Photo(it)) }
        }
    }

    inner class PhotoGridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int =
            if (displayRows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderViewHolder(inflater.inflate(R.layout.item_date_header, parent, false))
            } else {
                PhotoViewHolder(inflater.inflate(R.layout.item_photo_grid, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = displayRows[position]) {
                is Row.Header -> (holder as HeaderViewHolder).bind(row)
                is Row.Photo -> (holder as PhotoViewHolder).bind(row.item)
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
                    "${row.count} 张 · ${String.format(Locale.getDefault(), "%.2f", mb / 1024)} GB"
                } else {
                    "${row.count} 张 · ${String.format(Locale.getDefault(), "%.1f", mb)} MB"
                }
            }
        }

        inner class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val ivThumb: ImageView = view.findViewById(R.id.iv_grid_thumb)
            private val tvCamTag: TextView = view.findViewById(R.id.tv_grid_cam_tag)
            private val ivCheck: ImageView = view.findViewById(R.id.iv_grid_check)
            private val tvTime: TextView = view.findViewById(R.id.tv_grid_time)
            private val tvSize: TextView = view.findViewById(R.id.tv_grid_size)

            fun bind(item: PhotoItem) {
                val isChecked = selectedItems.contains(item)

                tvCamTag.text = item.camTag
                tvTime.text = item.formattedTime
                tvSize.text = item.sizeFormatted

                if (isMultiSelectMode) {
                    ivCheck.visibility = View.VISIBLE
                    ivCheck.setImageResource(if (isChecked) R.drawable.ic_action_check else R.drawable.sp_bg_circle_glass)
                    ivCheck.setColorFilter(if (isChecked) requireContext().getColor(R.color.sp_ac) else requireContext().getColor(R.color.sp_tx3))
                } else {
                    ivCheck.visibility = View.GONE
                }

                ivThumb.tag = item.file.absolutePath
                ivThumb.setImageDrawable(null)

                val cached = thumbCache.get(item.file.absolutePath)
                if (cached != null) {
                    ivThumb.setImageBitmap(cached)
                } else {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                        val bmp = try {
                            BitmapFactory.decodeFile(item.file.absolutePath, opts)
                        } catch (e: Exception) {
                            null
                        }
                        bmp?.let { thumbCache.put(item.file.absolutePath, it) }
                        withContext(Dispatchers.Main) {
                            if (ivThumb.tag == item.file.absolutePath) {
                                ivThumb.setImageBitmap(bmp)
                            }
                        }
                    }
                }

                itemView.setOnClickListener {
                    if (isMultiSelectMode) {
                        if (selectedItems.contains(item)) selectedItems.remove(item) else selectedItems.add(item)
                        updateMultiSelectUi()
                        notifyItemChanged(bindingAdapterPosition)
                    } else {
                        // 大图索引需基于照片行，排除日期头
                        val photoIndex = displayedPhotos.indexOf(item)
                        if (photoIndex >= 0) showFullscreenPhoto(photoIndex)
                    }
                }
            }
        }
    }
}
