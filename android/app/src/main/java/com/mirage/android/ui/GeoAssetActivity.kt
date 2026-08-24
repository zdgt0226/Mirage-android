package com.mirage.android.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mirage.android.R
import com.mirage.android.core.CoreController
import com.mirage.android.core.GeoManager
import com.mirage.android.core.RuleStore
import com.mirage.android.data.model.Rule
import com.mirage.android.data.model.RuleCondition
import com.mirage.android.databinding.ActivityGeoAssetBinding
import com.mirage.android.databinding.ItemGeoTagBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

/**
 * 独立的 Geo 规则集资产管理中心。
 * 针对流畅度极致优化：
 * 1. 默认静默处理，只有用户点击「读取 Tag 规则集」时才按需后台解析，0 启动负担；
 * 2. 内存热缓存，一旦解析即刻常驻，二次打开秒现；
 * 3. 修复更新源设置点击事件；
 * 4. 精选常用标签体系与虚拟滚动分页。
 */
class GeoAssetActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGeoAssetBinding
    private val allTagList = mutableListOf<GeoManager.GeoTagEntry>()
    private val currentDisplayList = mutableListOf<GeoManager.GeoTagEntry>()
    private var fullFilteredPool: List<GeoManager.GeoTagEntry> = emptyList()
    private lateinit var tagAdapter: TagAdapter
    private var filterJob: Job? = null
    private var currentPage = 1
    private val PAGE_SIZE = 40

    companion object {
        // 精选高频常用 Tag 集合
        val FEATURED_TAGS = setOf(
            "category-ads-all", "cn", "google", "telegram", "youtube", "openai",
            "netflix", "github", "twitter", "bilibili", "apple", "microsoft",
            "geolocation-!cn", "spotify", "steam", "discord", "tiktok", "amazon",
            "meta", "facebook", "instagram", "baidu", "alibaba", "tencent"
        )

        // 国内/直连高频服务
        val CHINA_TAGS = setOf(
            "cn", "bilibili", "alibaba", "tencent", "baidu", "netease", "bytedance",
            "zhihu", "jd", "weibo", "meituan", "kuaishou", "geolocation-cn", "apple-cn", "microsoft-cn"
        )

        // 境外流行服务
        val OVERSEAS_TAGS = setOf(
            "google", "youtube", "telegram", "openai", "netflix", "github", "twitter",
            "discord", "spotify", "steam", "apple", "microsoft", "amazon", "meta",
            "tiktok", "wikimedia", "wikipedia", "gitlab", "docker", "cloudflare", "geolocation-!cn"
        )

        // 广告与隐私拦截
        val ADS_TAGS = setOf(
            "category-ads-all", "category-ads", "category-ads-ir", "anti-ad", "adguard"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGeoAssetBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        setupListeners()
        loadScheduleSetting()
        refreshStatus()

        // 如果已有内存缓存，则直接展示 Tag 列表；否则展示按需触发卡片
        if (GeoManager.hasCachedTags()) {
            showTagsContentImmediately()
        } else {
            showTriggerCard()
        }
    }

    private fun setupRecyclerView() {
        tagAdapter = TagAdapter(
            onAddRule = { entry -> showAddRuleDialog(entry) }
        )
        val layoutManager = LinearLayoutManager(this)
        binding.rvTags.layoutManager = layoutManager
        binding.rvTags.adapter = tagAdapter
        binding.rvTags.setHasFixedSize(true)

        // 滚动到底部自动加载下一页
        binding.rvTags.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) {
                    val totalItemCount = layoutManager.itemCount
                    val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                    if (lastVisibleItem >= totalItemCount - 6) {
                        loadNextPage()
                    }
                }
            }
        })
    }

    private fun setupListeners() {
        binding.btnUpdateNow.setOnClickListener {
            startGeoUpdate()
        }

        // 绑定更新源选择设置弹窗
        binding.btnSelectSource.setOnClickListener {
            showSourceSelectionDialog()
        }

        // 绑定自动更新策略点击弹窗
        binding.cardSchedule.setOnClickListener {
            showScheduleSelectionDialog()
        }

        // 绑定按需读取 Tag 按钮
        binding.btnTriggerLoadTags.setOnClickListener {
            startLoadingTags()
        }

        binding.etSearchTag.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                scheduleFilterTags()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.chipGroupType.setOnCheckedStateChangeListener { _, _ ->
            scheduleFilterTags(immediate = true)
        }
    }

    private fun showTriggerCard() {
        binding.cardTagTrigger.visibility = View.VISIBLE
        binding.layoutTagSearchArea.visibility = View.GONE
        binding.rvTags.visibility = View.GONE
        binding.loadingTagsProgress.visibility = View.GONE
    }

    private fun showTagsContentImmediately() {
        binding.cardTagTrigger.visibility = View.GONE
        binding.layoutTagSearchArea.visibility = View.VISIBLE
        binding.rvTags.visibility = View.VISIBLE
        binding.loadingTagsProgress.visibility = View.GONE

        val res = GeoManager.getTagsDetailFromNative(this)
        allTagList.clear()
        allTagList.addAll(res.geositeTags)
        allTagList.addAll(res.geoipCodes)
        scheduleFilterTags(immediate = true)
    }

    private fun startLoadingTags() {
        binding.cardTagTrigger.visibility = View.GONE
        binding.loadingTagsProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                GeoManager.getTagsDetailFromNative(this@GeoAssetActivity, forceReload = true)
            }
            allTagList.clear()
            allTagList.addAll(res.geositeTags)
            allTagList.addAll(res.geoipCodes)

            binding.loadingTagsProgress.visibility = View.GONE
            binding.layoutTagSearchArea.visibility = View.VISIBLE
            binding.rvTags.visibility = View.VISIBLE
            scheduleFilterTags(immediate = true)
        }
    }

    private fun loadScheduleSetting() {
        val interval = GeoManager.getAutoUpdateInterval(this)
        binding.tvCurrentSchedule.text = "${interval.displayName} ▾"
    }

    private fun showScheduleSelectionDialog() {
        val intervals = arrayOf(
            GeoManager.AutoUpdateInterval.ON_START,
            GeoManager.AutoUpdateInterval.DAILY,
            GeoManager.AutoUpdateInterval.WEEKLY,
            GeoManager.AutoUpdateInterval.NEVER
        )
        val names = intervals.map { it.displayName }.toTypedArray()
        val current = GeoManager.getAutoUpdateInterval(this)
        var selectedIdx = intervals.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("设置自动更新策略")
            .setSingleChoiceItems(names, selectedIdx) { _, which ->
                selectedIdx = which
            }
            .setPositiveButton("确定") { _, _ ->
                val chosen = intervals[selectedIdx]
                GeoManager.setAutoUpdateInterval(this, chosen)
                binding.tvCurrentSchedule.text = "${chosen.displayName} ▾"
                Toast.makeText(this, "自动更新策略已设置为: ${chosen.displayName}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = GeoManager.getGeoStatus(this@GeoAssetActivity)
            val source = GeoManager.getActiveSource(this@GeoAssetActivity)
            val df = DecimalFormat("#.##")
            val siteMb = df.format(status.geositeSize / (1024.0 * 1024.0))
            val ipMb = df.format(status.geoipSize / (1024.0 * 1024.0))

            withContext(Dispatchers.Main) {
                if (status.isReady) {
                    binding.tvGeoStatusBadge.text = "已就绪"
                    binding.tvGeoStatusBadge.setTextColor(Color.parseColor("#10B981"))
                } else {
                    binding.tvGeoStatusBadge.text = "未就绪"
                    binding.tvGeoStatusBadge.setTextColor(Color.parseColor("#EF4444"))
                }

                binding.tvGeoDetails.text = buildString {
                    appendLine("• 当前规则源: ${source.name}")
                    appendLine("• GeoSite 域名集: ${siteMb} MB (${status.geositeTagCount} 个标签)")
                    appendLine("• GeoIP 地址集: ${ipMb} MB (${status.geoipCodeCount} 个网段代码)")
                    appendLine("• 最近更新: ${status.lastUpdateTime}")
                }
            }
        }
    }

    private fun scheduleFilterTags(immediate: Boolean = false) {
        filterJob?.cancel()
        filterJob = lifecycleScope.launch {
            if (!immediate) delay(100)
            val query = binding.etSearchTag.text.toString().trim().lowercase()
            val isFeatured = binding.chipFeatured.isChecked
            val isChina = binding.chipChina.isChecked
            val isOverseas = binding.chipOverseas.isChecked
            val isAds = binding.chipAds.isChecked

            val filtered = withContext(Dispatchers.Default) {
                if (query.isNotEmpty()) {
                    allTagList.filter { it.tag.lowercase().contains(query) }
                        .sortedWith(compareBy(
                            { !it.tag.lowercase().startsWith(query) },
                            { it.tag.length }
                        ))
                } else {
                    when {
                        isFeatured -> allTagList.filter { FEATURED_TAGS.contains(it.tag.lowercase()) }
                        isChina -> allTagList.filter { CHINA_TAGS.contains(it.tag.lowercase()) }
                        isOverseas -> allTagList.filter { OVERSEAS_TAGS.contains(it.tag.lowercase()) }
                        isAds -> allTagList.filter { ADS_TAGS.contains(it.tag.lowercase()) || it.tag.lowercase().contains("ad") }
                        else -> allTagList
                    }
                }
            }

            fullFilteredPool = filtered
            currentPage = 1
            currentDisplayList.clear()

            val initialCount = minOf(PAGE_SIZE, fullFilteredPool.size)
            currentDisplayList.addAll(fullFilteredPool.subList(0, initialCount))
            tagAdapter.submitList(currentDisplayList.toList())

            val statusText = if (query.isNotEmpty()) {
                "搜索「$query」共匹配 ${fullFilteredPool.size} 个标签"
            } else when {
                isFeatured -> "已展示 ${currentDisplayList.size} 个精选高频标签 (可搜索全量 ${allTagList.size} 个)"
                isChina -> "国内常用标签 (${currentDisplayList.size} 个)"
                isOverseas -> "境外流行服务标签 (${currentDisplayList.size} 个)"
                isAds -> "广告拦截与隐私标签 (${currentDisplayList.size} 个)"
                else -> "全量标签: 已载入 ${currentDisplayList.size}/${fullFilteredPool.size} 个 (向下滑动加载更多)"
            }
            binding.tvTagMatchCount.text = statusText
        }
    }

    private fun loadNextPage() {
        if (currentDisplayList.size >= fullFilteredPool.size) return
        val nextStart = currentDisplayList.size
        val nextEnd = minOf(nextStart + PAGE_SIZE, fullFilteredPool.size)
        if (nextStart < nextEnd) {
            currentDisplayList.addAll(fullFilteredPool.subList(nextStart, nextEnd))
            tagAdapter.submitList(currentDisplayList.toList())
            if (binding.chipAll.isChecked && binding.etSearchTag.text.isNullOrBlank()) {
                binding.tvTagMatchCount.text = "全量标签: 已载入 ${currentDisplayList.size}/${fullFilteredPool.size} 个 (向下滑动加载更多)"
            }
        }
    }

    private fun startGeoUpdate() {
        binding.btnUpdateNow.isEnabled = false
        binding.progressUpdate.visibility = View.VISIBLE
        binding.tvProgressMsg.visibility = View.VISIBLE
        binding.progressUpdate.progress = 0

        lifecycleScope.launch {
            val result = GeoManager.updateGeoFiles(this@GeoAssetActivity) { msg, progress ->
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.tvProgressMsg.text = msg
                    binding.progressUpdate.progress = progress
                }
            }

            binding.btnUpdateNow.isEnabled = true
            binding.progressUpdate.visibility = View.GONE
            binding.tvProgressMsg.visibility = View.GONE

            if (result.success) {
                Toast.makeText(this@GeoAssetActivity, result.message, Toast.LENGTH_LONG).show()
                refreshStatus()
                if (binding.rvTags.visibility == View.VISIBLE) {
                    startLoadingTags()
                }
            } else {
                Toast.makeText(this@GeoAssetActivity, "更新失败: ${result.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSourceSelectionDialog() {
        val sources = GeoManager.getSources(this)
        val active = GeoManager.getActiveSource(this)
        val names = sources.map { it.name }.toTypedArray()
        var selectedIdx = sources.indexOfFirst { it.id == active.id }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("选择 Geo 规则更新源")
            .setSingleChoiceItems(names, selectedIdx) { _, which ->
                selectedIdx = which
            }
            .setPositiveButton("确定") { _, _ ->
                val chosen = sources[selectedIdx]
                GeoManager.setActiveSource(this, chosen.id)
                refreshStatus()
                Toast.makeText(this, "已切换更新源为: ${chosen.name}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("添加自定义源") { _, _ ->
                showAddCustomSourceDialog()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddCustomSourceDialog() {
        val etName = EditText(this).apply { hint = "规则源名称 (如: 我的私有源)" }
        val etSiteUrl = EditText(this).apply { hint = "geosite.dat 下载 URL" }
        val etIpUrl = EditText(this).apply { hint = "geoip.dat 下载 URL" }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
            addView(etName)
            addView(etSiteUrl)
            addView(etIpUrl)
        }

        AlertDialog.Builder(this)
            .setTitle("添加自定义 Geo 规则源")
            .setView(layout)
            .setPositiveButton("保存并使用") { _, _ ->
                val name = etName.text.toString().trim()
                val siteUrl = etSiteUrl.text.toString().trim()
                val ipUrl = etIpUrl.text.toString().trim()
                if (name.isNotEmpty() && siteUrl.isNotEmpty() && ipUrl.isNotEmpty()) {
                    val id = "custom_" + System.currentTimeMillis()
                    val newSource = GeoManager.GeoSource(id, name, siteUrl, ipUrl, false)
                    val list = GeoManager.getSources(this).toMutableList()
                    list.add(newSource)
                    GeoManager.saveCustomSources(this, list)
                    GeoManager.setActiveSource(this, id)
                    refreshStatus()
                    Toast.makeText(this, "已添加并激活自定义源: $name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddRuleDialog(entry: GeoManager.GeoTagEntry) {
        val actions = arrayOf("🚀 走代理 (Proxy)", "⚡ 走直连 (Direct)", "🚫 拦截阻断 (Block)")
        val actionValues = arrayOf("proxy", "direct", "block")
        var selectedAction = 0

        AlertDialog.Builder(this)
            .setTitle("将标签「${entry.tag}」添加为分流规则")
            .setSingleChoiceItems(actions, selectedAction) { _, which ->
                selectedAction = which
            }
            .setPositiveButton("添加") { _, _ ->
                val chosenAction = actionValues[selectedAction]
                val kind = if (entry.isGeoSite) "geosite" else "geoip"
                val type = if (entry.isGeoSite) "geosite" else "geoip"
                val name = (if (entry.isGeoSite) "GeoSite: " else "GeoIP: ") + entry.tag
                val rule = Rule(
                    name = name,
                    enabled = true,
                    logic = "OR",
                    conditions = listOf(RuleCondition(kind, entry.tag.lowercase())),
                    type = type,
                    kind = kind,
                    pattern = entry.tag.lowercase(),
                    action = chosenAction
                )
                RuleStore.addRule(this, rule)
                val ok = CoreController.setRules(RuleStore.toJson(this))
                Toast.makeText(this, if (ok) "已成功添加并生效分流规则: $name" else "规则添加成功", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    inner class TagAdapter(
        private val onAddRule: (GeoManager.GeoTagEntry) -> Unit
    ) : ListAdapter<GeoManager.GeoTagEntry, TagAdapter.TagViewHolder>(TagDiffCallback) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagViewHolder {
            val binding = ItemGeoTagBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return TagViewHolder(binding)
        }

        override fun onBindViewHolder(holder: TagViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class TagViewHolder(private val binding: ItemGeoTagBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(entry: GeoManager.GeoTagEntry) {
                binding.tvTagName.text = entry.tag
                if (entry.isGeoSite) {
                    binding.tvTagType.text = "GEOSITE"
                    binding.tvTagType.setTextColor(Color.parseColor("#0077CC"))
                    binding.tvTagCount.text = "包含 ${entry.count} 条域名规则"
                } else {
                    binding.tvTagType.text = "GEOIP"
                    binding.tvTagType.setTextColor(Color.parseColor("#10B981"))
                    binding.tvTagCount.text = "包含 ${entry.count} 个 IP/CIDR 网段"
                }

                binding.btnAddRule.setOnClickListener {
                    onAddRule(entry)
                }
            }
        }
    }

    object TagDiffCallback : DiffUtil.ItemCallback<GeoManager.GeoTagEntry>() {
        override fun areItemsTheSame(oldItem: GeoManager.GeoTagEntry, newItem: GeoManager.GeoTagEntry): Boolean =
            oldItem.tag == newItem.tag && oldItem.isGeoSite == newItem.isGeoSite

        override fun areContentsTheSame(oldItem: GeoManager.GeoTagEntry, newItem: GeoManager.GeoTagEntry): Boolean =
            oldItem == newItem
    }
}
