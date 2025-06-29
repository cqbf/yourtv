package com.horsenma.yourtv

import android.content.Context
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.horsenma.yourtv.databinding.MenuBinding
import com.horsenma.yourtv.models.TVListModel
import com.horsenma.yourtv.models.TVModel
import java.io.File
import androidx.core.content.edit
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch


class MenuFragment : Fragment(), GroupAdapter.ItemListener, TVListAdapter.ItemListener {
    private var _binding: MenuBinding? = null
    private val binding get() = _binding!!

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var listAdapter: TVListAdapter

    private var groupWidth = 0
    private var listWidth = 0

    private val cachedSources = LinkedHashMap<String, String>()
    private lateinit var viewModel: MainViewModel
    private var currentTestCodeIndex: Int = 0 // 实际源索引
    private var displaySourceIndex: Int = 0 // 显示源索引
    private var lastSwitchSourceTime: Long = 0L
    private var listenersBound: Boolean = false
    private var clickCount: Int = 0
    private var lastClickTime: Long = System.currentTimeMillis() // 初始化为当前时间

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = MenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireActivity()
        val application = context.applicationContext as YourTVApplication
        viewModel = ViewModelProvider(context)[MainViewModel::class.java]

        groupAdapter = GroupAdapter(context, binding.group, viewModel.groupModel)
        binding.group.adapter = groupAdapter
        binding.group.layoutManager = LinearLayoutManager(context)
        groupWidth = application.px2Px(binding.group.layoutParams.width)
        binding.group.layoutParams.width = if (SP.compactMenu) {
            groupWidth * 2 / 3
        } else {
            groupWidth
        }
        groupAdapter.setItemListener(this)

        listAdapter = TVListAdapter(context, binding.list, this)
        listAdapter.setItemListener(this)
        binding.list.adapter = listAdapter
        binding.list.layoutManager = LinearLayoutManager(context)
        listWidth = application.px2Px(binding.list.layoutParams.width)
        binding.list.layoutParams.width = if (SP.compactMenu) {
            listWidth * 4 / 5
        } else {
            listWidth
        }

