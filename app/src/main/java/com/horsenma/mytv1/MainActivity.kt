package com.horsenma.mytv1

import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.KeyEvent.*
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import android.content.Intent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.horsenma.mytv1.models.TVList
import kotlin.math.abs
import com.horsenma.yourtv.R
import com.horsenma.yourtv.showToast
import kotlinx.coroutines.*


class MainActivity : FragmentActivity() {

    private var ok = 0
    internal var webFragment = com.horsenma.mytv1.WebFragment()
    private val errorFragment = com.horsenma.mytv1.ErrorFragment()
    private val loadingFragment = com.horsenma.mytv1.LoadingFragment()
    private var infoFragment = com.horsenma.mytv1.InfoFragment()
    private var channelFragment = com.horsenma.mytv1.ChannelFragment()
    private var timeFragment = com.horsenma.mytv1.TimeFragment()
    private var menuFragment = com.horsenma.mytv1.MenuFragment()
    private var settingFragment = com.horsenma.mytv1.SettingFragment()

    private val handler = Handler(Looper.myLooper()!!)
    private val delayHideMenu = 10 * 1000L
    private val delayHideSetting = 1 * 60 * 1000L
    lateinit var gestureDetector: GestureDetector
    private var server: SimpleServer? = null
    private var isSafeToPerformFragmentTransactions = false

    // 在 GestureListener 类顶部添加计数变量
    private var menuPressCount = 0
    private var lastMenuPressTime = 0L
    private val MENU_PRESS_INTERVAL = 300L
    private val MENU_TAP_INTERVAL = 800L
    private val REQUIRED_MENU_PRESSES = 4
    private var lastSwitchTime = 0L
    private val DEBOUNCE_INTERVAL = 2000L
    private var lastBackPressTime = 0L
    private val BACK_PRESS_INTERVAL = 2000L

    // 添加 handleTapRunnable
    private val handleTapRunnable = Runnable {
        if (menuPressCount >= REQUIRED_MENU_PRESSES) {
            showSetting()
            menuPressCount = 0
        } else if (menuPressCount == 2) {
            showFragment(menuFragment)
            menuActive()
        }
        menuPressCount = 0 // 重置计数
    }

    // 文件: com.horsenma.mytv1.MainActivity
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SP.init(this)

