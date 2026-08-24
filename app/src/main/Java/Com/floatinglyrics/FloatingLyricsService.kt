package com.example.floatinglyrics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class BackgroundMode { NORMAL, CLEAR, BLUR }

class FloatingLyricsService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var currentLyrics: List<LyricLine> = emptyList()
    private var syncJob: Job? = null
    private var currentBgMode = BackgroundMode.NORMAL

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_lyrics, null)

        val sharedPreferences = getSharedPreferences("FloatingLyricsPrefs", Context.MODE_PRIVATE)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            sharedPreferences.getInt("WIDGET_WIDTH", WindowManager.LayoutParams.WRAP_CONTENT),
            sharedPreferences.getInt("WIDGET_HEIGHT", WindowManager.LayoutParams.WRAP_CONTENT),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sharedPreferences.getInt("WIDGET_X", 100)
            y = sharedPreferences.getInt("WIDGET_Y", 200)
        }

        windowManager.addView(floatingView, layoutParams)
        
        setupDragTouchListener()
        setupResizeTouchListener()
        setupCloseButton()
        setupMediaControls()
        setupBackgroundToggle()
    }

    private fun saveWidgetState() {
        val sharedPreferences = getSharedPreferences("FloatingLyricsPrefs", Context.MODE_PRIVATE)
        sharedPreferences.edit().apply {
            putInt("WIDGET_X", layoutParams.x)
            putInt("WIDGET_Y", layoutParams.y)
            putInt("WIDGET_WIDTH", layoutParams.width)
            putInt("WIDGET_HEIGHT", layoutParams.height)
            apply()
        }
    }

    private fun setupDragTouchListener() {
        val rootLayout = floatingView.findViewById<View>(R.id.floating_root)
        rootLayout.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, layoutParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val screenWidth = resources.displayMetrics.widthPixels
                        val viewWidth = floatingView.width
                        val middleOfScreen = screenWidth / 2
                        val viewCenter = layoutParams.x + (viewWidth / 2)

                        val targetX = if (viewCenter < middleOfScreen) 0 else screenWidth - viewWidth
                        animateToEdge(layoutParams.x, targetX)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun animateToEdge(startX: Int, endX: Int) {
        val animator = ValueAnimator.ofInt(startX, endX)
        animator.duration = 250
        animator.interpolator = DecelerateInterpolator()
        
        animator.addUpdateListener { animation ->
            layoutParams.x = animation.animatedValue as Int
            windowManager.updateViewLayout(floatingView, layoutParams)
        }
        
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                saveWidgetState()
            }
        })
        
        animator.start()
    }

    private fun setupResizeTouchListener() {
        val resizeHandle = floatingView.findViewById<View>(R.id.iv_resize_handle)
        resizeHandle.setOnTouchListener(object : View.OnTouchListener {
            private var initialWidth = 0
            private var initialHeight = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialWidth = floatingView.width
                        initialHeight = floatingView.height
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val newWidth = initialWidth + (event.rawX - initialTouchX).toInt()
                        val newHeight = initialHeight + (event.rawY - initialTouchY).toInt()
                        layoutParams.width = newWidth.coerceAtLeast(300)
                        layoutParams.height = newHeight.coerceAtLeast(200)
                        windowManager.updateViewLayout(floatingView, layoutParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        saveWidgetState()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun setupCloseButton() {
        floatingView.findViewById<View>(R.id.iv_close_button).setOnClickListener { stopSelf() }
    }

    private fun setupMediaControls() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        fun sendMediaCommand(keyCode: Int) {
            val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(eventDown)
            audioManager.dispatchMediaKeyEvent(eventUp)
        }

        val playPauseButton = floatingView.findViewById<ImageView>(R.id.iv_play_pause)
        playPauseButton.setOnClickListener {
            sendMediaCommand(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            if (playPauseButton.tag == "playing") {
                playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                playPauseButton.tag = "paused"
            } else {
                playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
                playPauseButton.tag = "playing"
            }
        }

        floatingView.findViewById<View>(R.id.iv_prev).setOnClickListener {
            sendMediaCommand(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }

        floatingView.findViewById<View>(R.id.iv_next).setOnClickListener {
            sendMediaCommand(KeyEvent.KEYCODE_MEDIA_NEXT)
        }
    }

    private fun setupBackgroundToggle() {
        val bgToggleBtn = floatingView.findViewById<ImageView>(R.id.iv_bg_toggle)
        val rootLayout = floatingView.findViewById<View>(R.id.floating_root)

        bgToggleBtn.setOnClickListener {
            currentBgMode = when (currentBgMode) {
                BackgroundMode.NORMAL -> BackgroundMode.CLEAR
                BackgroundMode.CLEAR -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BackgroundMode.BLUR else BackgroundMode.NORMAL
                BackgroundMode.BLUR -> BackgroundMode.NORMAL
            }
            applyBackgroundMode(rootLayout)
        }
    }

    private fun applyBackgroundMode(rootLayout: View) {
        when (currentBgMode) {
            BackgroundMode.NORMAL -> {
                rootLayout.setBackgroundColor(Color.parseColor("#D9000000"))
                layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            }
            BackgroundMode.CLEAR -> {
                rootLayout.setBackgroundColor(Color.TRANSPARENT)
                layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            }
            BackgroundMode.BLUR -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    rootLayout.setBackgroundColor(Color.parseColor("#44000000"))
                    layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    layoutParams.blurBehindRadius = 40
                }
            }
        }
        windowManager.updateViewLayout(floatingView, layoutParams)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("TRACK_TITLE") ?: return START_STICKY
        val artist = intent.getStringExtra("ARTIST_NAME") ?: return START_STICKY

        val tvTitle = floatingView.findViewById<TextView>(R.id.tv_track_title)
        tvTitle.text = "$title - $artist"
        
        val tvLyrics = floatingView.findViewById<TextView>(R.id.tv_lyrics)

        val cacheFile = getCacheFile(title, artist)
        if (cacheFile.exists()) {
            val cachedLrc = cacheFile.readText()
            currentLyrics = LrcParser.parse(cachedLrc)
            tvLyrics.text = "Loaded from cache! (${currentLyrics.size} lines)"
            startSyncTimer()
        } else {
            fetchLyricsFromApi(title, artist)
        }

        return START_STICKY
    }

    private fun getCacheFile(trackTitle: String, artist: String): File {
        val safeName = "${trackTitle}_${artist}".replace(Regex("[^a-zA-Z0-9]"), "_")
        return File(cacheDir, "$safeName.lrc")
    }

    private fun fetchLyricsFromApi(trackTitle: String, artist: String) {
        val tvLyrics = floatingView.findViewById<TextView>(R.id.tv_lyrics)
        tvLyrics.text = "Searching LRCLIB..."
        
        serviceScope.launch {
            try {
                val safeTrack = URLEncoder.encode(trackTitle, "UTF-8")
                val safeArtist = URLEncoder.encode(artist, "UTF-8")
                val url = URL("https://lrclib.net/api/get?track_name=$safeTrack&artist_name=$safeArtist")
                
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "FloatingLyricsApp/1.0 (test@example.com)")
                
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)
                    
                    if (json.has("syncedLyrics") && !json.isNull("syncedLyrics")) {
                        val syncedText = json.getString("syncedLyrics")
                        currentLyrics = LrcParser.parse(syncedText)
                        
                        getCacheFile(trackTitle, artist).writeText(syncedText)
                        
                        withContext(Dispatchers.Main) {
                            tvLyrics.text = "Saved and ready! (${currentLyrics.size} lines)"
                            startSyncTimer()
                        }
                    } else {
                        withContext(Dispatchers.Main) { tvLyrics.text = "No synced lyrics available." }
                    }
                } else {
                    withContext(Dispatchers.Main) { tvLyrics.text = "Song not found in database." }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvLyrics.text = "Network error occurred." }
            }
        }
    }

    private fun getActiveMediaController(): MediaController? {
        val sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val componentName = ComponentName(this, MediaNotificationListener::class.java)
        
        return try {
            val controllers = sessionManager.getActiveSessions(componentName)
            controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.firstOrNull()
        } catch (e: SecurityException) {
            null
        }
    }

    private fun startSyncTimer() {
        syncJob?.cancel()
        
        syncJob = serviceScope.launch(Dispatchers.Main) {
            var timePausedAt = 0L

            while (isActive) {
                val controller = getActiveMediaController()
                val state = controller?.playbackState

                if (state != null) {
                    val isPlaying = state.state == PlaybackState.STATE_PLAYING

                    if (isPlaying) {
                        timePausedAt = 0L
                        if (floatingView.visibility != View.VISIBLE) {
                            floatingView.visibility = View.VISIBLE
                        }

                        val timeDelta = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                        val currentPosition = (state.position + (timeDelta * state.playbackSpeed)).toLong()
                        updateActiveLyric(currentPosition)

                    } else {
                        if (timePausedAt == 0L) {
                            timePausedAt = SystemClock.elapsedRealtime()
                        }
                        val timeSincePause = SystemClock.elapsedRealtime() - timePausedAt
                        if (timeSincePause > 3000) {
                            if (floatingView.visibility == View.VISIBLE) {
                                floatingView.visibility = View.GONE
                            }
                        }
                    }
                } else {
                    if (floatingView.visibility == View.VISIBLE) {
                        floatingView.visibility = View.GONE
                    }
                }
                delay(100)
            }
        }
    }

    private fun updateActiveLyric(currentPositionMs: Long) {
        if (currentLyrics.isEmpty()) return

        val tvLyrics = floatingView.findViewById<TextView>(R.id.tv_lyrics)
        val activeIndex = currentLyrics.indexOfLast { it.startTimeMs <= currentPositionMs }

        if (activeIndex == -1) {
            tvLyrics.text = "Waiting for vocals..."
            return
        }

        val prevLine = if (activeIndex > 0) currentLyrics[activeIndex - 1].text else ""
        val currentLine = currentLyrics[activeIndex].text
        val nextLine = if (activeIndex < currentLyrics.size - 1) currentLyrics[activeIndex + 1].text else ""

        val formattedText = """
            <font color='#888888'>$prevLine</font><br>
            <b><font color='#00FF66'>$currentLine</font></b><br>
            <font color='#888888'>$nextLine</font>
        """.trimIndent()

        tvLyrics.text = HtmlCompat.fromHtml(formattedText, HtmlCompat.FROM_HTML_MODE_COMPACT)
    }

    override fun onDestroy() {
        super.onDestroy()
        saveWidgetState()
        serviceScope.cancel()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}