        var lastClickTime = 0L
        binding.menu.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastClickTime > 1000) {
                hideSelf()
                lastClickTime = currentTime
            }
            (activity as? MainActivity)?.menuActive()
        }

        groupAdapter.focusable(true)
        listAdapter.focusable(true)

        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.post { requestFocus() }

        // 根视图按键监听
        binding.root.isFocusable = true
        binding.root.isFocusableInTouchMode = true
        binding.group.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && isVisible && isAdded) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val layoutManager = binding.group.layoutManager as? LinearLayoutManager
                        val firstVisiblePosition = layoutManager?.findFirstCompletelyVisibleItemPosition() ?: -1
                        if (firstVisiblePosition == 0) {
                            binding.sourceSwitcherPrev.requestFocus()
                            //Log.d(TAG, "Group: Key DPAD_UP pressed at top, focus to source_switcher_prev")
                            return@setOnKeyListener true
                        }
                        return@setOnKeyListener false
                    }
                }
            }
            false
        }

        val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                val mainActivity = activity as? MainActivity
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        mainActivity?.menuActive()
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        mainActivity?.menuActive()
                    }
                }
            }
        }

        val onTouchListener = View.OnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                v.requestFocus()
                (activity as? MainActivity)?.menuActive()
                //Log.d(TAG, "Touch on ${v.id}, focus requested")
            }
            false
        }

        binding.group.addOnScrollListener(scrollListener)
        binding.list.addOnScrollListener(scrollListener)
        binding.menu.setOnTouchListener(onTouchListener)

        // 注册广播接收器
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.horsenma.yourtv.TEST_CODE_EXPIRED") {
                    Log.d(TAG, "Received test code expired broadcast")
                    setupSourceSwitcher()
                }
            }
        }
        val filter = IntentFilter("com.horsenma.yourtv.TEST_CODE_EXPIRED")
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 在 onDestroyView 中注销
        viewLifecycleOwner.lifecycle.addObserver(object : LifecycleObserver {
            @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            fun onDestroy() {
                context?.unregisterReceiver(receiver)
            }
        })

    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            onVisible()
            view?.post { requestFocus() }
        } else {
            view?.post {
                groupAdapter.visible = false
                listAdapter.setVisible(false)
            }
        }
    }

    private fun getList(): TVListModel? {
        if (!this::viewModel.isInitialized) {
            return null
        }
        if (viewModel.groupModel.getCurrentList() == null) {
            viewModel.groupModel.setPosition(0)
        }
        return viewModel.groupModel.getCurrentList()
    }

    fun update() {
        view?.post {
            groupAdapter.changed()
            getList()?.let { listModel ->
                listAdapter.update(listModel)
                //Log.d(TAG, "MenuFragment: Updated list with ${listModel.tvList.value?.size} items")
            }
        }
    }

    fun updateSize() {
        view?.post {
            binding.group.layoutParams.width = if (SP.compactMenu) {
                groupWidth * 4 / 5 // 统一为 4/5，与 mytv1 一致
            } else {
                groupWidth
            }
            binding.list.layoutParams.width = if (SP.compactMenu) {
                listWidth * 4 / 5
            } else {
                listWidth
            }
        }
    }

    fun updateList(position: Int) {
        if (!this::viewModel.isInitialized) {
            return
        }
        viewModel.groupModel.setPosition(position)
        SP.positionGroup = position
        viewModel.groupModel.getCurrentList()?.let {
            listAdapter.update(it)
            listAdapter.toPosition(it.positionPlayingValue)
        }
    }

    private fun hideSelf() {
        if (!isAdded || activity == null || requireActivity().isFinishing) {
            //Log.w(TAG, "hideSelf: Fragment not added or Activity finishing, skipping")
            return
        }
        try {
            requireActivity().supportFragmentManager.beginTransaction()
                .hide(this)
                .commitAllowingStateLoss()
            //Log.d(TAG, "MenuFragment hidden")
        } catch (e: IllegalStateException) {
            //Log.e(TAG, "hideSelf: Failed to commit transaction", e)
        }
    }

    // GroupAdapter.ItemListener
    override fun onItemFocusChange(listTVModel: TVListModel, hasFocus: Boolean) {
        if (hasFocus) {
            listAdapter.update(listTVModel)
            (activity as? MainActivity)?.menuActive()
        }
    }

    // TVListAdapter.ItemListener
    override fun onItemFocusChange(tvModel: TVModel, hasFocus: Boolean) {
    }

    // GroupAdapter.ItemListener
    override fun onItemClicked(position: Int) {
        if (!this::viewModel.isInitialized) return
        updateList(position)
        groupAdapter.focusable(true)
        listAdapter.focusable(false)
        binding.group.requestFocus()
        groupAdapter.scrollToPositionAndSelect(position)
        (activity as? MainActivity)?.menuActive() // 重置计时器
        //Log.d(TAG, "MenuFragment: Group item clicked, focusing on position $position")
    }

    // TVListAdapter.ItemListener
    override fun onItemClicked(position: Int, type: String) {
        if (!this::viewModel.isInitialized) return
        viewModel.groupModel.setPositionPlaying()
        viewModel.groupModel.getCurrentList()?.let {
            it.setPosition(position)
            it.setPositionPlaying()
            it.getCurrent()?.let { tvModel ->
                Log.d(TAG, "onItemClicked: Channel=${tvModel.tv.title}, uris=${tvModel.tv.uris}")
                tvModel.setReady()
                viewModel.setCurrentTvModel(tvModel)
                viewModel.triggerPlay(tvModel)
                Log.d(TAG, "onItemClicked: Triggered play for channel: ${tvModel.tv.title}")
            }
        }
        (activity as? MainActivity)?.menuActive()
        hideSelf()
    }

    // GroupAdapter.ItemListener 的 onKey
    override fun onKey(keyCode: Int): Boolean {
        val mainActivity = activity as? MainActivity
        mainActivity?.menuActive()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                return false
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (listAdapter.itemCount == 0) return true
                listAdapter.focusable(true)
                groupAdapter.focusable(false)
                val position = if ((viewModel.groupModel.getCurrentList()?.positionPlayingValue ?: 0) >= 0 &&
                    (viewModel.groupModel.getCurrentList()?.positionPlayingValue ?: 0) < listAdapter.itemCount) {
                    viewModel.groupModel.getCurrentList()?.positionPlayingValue ?: 0
                } else {
                    0 // 默认滚动到顶部
                }
                // 重试机制，最多尝试 3 次，间隔 200ms
                fun trySetFocus(attempt: Int = 1) {
                    binding.list.postDelayed({
                        if (isAdded && isVisible) {
                            (binding.list.layoutManager as? LinearLayoutManager)?.scrollToPosition(position)
                            val holder = binding.list.findViewHolderForAdapterPosition(position)
                            if (holder != null) {
                                holder.itemView.isFocusable = true
                                holder.itemView.isFocusableInTouchMode = true
                                holder.itemView.requestFocus()
                                Log.d(TAG, "Focus set to list item at position $position (attempt $attempt)")
                            } else if (attempt < 3) {
                                Log.d(TAG, "No ViewHolder found at position $position (attempt $attempt), retrying")
                                trySetFocus(attempt + 1)
                            } else {
                                binding.list.isFocusable = true
                                binding.list.isFocusableInTouchMode = true
                                binding.list.requestFocus()
                                Log.d(TAG, "No ViewHolder found at position $position after $attempt attempts, focusing on list")
                            }
                        }
                    }, 200L * attempt)
                }
                trySetFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                groupAdapter.focusable(true)
                listAdapter.focusable(false)
                binding.group.requestFocus()
                groupAdapter.scrollToPositionAndSelect(viewModel.groupModel.positionValue)
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                hideSelf()
                return true
            }
        }
        return false
    }

    // TVListAdapter.ItemListener 的 onKey
    override fun onKey(listAdapter: TVListAdapter, keyCode: Int): Boolean {
        (activity as? MainActivity)?.menuActive() // 重置计时器
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                groupAdapter.focusable(true)
                listAdapter.focusable(false)
                binding.group.requestFocus()
                groupAdapter.scrollToPositionAndSelect(viewModel.groupModel.positionValue)
                (activity as? MainActivity)?.menuActive()
                return true
            }
        }
        return false
    }

    fun onVisible() {
        if (viewModel.groupModel.tvGroupValue.size < 2 || viewModel.groupModel.getAllList()?.size() == 0) {
            //Log.w(TAG, "MenuFragment: No data available in group or list")
            return
        }
        //Log.d(TAG, "MenuFragment onVisible, calling setupSourceSwitcher")
        setupSourceSwitcher()
        val position = viewModel.groupModel.positionPlayingValue
        if (position != viewModel.groupModel.positionValue) {
            updateList(position)
        }
        viewModel.groupModel.getCurrentList()?.let {
            listAdapter.update(it)
            listAdapter.toPosition(it.positionPlayingValue)
            // 延迟请求焦点，确保 RecyclerView 更新完成
            view?.postDelayed({
                if (isAdded && isVisible) {
                    requestFocus()
                    Log.d(TAG, "Delayed focus requested after listAdapter update")
                }
            }, 300) // 100ms 延迟
        } ?: run {
            //Log.w(TAG, "MenuFragment: Current list is null, retrying")
            view?.postDelayed({ if (isAdded) onVisible() }, 1000)
        }
        (activity as MainActivity).menuActive()
    }

    private fun requestFocus() {
        binding.sourceSwitcherText.isFocusable = true
        binding.sourceSwitcherText.isFocusableInTouchMode = true
        binding.sourceSwitcherPrev.isFocusable = true
        binding.sourceSwitcherPrev.isFocusableInTouchMode = true
        binding.sourceSwitcherNext.isFocusable = true
        binding.sourceSwitcherNext.isFocusableInTouchMode = true
        binding.group.isFocusable = true
        binding.group.isFocusableInTouchMode = true

        binding.list.isFocusable = true
        binding.list.isFocusableInTouchMode = true
        if (listAdapter.itemCount > 0) {
            listAdapter.focusable(true)
            groupAdapter.focusable(false)
            binding.list.requestFocus()
            val position = viewModel.groupModel.getCurrentList()?.positionPlayingValue ?: 0
            listAdapter.toPosition(position)
            //Log.d(TAG, "Focus requested on list at position $position")
        } else {
            binding.group.isFocusable = true
            binding.group.isFocusableInTouchMode = true
            groupAdapter.focusable(true)
            listAdapter.focusable(false)
            binding.group.requestFocus()
            val groupPosition = viewModel.groupModel.positionValue
            groupAdapter.scrollToPositionAndSelect(groupPosition)
            //Log.d(TAG, "Focus requested on group at position $groupPosition")
        }
    }

    internal fun setupSourceSwitcher() {
        val context = context ?: return
        val prefs = context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)
        // 清理无效键
        with(prefs.edit()) {
            prefs.all.keys.filter { key ->
                if (key.startsWith("cache_") && !key.startsWith("cache_time_") && key.matches(Regex("^cache_.*\\.txt$"))) {
                    val filename = key.removePrefix("cache_")
                    val cacheFile = File(context.filesDir, "cache_$filename")
                    prefs.getString(key, null).isNullOrEmpty() || !cacheFile.exists()
                } else if (key.startsWith("cache_time_") || key.startsWith("url_")) {
                    val filename = key.removePrefix("cache_time_").removePrefix("url_")
                    !prefs.contains("cache_$filename")
                } else {
                    false
                }
            }.forEach { remove(it) }
            apply()
        }
        // 重建 cachedSources
        cachedSources.clear()
        cachedSources["default_channels.txt"] = context.getString(R.string.default_iptv_channel)
        cachedSources["webchannelsiniptv.txt"] = context.getString(R.string.default_web_channel)
        Log.d(TAG, "Added to cachedSources: webchannelsiniptv.txt, default_channels.txt")
        // 添加其他缓存源
        prefs.all.keys.filter { it.startsWith("cache_") && !it.startsWith("cache_time_") }
            .forEach { key ->
                val filename = key.removePrefix("cache_")
                val cachedContent = prefs.getString(key, null)
                val cacheFile = File(context.filesDir, "cache_$filename")
                if (cachedContent != null && cacheFile.exists() && cacheFile.readText() == cachedContent) {
                    val sourceName = prefs.getString("url_$filename", null)?.substringAfterLast("/")?.substringBeforeLast(".")?.takeIf { it.isNotBlank() }
                        ?: filename.substringBeforeLast(".").takeIf { it.isNotBlank() }
                        ?: "未知源"
                    cachedSources[filename] = sourceName
                    Log.d(TAG, "Added to cachedSources: $filename -> $sourceName")
                }
            }
        // 初始化索引
        val activeFilename = prefs.getString("active_source", "default_channels.txt") ?: "default_channels.txt"
        currentTestCodeIndex = cachedSources.keys.indexOfFirst { it == activeFilename }.coerceAtLeast(0)
        displaySourceIndex = currentTestCodeIndex
        if (!cachedSources.containsKey(activeFilename)) {
            Log.w(TAG, "Active source $activeFilename not found in cachedSources, switching to default")
            // 切换到默认源
            with(prefs.edit()) {
                putString("active_source", "default_channels.txt")
                apply()
            }
            switchSource(0) // 触发源切换
        } else {
            Log.d(TAG, "Set displaySourceIndex to $displaySourceIndex for active source: $activeFilename")
        }
        // 更新 UI
        updateSourceSwitcher()
        Log.d(TAG, "Source switcher updated, cachedSources.size=${cachedSources.size}")
        viewModel.channelsOk.observe(viewLifecycleOwner) { isInitialized ->
            if (isInitialized) {
                updateSourceSwitcher()
                view?.post {
                    if (cachedSources.size >= 1) {
                        view?.findViewById<View>(R.id.source_switcher_container)?.visibility = View.VISIBLE
                    } else {
                        view?.findViewById<View>(R.id.source_switcher_container)?.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun updateSourceSwitcher() {
        try {
            if (cachedSources.size >= 2) {
                Log.d(TAG, "Showing source switcher: cachedSources.size=${cachedSources.size}")
                binding.sourceSwitcherContainer.visibility = View.VISIBLE
                val application = context?.applicationContext as YourTVApplication
                val groupActualWidth = binding.group.layoutParams.width + application.dp2Px(1)
                val listActualWidth = binding.list.layoutParams.width
                val totalWidth = groupActualWidth + listActualWidth
                binding.sourceSwitcherContainer.layoutParams.width = totalWidth
                binding.sourceSwitcherText.textSize = 16f
                // 动态更新 displaySourceIndex 以反映当前活跃源
                val activeFilename = context?.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)?.getString("active_source", "default_channels.txt") ?: "default_channels.txt"
                displaySourceIndex = cachedSources.keys.indexOfFirst { it == activeFilename }.coerceAtLeast(0)
                Log.d(TAG, "Updated displaySourceIndex=$displaySourceIndex for activeFilename=$activeFilename")
                updateSourceText()
                updateSourceText()
                if (!listenersBound) {
                    Log.d(TAG, "Binding source switcher listeners")
                    binding.sourceSwitcherPrev.setOnClickListener {
                        updateDisplaySource(-1)
                        binding.sourceSwitcherPrev.requestFocus()
                        (activity as? MainActivity)?.menuActive()
                    }
                    binding.sourceSwitcherNext.setOnClickListener {
                        updateDisplaySource(1)
                        binding.sourceSwitcherNext.requestFocus()
                        (activity as? MainActivity)?.menuActive()
                    }
                    binding.sourceSwitcherText.setOnClickListener {
                        val currentTime = System.currentTimeMillis()
                        // 检查是否在 10 秒防抖期内
                        if (currentTime - lastSwitchSourceTime < 10000) {
                            //Log.d(TAG, "Switch source blocked: within 10s debounce period")
                            (activity as? MainActivity)?.menuActive()
                            return@setOnClickListener
                        }
                        //Log.d(TAG, "Text clicked, clickCount=$clickCount, timeDiff=${currentTime - lastClickTime}")
                        if (currentTime - lastClickTime <= 500) {
                            clickCount += 1
                            // 支持 4 次连击或 2 次双击
                            if (clickCount >= 4) {
                                //Log.d(TAG, "Triggering switchSource(0) on 4 clicks")
                                switchSource(0)
                                clickCount = 0
                            } else if (clickCount == 2) {
                                view?.postDelayed({
                                    if (clickCount == 2) {
                                        //Log.d(TAG, "Triggering switchSource(0) on 2 double clicks")
                                        switchSource(0)
                                        clickCount = 0
                                    }
                                }, 500)
                            }
                        } else {
                            clickCount = 1
                        }
                        lastClickTime = currentTime
                        binding.sourceSwitcherText.requestFocus()
                        (activity as? MainActivity)?.menuActive()
                    }
                    // 在 setupSourceSwitcher() 后，调整按键监听
                    binding.sourceSwitcherPrev.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    binding.sourceSwitcherText.requestFocus()
                                    (activity as? MainActivity)?.menuActive()
                                    //Log.d(TAG, "Focus moved to source_switcher_text from prev")
                                    return@setOnKeyListener true
                                }
                                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    // 不处理上下键，保持旧版焦点逻辑
                                    return@setOnKeyListener false
                                }
                            }
                        }
                        false
                    }
                    binding.sourceSwitcherText.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    binding.sourceSwitcherPrev.requestFocus()
                                    (activity as? MainActivity)?.menuActive()
                                    //Log.d(TAG, "Focus moved to source_switcher_prev from text")
                                    return@setOnKeyListener true
                                }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    binding.sourceSwitcherNext.requestFocus()
                                    (activity as? MainActivity)?.menuActive()
                                    //Log.d(TAG, "Focus moved to source_switcher_next from text")
                                    return@setOnKeyListener true
                                }
                                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    // 不处理上下键，保持旧版焦点逻辑
                                    return@setOnKeyListener false
                                }
                            }
                        }
                        false
                    }
                    binding.sourceSwitcherNext.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    binding.sourceSwitcherText.requestFocus()
                                    (activity as? MainActivity)?.menuActive()
                                    //Log.d(TAG, "Focus moved to source_switcher_text from next")
                                    return@setOnKeyListener true
                                }
                                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    // 不处理上下键，保持旧版焦点逻辑
                                    return@setOnKeyListener false
                                }
                            }
                        }
                        false
                    }
                    listenersBound = true
                }
                binding.sourceSwitcherContainer.requestLayout()
            } else {
                //Log.d(TAG, "Hiding source switcher: cachedSources.size=${cachedSources.size}")
                binding.sourceSwitcherContainer.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateSourceSwitcher: ${e.message}", e)
            binding.sourceSwitcherContainer.visibility = View.GONE
        }
    }

    private fun updateDisplaySource(direction: Int) {
        if (cachedSources.isEmpty()) {
            setupSourceSwitcher()
            return
        }
        // 循环更新显示索引
        displaySourceIndex = (displaySourceIndex + direction).let { idx ->
            when {
                idx >= cachedSources.size -> 0
                idx < 0 -> cachedSources.size - 1
                else -> idx
            }
        }
        //Log.d(TAG, "Updated display source: direction=$direction, displaySourceIndex=$displaySourceIndex")
        updateSourceText()
    }

    private fun switchSource(direction: Int) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSwitchSourceTime < 10000) {
            context?.let {
                Toast.makeText(it, R.string.wait_10_seconds, Toast.LENGTH_SHORT).show()
            }
            return
        }
        lastSwitchSourceTime = currentTime
        val prefs = context?.getSharedPreferences("SourceCache", Context.MODE_PRIVATE) ?: return
        if (cachedSources.isEmpty()) {
            setupSourceSwitcher()
            return
        }
        currentTestCodeIndex = if (direction == 0) displaySourceIndex else {
            (currentTestCodeIndex + direction).let { idx ->
                when {
                    idx >= cachedSources.size -> 0
                    idx < 0 -> cachedSources.size - 1
                    else -> idx
                }
            }
        }
        displaySourceIndex = currentTestCodeIndex
        val selectedFilename = cachedSources.keys.elementAt(currentTestCodeIndex)
        val selectedUrl = prefs.getString("url_$selectedFilename", "") ?: ""
        val selectedSourceName = cachedSources[selectedFilename] ?: R.string.unknown_source
        val activeFilename = prefs.getString("active_source", "default_channels.txt") ?: "default_channels.txt"
        if (selectedFilename == activeFilename) {
            Log.d(TAG, "Selected source is already active: $selectedFilename, skipping switch")
            context?.let {
                Toast.makeText(it, it.getString(R.string.load_failed, selectedSourceName), Toast.LENGTH_SHORT).show()
            }
            return
        }
        lastSwitchSourceTime = currentTime
        Log.d(TAG, "Switching source: direction=$direction, index=$currentTestCodeIndex, filename=$selectedFilename, url=$selectedUrl")
        hideSelf()
        view?.post {
            if (selectedFilename == "default_channels.txt" || selectedFilename == "webchannelsiniptv.txt") {
                val resourceId = if (selectedFilename == "webchannelsiniptv.txt") {
                    R.raw.webchannelsiniptv
                } else {
                    R.raw.channels
                }
                try {
                    val str = requireContext().resources.openRawResource(resourceId).bufferedReader().use { it.readText() }
                    viewModel.tryStr2Channels(str, null, "default://$selectedFilename", selectedFilename)
                    prefs.edit { putString("active_source", selectedFilename) }
                    Log.d(TAG, "Switched to resource: $selectedFilename")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load resource $selectedFilename: ${e.message}", e)
                    context?.let { Toast.makeText(it, it.getString(R.string.load_failed, selectedSourceName), Toast.LENGTH_SHORT).show() }
                }
            } else {
                (activity as? MainActivity)?.switchSource(selectedFilename, selectedUrl)
            }
            updateSourceText()
            context?.let {
                Toast.makeText(it, it.getString(R.string.switched_to, selectedSourceName), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateSourceText() {
        val sourceName = cachedSources.values.elementAtOrNull(displaySourceIndex) ?: ""
        binding.sourceSwitcherText.text = sourceName
        //Log.d(TAG, "Updated source text: $sourceName")
    }

    override fun onAttach(context: Context) { // 注意：使用 Context（适配新版 Fragment）
        super.onAttach(context)
        // 添加 OnBackPressedCallback，处理返回键（可选）
        requireActivity().onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 可选：自定义返回键逻辑，例如隐藏 MenuFragment
                if (isVisible && isAdded) {
                    hideSelf()
                }
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "MenuFragment"
    }
}