        // 强制重置 TVList 状态
        TVList.reset()
        TVList.reloadData(this)
        Log.d(TAG, "TVList initialized in onCreate")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, webFragment)
                .add(R.id.main_browse_fragment, errorFragment)
                .add(R.id.main_browse_fragment, loadingFragment)
                .add(R.id.main_browse_fragment, infoFragment)
                .add(R.id.main_browse_fragment, channelFragment)
                .add(R.id.main_browse_fragment, menuFragment)
                .add(R.id.main_browse_fragment, settingFragment)
                .hide(menuFragment)
                .hide(settingFragment)
                .hide(errorFragment)
                .hide(loadingFragment)
                .show(webFragment)
                .commitNow()
        }

        gestureDetector = GestureDetector(this, GestureListener(this))
    }

    override fun onResumeFragments() {
        super.onResumeFragments()
        Log.i(TAG, "watch")
        TVList.groupModel.change.observe(this) { _ ->
            Log.i(TAG, "groupModel changed")
            if (TVList.groupModel.tvGroupModel.value != null) {
                watch()
                Log.i(TAG, "menuFragment update")
                menuFragment.update()
            }
        }
        // 确保播放默认频道或第一个可用频道
        if (TVList.size() > 0) {
            if (!TVList.setPosition(0)) {
                Log.w(TAG, "Failed to set position 0, list may be empty")
                "频道列表为空".showToast(Toast.LENGTH_LONG)
            } else {
                "播放默认频道".showToast(Toast.LENGTH_LONG)
            }
        } else {
            Log.w(TAG, "No channels available")
            "无可用频道".showToast(Toast.LENGTH_LONG)
        }
        server = SimpleServer(this)
    }

    fun ready(tag: String) {
        Log.i(TAG, "ready $tag")
    }

    fun updateMenuSize() {
        menuFragment.updateSize()
    }

    private fun watch() {
        TVList.listModel.forEach { tvModel ->
            tvModel.errInfo.observe(this) { _ ->
                if (tvModel.errInfo.value != null
                    && tvModel.tv.id == TVList.position.value
                ) {
                    hideFragment(loadingFragment)
                    if (tvModel.errInfo.value == "") {
                        Log.i(TAG, "${tvModel.tv.title} 播放中")
                        hideErrorFragment()
                        showFragment(webFragment)
                    } else if (tvModel.errInfo.value == "web ok") {
                        Log.i(TAG, "${tvModel.tv.title} 播放中")
                        hideErrorFragment()
                        showFragment(webFragment)
                    } else {
                        Log.i(TAG, "${tvModel.tv.title} ${tvModel.errInfo.value.toString()}")
                        hideFragment(webFragment)
                        hideFragment(webFragment)
                        showErrorFragment(tvModel.errInfo.value.toString())
                    }
                }
            }

            tvModel.ready.observe(this) { _ ->

                // not first time && channel is not changed
                if (tvModel.ready.value != null
                    && tvModel.tv.id == TVList.position.value
                ) {
                    Log.i(TAG, "loading ${tvModel.tv.title}")
                    hideErrorFragment()
                    showFragment(loadingFragment)
                    webFragment.play(tvModel)
                    infoFragment.show(tvModel)
                    if (SP.channelNum) {
                        channelFragment.show(tvModel)
                    }
                }
            }

            tvModel.like.observe(this) { _ ->
                if (tvModel.like.value != null) {
                    val liked = tvModel.like.value as Boolean
                    if (liked) {
                        TVList.groupModel.getTVListModel(0)?.replaceTVModel(tvModel)
                    } else {
                        TVList.groupModel.getTVListModel(0)?.removeTVModel(tvModel.tv.id)
                    }
                    SP.setLike(tvModel.tv.id, liked)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            gestureDetector.onTouchEvent(event)
        }
        return super.onTouchEvent(event)
    }

    private inner class GestureListener(context: Context) :
        GestureDetector.SimpleOnGestureListener() {

        private var screenWidth = windowManager.defaultDisplay.width
        private var screenHeight = windowManager.defaultDisplay.height
        private val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager

        private var maxVolume = 0

        init {
            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }

        override fun onDown(e: MotionEvent): Boolean {
            webFragment.hideVolumeNow()
            settingActive()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            return handleTapCount(1)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // 记录双击
            val currentTime = System.currentTimeMillis()
            val timeSinceLastTap = currentTime - lastMenuPressTime
            if (timeSinceLastTap <= MENU_TAP_INTERVAL) {
                menuPressCount += 2
            } else {
                menuPressCount = 2
            }
            lastMenuPressTime = currentTime

            // 延迟处理，等待可能的后续双击
            handler.removeCallbacks(handleTapRunnable)
            handler.postDelayed(handleTapRunnable, MENU_TAP_INTERVAL)

            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val oldX = e1?.rawX ?: 0f
            val oldY = e1?.rawY ?: 0f
            val newX = e2.rawX
            val newY = e2.rawY
            if (oldX > screenWidth / 3 && oldX < screenWidth * 2 / 3 && abs(newX - oldX) < abs(newY - oldY)) {
                if (velocityY > 0) {
                    if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
                        prev()
                    }
                }
                if (velocityY < 0) {
                    if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
                        next()
                    }
                }
            }

            return super.onFling(e1, e2, velocityX, velocityY)
        }

        private var lastScrollTime: Long = 0
        private var decayFactor: Float = 1.0f

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val oldX = e1?.rawX ?: 0f
            val oldY = e1?.rawY ?: 0f
            val newX = e2.rawX
            val newY = e2.rawY

            if (oldX < screenWidth / 3) {
                val currentTime = System.currentTimeMillis()
                val deltaTime = currentTime - lastScrollTime
                lastScrollTime = currentTime

                decayFactor =
                    0.01f.coerceAtLeast(decayFactor - 0.03f * deltaTime)
                val delta =
                    ((oldY - newY) * decayFactor * 0.2 / screenHeight).toFloat()
                adjustBrightness(delta)
                decayFactor = 1.0f
                return super.onScroll(e1, e2, distanceX, distanceY)
            }

            if (oldX > screenWidth * 2 / 3 && abs(distanceY) > abs(distanceX)) {
                val currentTime = System.currentTimeMillis()
                val deltaTime = currentTime - lastScrollTime
                lastScrollTime = currentTime

                decayFactor =
                    0.01f.coerceAtLeast(decayFactor - 0.03f * deltaTime)
                val delta =
                    ((oldY - newY) * maxVolume * decayFactor * 0.2 / screenHeight).toInt()
                adjustVolume(delta)
                decayFactor = 1.0f
                return super.onScroll(e1, e2, distanceX, distanceY)
            }

            return super.onScroll(e1, e2, distanceX, distanceY)
        }

        private fun adjustVolume(deltaVolume: Int) {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            var newVolume = currentVolume + deltaVolume

            if (newVolume < 0) {
                newVolume = 0
            } else if (newVolume > maxVolume) {
                newVolume = maxVolume
            }

            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

            webFragment.setVolumeMax(maxVolume * 100)
            webFragment.setVolume(newVolume.toInt() * 100, true)
            webFragment.showVolume(View.VISIBLE)
        }

        private fun adjustBrightness(deltaBrightness: Float) {
            var brightness = window.attributes.screenBrightness

            brightness += deltaBrightness
            brightness = 0.1f.coerceAtLeast(0.9f.coerceAtMost(brightness))

            val attributes = window.attributes.apply {
                screenBrightness = brightness
            }
            window.attributes = attributes

            webFragment.setVolumeMax(100)
            webFragment.setVolume((brightness * 100).toInt())
            webFragment.showVolume(View.VISIBLE)
        }
    }

    fun onPlayEnd() {
        val tvModel = TVList.getTVModel()!!
        if (SP.repeatInfo) {
            infoFragment.show(tvModel)
            if (SP.channelNum) {
                channelFragment.show(tvModel)
            }
        }
    }

    fun play(position: Int) {
        val prevGroup = TVList.getTVModel()!!.groupIndex
        if (position > -1 && position < TVList.size()) {
            TVList.setPosition(position)
            val currentGroup = TVList.getTVModel()!!.groupIndex
            if (currentGroup != prevGroup) {
                Log.i(TAG, "group change")
                menuFragment.updateList(currentGroup)
            }
        } else {
            Toast.makeText(this, "频道不存在", Toast.LENGTH_LONG).show()
        }
    }

    fun prev() {
        val prevGroup = TVList.getTVModel()!!.groupIndex
        var position = TVList.position.value?.dec() ?: 0
        if (position == -1) {
            position = TVList.size() - 1
        }
        TVList.setPosition(position)
        val currentGroup = TVList.getTVModel()!!.groupIndex
        if (currentGroup != prevGroup) {
            Log.i(TAG, "group change")
            menuFragment.updateList(currentGroup)
        }
    }

    fun next() {
        val prevGroup = TVList.getTVModel()!!.groupIndex
        var position = TVList.position.value?.inc() ?: 0
        if (position == TVList.size()) {
            position = 0
        }
        TVList.setPosition(position)
        val currentGroup = TVList.getTVModel()!!.groupIndex
        if (currentGroup != prevGroup) {
            Log.i(TAG, "group change")
            menuFragment.updateList(currentGroup)
        }
    }

    fun showFragment(fragment: Fragment) {
        if (!isSafeToPerformFragmentTransactions) {
            return
        }
        if (!fragment.isAdded) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, fragment)
                .commitAllowingStateLoss()
            return
        }
        if (!fragment.isHidden) {
            return
        }
        supportFragmentManager.beginTransaction()
            .show(fragment)
            .commitAllowingStateLoss()
    }

    private fun hideFragment(fragment: Fragment) {
        if (!isSafeToPerformFragmentTransactions) {
            return
        }

        if (!fragment.isAdded || fragment.isHidden) {
            return
        }

        supportFragmentManager.beginTransaction()
            .hide(fragment)
            .commitAllowingStateLoss()
    }

    fun menuActive() {
        handler.removeCallbacks(hideMenu)
        handler.postDelayed(hideMenu, delayHideMenu)
    }

    private val hideMenu = Runnable {
        if (!isFinishing && !supportFragmentManager.isStateSaved) {
            if (!menuFragment.isHidden) {
                supportFragmentManager.beginTransaction().hide(menuFragment).commit()
            }
        }
    }

    fun settingActive() {
        handler.removeCallbacks(hideSetting)
        handler.postDelayed(hideSetting, delayHideSetting)
    }

    private val hideSetting = Runnable {
        if (!settingFragment.isHidden) {
            supportFragmentManager.beginTransaction().hide(settingFragment).commitNow()
        }
        addTimeFragment()
    }

    fun addTimeFragment() {
        if (SP.time) {
            showFragment(timeFragment)
        } else {
            hideFragment(timeFragment)
        }
    }

    // 修改 showChannel 方法
    private fun showChannel(channel: String) {
        if (!menuFragment.isHidden) {
            return
        }
        if (settingFragment.isVisible) {
            return
        }
        channelFragment.show(channel)
    }

    private fun channelUp() {
        if (menuFragment.isHidden && settingFragment.isHidden) {
            if (SP.channelReversal) {
                next()
                return
            }
            prev()
        }
    }

    private fun channelDown() {
        if (menuFragment.isHidden && settingFragment.isHidden) {
            if (SP.channelReversal) {
                prev()
                return
            }
            next()
        }
    }

    private fun showSetting() {
        if (menuFragment.isAdded && !menuFragment.isHidden) {
            return
        }
        showFragment(settingFragment)
        settingActive()
    }

    fun hideMenuFragment() {
        supportFragmentManager.beginTransaction()
            .hide(menuFragment)
            .commit()
        Log.i(TAG, "SP.time ${SP.time}")
    }

    private fun hideSettingFragment() {
        supportFragmentManager.beginTransaction()
            .hide(settingFragment)
            .commit()
    }

    private fun showErrorFragment(msg: String) {
        errorFragment.show(msg)
        if (!errorFragment.isHidden) {
            return
        }

        supportFragmentManager.beginTransaction()
            .show(errorFragment)
            .commitNow()
    }

    private fun hideErrorFragment() {
        errorFragment.show("hide")
        if (errorFragment.isHidden) {
            return
        }

        supportFragmentManager.beginTransaction()
            .hide(errorFragment)
            .commitNow()
    }

    private fun handleTapCount(tapCount: Int): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastTap = currentTime - lastMenuPressTime

        if (timeSinceLastTap <= MENU_TAP_INTERVAL) {
            menuPressCount += tapCount
        } else {
            menuPressCount = tapCount
        }
        lastMenuPressTime = currentTime

        // 延迟处理，等待可能的后续点击
        handler.removeCallbacks(handleTapRunnable)
        handler.postDelayed(handleTapRunnable, MENU_TAP_INTERVAL)
        return true
    }

    private fun handleSettingsKeyPress(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastMenuPressTime <= MENU_PRESS_INTERVAL) {
            menuPressCount++
            if (menuPressCount >= REQUIRED_MENU_PRESSES) {
                showSetting()
                menuPressCount = 0
                return true
            }
        } else {
            menuPressCount = 1
        }
        lastMenuPressTime = currentTime
        return true
    }

    fun onKey(keyCode: Int): Boolean {
        when (keyCode) {
            KEYCODE_ESCAPE, KEYCODE_BACK -> {
                if (menuFragment.isAdded && !menuFragment.isHidden) {
                    menuActive()
                    hideMenuFragment()
                    return true
                }
                if (settingFragment.isAdded && !settingFragment.isHidden) {
                    hideSettingFragment()
                    addTimeFragment()
                    settingActive()
                    return true
                }
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressTime < BACK_PRESS_INTERVAL) {
                    finishAffinity()
                    return true
                }
                lastBackPressTime = currentTime
                R.string.press_back_exit.showToast()
                return true
            }
            KEYCODE_0, KEYCODE_1, KEYCODE_2, KEYCODE_3, KEYCODE_4,
            KEYCODE_5, KEYCODE_6, KEYCODE_7, KEYCODE_8, KEYCODE_9 -> {
                showChannel((keyCode - 7).toString())
                return true
            }
            KEYCODE_BOOKMARK, KEYCODE_UNKNOWN, KEYCODE_HELP,
            KEYCODE_SETTINGS, KEYCODE_MENU -> {
                settingActive()
                return handleSettingsKeyPress()
            }
            KEYCODE_DPAD_UP, KEYCODE_CHANNEL_UP -> {
                if (menuFragment.isAdded && !menuFragment.isHidden) {
                    menuActive()
                    return false
                }
                if (settingFragment.isAdded && !settingFragment.isHidden) {
                    settingActive()
                    return false
                }
                channelUp()
                return true
            }
            KEYCODE_DPAD_DOWN, KEYCODE_CHANNEL_DOWN -> {
                if (menuFragment.isAdded && !menuFragment.isHidden) {
                    menuActive()
                    return false
                }
                if (settingFragment.isAdded && !settingFragment.isHidden) {
                    settingActive()
                    return false
                }
                channelDown()
                return true
            }
            KEYCODE_ENTER, KEYCODE_DPAD_CENTER -> {
                showFragment(menuFragment)
                menuActive()
                return true
            }
            KEYCODE_DPAD_LEFT -> {
                if (menuFragment.isAdded && !menuFragment.isHidden ||
                    settingFragment.isAdded && !settingFragment.isHidden) {
                    settingActive()
                    menuActive()
                    return false
                }
                return false // 无 programFragment，不拦截
            }
            KEYCODE_DPAD_RIGHT -> {
                if (menuFragment.isAdded && !menuFragment.isHidden ||
                    settingFragment.isAdded && !settingFragment.isHidden) {
                    settingActive()
                    menuActive()
                    return false
                }
                return true // 无 sourceUp，拦截但不处理
            }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (onKey(keyCode)) {
            return true
        }
        return false  // 不调用 super.onKeyDown，阻止系统默认退出
    }

    // 在 onResume 中恢复视图
    override fun onResume() {
        super.onResume()
        isSafeToPerformFragmentTransactions = true
        addTimeFragment()
    }

    fun handleWebviewTypeSwitch(enable: Boolean) {
        if (!enable) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSwitchTime < DEBOUNCE_INTERVAL) {
            Log.d(TAG, "Switch ignored due to debounce")
            return
        }
        lastSwitchTime = currentTime

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                supportFragmentManager.beginTransaction()
                    .hide(webFragment)
                    .commitNowAllowingStateLoss()
                supportFragmentManager.fragments.forEach { fragment ->
                    if (fragment.isAdded && !fragment.isHidden) {
                        supportFragmentManager.beginTransaction()
                            .hide(fragment)
                            .commitNowAllowingStateLoss()
                    }
                }
                Log.d(TAG, "All fragments hidden")
                com.horsenma.yourtv.SP.enableWebviewType = false
                Log.d(TAG, "SP.enableWebviewType set to false")
                delay(500)
                val intent = Intent(this@MainActivity, com.horsenma.yourtv.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
                Log.d(TAG, "Switched to yourtv.MainActivity with new task")
            } catch (e: Exception) {
                Log.e(TAG, "Error switching to yourtv.MainActivity: ${e.message}", e)
                R.string.switch_iptv_failed.showToast()
            }
        }
    }

    override fun onPause() {
        super.onPause()

        isSafeToPerformFragmentTransactions = false
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}