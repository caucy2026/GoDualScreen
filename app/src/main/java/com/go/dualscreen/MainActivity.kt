package com.go.dualscreen

import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var game: GoGame
    lateinit var goView: GoView  // V6.3: public for analysis access
    private lateinit var statusText: TextView
    private lateinit var capturesText: TextView
    private lateinit var countdownText: TextView
    private lateinit var startBtn: Button
    private lateinit var undoBtn: Button
    private var gamePresentation: GamePresentation? = null
    private lateinit var displayManager: DisplayManager
    private val handler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null
    private var secondsLeft = 60
    private var pauseBtnBlack: Button? = null
    private var animator: ValueAnimator? = null
    private var myPlayer = GoGame.PLAYER_BLACK
    private lateinit var prefs: SharedPreferences
    @JvmField var autoPlayEnabled = false
    @JvmField var autoPlayWhite = false
    @JvmField var kataGoEnabled = true  // V6.1: KataGo开关
    private var autoPlayRunnable: Runnable? = null
    // ★ V4.4: KataGo 启动计时器
    private var kataGoStartMs = 0L
    private var kataGoTimerRunnable: Runnable? = null
    // V8.6: 录像功能
    private var recordingEnabled = false
    @JvmField var isReplaying = false
    private var replayData: RecordingData? = null
    private var replayIndex = 0
    private var replayPlaying = false
    private val replayHandler = Handler(Looper.getMainLooper())
    private var replayRunnable: Runnable? = null
    private var replayProgressBar: SeekBar? = null
    private var replayPlayBtn: Button? = null
    private var replayExitBtn: Button? = null
    private var replayMoveText: TextView? = null  // V8.7: 步数显示
    private var replayOrigBoardSize = 13  // V9.2: 回放前原始棋盘大小
    // V8.8: 回放时禁用的按钮列表
    private val replaySensitiveViews = mutableListOf<View>()

    fun setAutoPlayWhite(on: Boolean) { autoPlayWhite = on }
    fun triggerAutoPlayIfMyTurn() {
        if (game.isActive && !game.isGameOver && !game.isPaused && game.currentPlayer == GoGame.PLAYER_WHITE && autoPlayWhite) {
            startRemindTimers()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // V4.0: 双屏防呆 — 强制迁回主屏 (Display 0)
        @Suppress("DEPRECATION")
        val launchedDisplayId = windowManager.defaultDisplay.displayId
        if (launchedDisplayId != Display.DEFAULT_DISPLAY) {
            val options = ActivityOptions.makeBasic()
            options.launchDisplayId = Display.DEFAULT_DISPLAY
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                options.toBundle()
            )
            finish()
            return
        }

        // --- 以下仅在主屏 (Display 0) 执行 ---
        myPlayer = GoGame.PLAYER_BLACK
        prefs = getSharedPreferences("go_settings", MODE_PRIVATE)
        val savedSize = prefs.getInt("board_size", 13)
        GameState.game = GoGame(savedSize)
        game = GameState.game
        GameState.mainActivity = this
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        SoundFX.stoneSoundEnabled = prefs.getBoolean("stone_sound", true)
        SoundFX.voiceStyle = prefs.getInt("voice_style", 0)
        // V6.0: 恢复背景音乐状态（默认开启50%）
        BgMusic.updateVolume(prefs.getFloat("music_volume", 0.5f))
        if (prefs.getBoolean("bg_music", false)) {
            BgMusic.setEnabled(this, true)
        }
        // V6.1: KataGo开关状态（默认开启）
        kataGoEnabled = prefs.getBoolean("katago_enabled", true)
        // V8.6: 录像开关状态
        recordingEnabled = prefs.getBoolean("recording_enabled", true)  // V10.3: 默认开启诊断录像
        initMainUI()
        goView.showPieceOrder = prefs.getBoolean("piece_order", false)
        launchWhiteScreen()

        // ★ V6.1: 初始化 KataGo 引擎（后台加载模型，不阻塞 UI）
        if (kataGoEnabled) startKataGoEngine()
        // V8.9: 确保初始化阶段没有残留计时器
        stopRemindTimers()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        initMainUI()
        launchWhiteScreen()
        if (game.isActive) startRemindTimers()
    }

    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** V6.1: 启动 KataGo 引擎（可从设置开关触发） */
    private fun startKataGoEngine() {
        if (GameState.kataGoEngine?.isReady == true) return  // 已就绪
        kataGoStartMs = System.currentTimeMillis()
        goView.message = "\uD83E\uDD16 KataGo \u521D\u59CB\u5316\u4E2D... 0s"
        goView.invalidate()
        kataGoTimerRunnable = object : Runnable {
            override fun run() {
                if (GameState.kataGoEngine?.isReady == true) return
                val elapsed = (System.currentTimeMillis() - kataGoStartMs) / 1000
                goView.message = "\uD83E\uDD16 KataGo \u521D\u59CB\u5316\u4E2D... ${elapsed}s"
                goView.invalidate()
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(kataGoTimerRunnable!!, 1000)

        GameState.kataGoEngine = KataGoEngine(this).also { engine ->
            engine.init(
                onProgress = { msg ->
                    runOnUiThread {
                        val elapsed = (System.currentTimeMillis() - kataGoStartMs) / 1000
                        goView.message = "\uD83E\uDD16 $msg (${elapsed}s)"
                        goView.invalidate()
                    }
                },
                onReady = { ok, info ->
                    runOnUiThread {
                        GameState.useKataGo = ok
                        kataGoTimerRunnable?.let { handler.removeCallbacks(it) }
                        kataGoTimerRunnable = null
                        val totalSec = (System.currentTimeMillis() - kataGoStartMs) / 1000
                        if (ok) {
                            goView.message = "\u2705 KataGo \u5C31\u7EEA (OpenCL GPU, ${totalSec}s)"
                            goView.invalidate()
                            handler.postDelayed({ goView.clearMessage(); goView.invalidate(); updateStatusDisplay() }, 2500)
                            if (game.isActive) asyncSyncKataGoBoard()
                        } else {
                            goView.message = "\u274C KataGo \u5931\u8D25: $info"
                            goView.invalidate()
                            updateStatusDisplay()
                        }
                    }
                }
            )
        }
    }

    private fun initMainUI() {
        val root = LinearLayout(this).apply {
            orientation = if (isLandscape()) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#D4C4A8"))
        }
        goView = GoView(this).apply {
            playerPerspective = myPlayer
            game = this@MainActivity.game
            onConfirmPlace = { row, col -> handlePiecePlaced(row, col) }
        }

        val smallSize = (resources.displayMetrics.density * 60).toInt()
        val leftPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(12, 12, 4, 12)
        }
        val logoImg = ImageView(this).apply {
            setImageResource(R.drawable.kemi_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener {
                goView.startFlowerAnimation()
                SoundFX.playCheerfulSound()
            }
        }
        leftPanel.addView(logoImg, LinearLayout.LayoutParams(smallSize, smallSize).apply { bottomMargin = 12 })

        // 催促按钮
        val hurryBtn = create3DButton("\u23F0\n\u50AC", "#FF9800", "#E65100", smallSize)
        hurryBtn.setOnClickListener {
            if (isReplaying) return@setOnClickListener  // V8.6
            if (!game.isActive || game.isGameOver) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u672A\u5F00\u59CB"); return@setOnClickListener }
            if (game.isPaused && game.pausedByPlayer != myPlayer) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u6682\u505C\u4E2D\uFF0C\u8BF7\u7B49\u5F85\u5BF9\u65B9\u6062\u590D"); return@setOnClickListener }
            if (game.currentPlayer == myPlayer) { showMsgToPlayer(myPlayer, "\u8F6E\u5230\u4F60\u4E86\uFF0C\u4E0D\u80FD\u50AC\u81EA\u5DF1"); return@setOnClickListener }
            val rid = if (myPlayer == GoGame.PLAYER_BLACK) R.raw.hurry_black else R.raw.hurry_white
            SoundFX.playVoice(this, rid)
            gamePresentation?.shakeScreen()
        }
        leftPanel.addView(hurryBtn, LinearLayout.LayoutParams(smallSize, smallSize).apply { bottomMargin = 8 })

        // Pass 虚手按钮
        val passBtn = create3DButton("\u270B\n\u865A\u624B", "#A08060", "#806040", smallSize)
        passBtn.setOnClickListener {
            if (isReplaying) return@setOnClickListener  // V8.6
            if (!game.isActive || game.isGameOver) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u672A\u5F00\u59CB"); return@setOnClickListener }
            if (game.isPaused) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u6682\u505C\u4E2D\uFF0C\u8BF7\u5148\u6062\u590D"); return@setOnClickListener }
            if (game.currentPlayer != myPlayer) { showMsgToPlayer(myPlayer, "\u8BF7\u7B49\u5F85\u5BF9\u65B9\u64CD\u4F5C"); return@setOnClickListener }
            handlePass(myPlayer)
        }
        leftPanel.addView(passBtn, LinearLayout.LayoutParams(smallSize, smallSize).apply { bottomMargin = 8 })

        // 提示按钮（黑方在虚手下方）
        val hintBtn = create3DButton("\uD83D\uDCA1\nAI\u63D0\u793A", "#607888", "#405868", smallSize)
        hintBtn.setOnClickListener {
            if (isReplaying) return@setOnClickListener  // V8.6
            if (!game.isActive || game.isGameOver) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u672A\u5F00\u59CB"); return@setOnClickListener }
            if (game.isPaused) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u6682\u505C\u4E2D"); return@setOnClickListener }
            if (autoPlayEnabled) { showMsgToPlayer(myPlayer, "\uD83E\uDD16 AI\u81EA\u52A8\u4E2D\uFF0C\u65E0\u9700\u63D0\u793A"); return@setOnClickListener }
            if (game.currentPlayer != myPlayer) { showMsgToPlayer(myPlayer, "\u73B0\u5728\u662F\u5BF9\u65B9\u7684\u56DE\u5408\uFF0C\u4E0D\u80FD\u4F7F\u7528\u63D0\u793A"); return@setOnClickListener }
            handleHint()
        }
        leftPanel.addView(hintBtn, LinearLayout.LayoutParams(smallSize, smallSize).apply { bottomMargin = 8 })

        // 数目按钮
        val scoreBtn = create3DButton("\uD83D\uDCCA\n\u6570\u5B50", "#688078", "#486058", smallSize)
        scoreBtn.setOnClickListener {
            if (isReplaying) return@setOnClickListener  // V8.6
            if (!game.isActive || game.isGameOver) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u672A\u5F00\u59CB"); return@setOnClickListener }
            if (game.isPaused) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u6682\u505C\u4E2D"); return@setOnClickListener }
            handleScoring()
        }
        leftPanel.addView(scoreBtn, LinearLayout.LayoutParams(smallSize, smallSize).apply { bottomMargin = 8 })

        val pauseBtn = create3DButton("\u23F8\n\u6682\u505C", "#988878", "#786858", smallSize)
        pauseBtnBlack = pauseBtn
        var pauseBtnRef: Button? = null; pauseBtnRef = pauseBtn
        pauseBtn.setOnClickListener {
            if (isReplaying) return@setOnClickListener  // V8.6
            if (!game.isActive || game.isGameOver) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u672A\u5F00\u59CB"); return@setOnClickListener }
            if (game.isPaused) {
                if (game.pausedByPlayer == myPlayer) {
                    game.isPaused = false; game.pausedByPlayer = GoGame.EMPTY
                    pauseBtnRef?.let { b ->
                        b.text = "\u23F8\n\u6682\u505C"; b.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL; setSize(smallSize, smallSize)
                            colors = intArrayOf(Color.parseColor("#607D8B"), Color.parseColor("#37474F"))
                            orientation = GradientDrawable.Orientation.TOP_BOTTOM
                            setStroke(3, Color.parseColor("#CCFFFFFF"))
                        }
                    }
                    gamePresentation?.updatePauseBtn(false)
                    updateStatusDisplay(); gamePresentation?.updateStatusText()
                    startRemindTimers()
                } else {
                    showMsgToPlayer(myPlayer, "\u7B49\u5F85\u5BF9\u65B9\u6062\u590D\u6E38\u620F")
                }
            } else {
                val myPauseCount = if (myPlayer == GoGame.PLAYER_BLACK) game.pauseCountBlack else game.pauseCountWhite
                if (myPauseCount <= 0) { showMsgToPlayer(myPlayer, "\u6682\u505C\u6B21\u6570\u5DF2\u7528\u5B8C"); return@setOnClickListener }
                if (myPlayer == GoGame.PLAYER_BLACK) game.pauseCountBlack-- else game.pauseCountWhite--
                game.isPaused = true; game.pausedByPlayer = myPlayer
                pauseBtnRef?.let { b ->
                    b.text = "\u23F8\n\u6682\u505C\u4E2D"; b.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setSize(smallSize, smallSize)
                        colors = intArrayOf(Color.parseColor("#FF8F00"), Color.parseColor("#E65100"))
                        orientation = GradientDrawable.Orientation.TOP_BOTTOM
                        setStroke(3, Color.parseColor("#CCFFFFFF"))
                    }
                }
                gamePresentation?.updatePauseBtn(true)
                stopRemindTimers(); updateStatusDisplay(); gamePresentation?.updateStatusText()
            }
        }
        leftPanel.addView(pauseBtn, LinearLayout.LayoutParams(smallSize, smallSize).apply { bottomMargin = 4 })

        // 自动落子开关
        val autoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(4, 4, 4, 4)
        }
        autoRow.addView(TextView(this).apply {
            text = "\uD83E\uDD16AI\u81EA\u52A8"; textSize = 10f; setTextColor(Color.parseColor("#6D4C41"))
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = 4 })
        val autoSw = Switch(this).apply {
            isChecked = false; textSize = 8f
            setOnCheckedChangeListener { _, on ->
                if (on && !kataGoEnabled) {
                    showPopupMessage("⚠️ 请先在设置中开启 KataGo")
                    isChecked = false
                    return@setOnCheckedChangeListener
                }
                autoPlayEnabled = on
                goView.autoPlayBlock = on
                // 如果正在我方回合，立即触发自动落子
                if (on && game.isActive && !game.isGameOver && !game.isPaused && game.currentPlayer == myPlayer) {
                    startRemindTimers()
                }
            }
        }
        autoRow.addView(autoSw)
        leftPanel.addView(autoRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 4 })

        // V8.8: 注册回放时需禁用的左侧按钮
        replaySensitiveViews.addAll(listOf(hurryBtn, passBtn, hintBtn, scoreBtn, pauseBtn, autoSw))

        // 版本信息（左下角）
        val verLabel = TextView(this).apply {
            text = "KataGo围棋双屏\nV10.1"; textSize = 9f
            setTextColor(Color.parseColor("#998B7388")); gravity = Gravity.CENTER
            setPadding(2, 8, 2, 2)
        }
        leftPanel.addView(verLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(leftPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val boardLp = if (isLandscape()) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f) else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        root.addView(goView, boardLp)

        val panel = LinearLayout(this).apply {
            orientation = if (isLandscape()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER; setPadding(8, 12, 8, 8)
        }
        statusText = TextView(this).apply {
            text = "\u26AB\u9ED1\u65B9\n\u7B49\u5F85\u5F00\u59CB..."; textSize = 13f
            setTextColor(Color.parseColor("#4A3728")); gravity = Gravity.CENTER; setPadding(4, 4, 4, 4)
        }
        panel.addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = if (isLandscape()) 4 else 0; rightMargin = if (isLandscape()) 0 else 12
        })

        // 提子数显示
        capturesText = TextView(this).apply {
            text = "\u26AB \u63D0\u5B50: 0 | \u26AA \u63D0\u5B50: 0"; textSize = 11f
            setTextColor(Color.parseColor("#6D4C41")); gravity = Gravity.CENTER; setPadding(4, 2, 4, 2)
        }
        panel.addView(capturesText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = if (isLandscape()) 6 else 0; rightMargin = if (isLandscape()) 0 else 12
        })

        countdownText = TextView(this).apply {
            text = ""; textSize = 18f; setTextColor(Color.parseColor("#D84315"))
            gravity = Gravity.CENTER; setPadding(4, 4, 4, 4)
        }
        panel.addView(countdownText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = if (isLandscape()) 12 else 0; rightMargin = if (isLandscape()) 0 else 12
        })

        val btnSize = (resources.displayMetrics.density * 100).toInt()
        val btnMargin = if (isLandscape()) 24 else 0; val btnMargin2 = if (isLandscape()) 0 else 16
        startBtn = createPlaqueButton("\u25B6\n\u5F00\u59CB", "#4A6A50", "#6B8E6B", btnSize)
        startBtn.setOnClickListener {
            if (game.isPaused) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u6682\u505C\u4E2D\uFF0C\u8BF7\u5148\u6062\u590D\u6E38\u620F"); return@setOnClickListener }
            onStartOrRestart(myPlayer)
        }
        undoBtn = createPlaqueButton("\u21A9\n\u6094\u68CB", "#6B5540", "#A89078", btnSize)
        undoBtn.setOnClickListener {
            if (game.isPaused) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u6682\u505C\u4E2D\uFF0C\u65E0\u6CD5\u6094\u68CB"); return@setOnClickListener }
            requestUndo(myPlayer)
        }
        panel.addView(startBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply { bottomMargin = btnMargin; rightMargin = btnMargin2 })
        panel.addView(undoBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply { bottomMargin = 4; rightMargin = btnMargin2 })

        val exitBtn = createPlaqueButton("\uD83D\uDEAA\n\u9000\u51FA", "#5A3028", "#905850", btnSize)
        exitBtn.setOnClickListener {
            if (isReplaying) return@setOnClickListener  // V8.6
            if (game.isPaused && game.pausedByPlayer != myPlayer) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u6682\u505C\u4E2D\uFF0C\u8BF7\u7B49\u5F85\u5BF9\u65B9\u6062\u590D"); return@setOnClickListener }
            requestExit()
        }
        panel.addView(exitBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply { bottomMargin = 12; rightMargin = btnMargin2 })

        val settingsBtn = TextView(this).apply {
            text = "\u2699"; textSize = 26f; setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER; setPadding(10, 4, 10, 4)
            // V5.6: 加圆角背景让按钮更明显
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 12f
                setColor(Color.parseColor("#CC8D6E63")); setStroke(2, Color.parseColor("#FFD700"))
            }
            setOnClickListener {
                if (isReplaying) return@setOnClickListener  // V8.6
                if (game.isPaused && game.pausedByPlayer != myPlayer) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u6682\u505C\u4E2D\uFF0C\u8BF7\u7B49\u5F85\u5BF9\u65B9\u6062\u590D"); return@setOnClickListener }
                showSettingsDialog()
            }
        }
        panel.addView(settingsBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // V8.8: 注册回放时需禁用的右侧按钮（退出按钮不禁用）
        replaySensitiveViews.addAll(listOf(startBtn, undoBtn, settingsBtn))
        val panelLp = if (isLandscape()) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT) else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(panel, panelLp)
        setContentView(root)
    }

    private fun create3DButton(text: String, colorTop: String, colorBottom: String, size: Int) = Button(this).apply {
        this.text = text; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(12, 8, 12, 8); setBackgroundColor(Color.TRANSPARENT)
        val radius = if (size > 80) size * 0.22f else size * 0.28f
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = radius
            setSize(size, size)
            colors = intArrayOf(Color.parseColor(colorTop), Color.parseColor(colorBottom))
            orientation = GradientDrawable.Orientation.TOP_BOTTOM; setStroke(2, Color.parseColor("#88D4C8B8"))
        }
        elevation = 8f; isAllCaps = false
    }

    /** V7.2: 古典匾额风格大按钮 (雕梁画栋) */
    private fun createPlaqueButton(text: String, woodDark: String, woodLight: String, size: Int): Button {
        return Button(this).apply {
            this.text = text; textSize = 17f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#F5EDE0"))
            setShadowLayer(2f, 1f, 1f, Color.parseColor("#60000000"))
            setPadding(20, 12, 20, 12); setBackgroundColor(Color.TRANSPARENT)
            isAllCaps = false; typeface = android.graphics.Typeface.DEFAULT_BOLD
            val radius = size * 0.18f; val inset = (size * 0.08f).toInt()
            // 外层深色木框
            val frame = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = radius
                setSize(size, size)
                colors = intArrayOf(Color.parseColor(woodDark), Color.parseColor("#3A2010"))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            }
            // 内层浅色面板
            val panel = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = radius * 0.75f
                colors = intArrayOf(Color.parseColor(woodLight), Color.parseColor(woodDark))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(1, Color.parseColor("#60C8B090"))
            }
            val insetDrawable = android.graphics.drawable.InsetDrawable(panel, inset, inset, inset, inset)
            background = android.graphics.drawable.LayerDrawable(arrayOf(frame, insetDrawable))
            elevation = 6f
        }
    }

    private fun updateButtonState() {
        if (game.isActive && !game.isGameOver) setStartBtn("\uD83D\uDD04\n\u91CD\u65B0\u5F00\u59CB", "#FF8F00", "#E65100")
        else setStartBtn("\u25B6\n\u5F00\u59CB", "#4CAF50", "#2E7D32")
    }
    fun startEggOnMain() { goView.startEggAnimation(); startEggAnimLoop(); SoundFX.playEggSplat() }
    fun shakeMainForWhite() { shakeMainScreen() }
    fun syncPieceOrder(on: Boolean) { goView.showPieceOrder = on; goView.invalidate() }
    fun refreshBoardView() { goView.invalidate() }
    fun restartCountdown() { startRemindTimers() }
    fun updatePauseBtnForWhite(paused: Boolean) {
        pauseBtnBlack?.let {
            if (paused) {
                it.text = "\u23F8\n\u6682\u505C\u4E2D"
                it.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setSize(it.width, it.height)
                    colors = intArrayOf(Color.parseColor("#FF8F00"), Color.parseColor("#E65100"))
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM; setStroke(3, Color.parseColor("#CCFFFFFF"))
                }
            } else {
                it.text = "\u23F8\n\u6682\u505C"
                it.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setSize(it.width, it.height)
                    colors = intArrayOf(Color.parseColor("#607D8B"), Color.parseColor("#37474F"))
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM; setStroke(3, Color.parseColor("#CCFFFFFF"))
                }
            }
        }
    }
    private fun setStartBtn(text: String, top: String, bottom: String) {
        startBtn.text = text
        startBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(Color.parseColor(top), Color.parseColor(bottom))
            orientation = GradientDrawable.Orientation.TOP_BOTTOM; setStroke(3, Color.parseColor("#CCFFFFFF"))
        }
    }

    // ★ V10.0: 双 Activity 架构 — 反射 setLaunchDisplayId 启动副屏
    private fun launchWhiteScreen() {
        val myDisplayId = display?.displayId ?: 0
        // V10.0: 强制同步棋盘大小到prefs保存值
        val savedSize = prefs.getInt("board_size", 13)
        if (game.boardSize != savedSize) game.setBoardSize(savedSize)
        android.util.Log.i("GoGame", "launchWhiteScreen: mainBoard=${game.boardSize} savedPref=${savedSize}")
        GamePresentation.sharedPerspective = GoGame.PLAYER_WHITE
        GamePresentation.sharedGame = game
        goView.invalidate()

        // V10.0: 如果副屏Activity已存在且活跃，直接刷新（避免finish+startActivity竞态）
        val existing = gamePresentation
        if (existing != null && !existing.isFinishing && !existing.isDestroyed) {
            android.util.Log.i("GoGame", "launchWhiteScreen: reusing existing Activity, boardSize=${game.boardSize}")
            existing.onPiecePlaced = { r, c -> runOnUiThread { handlePiecePlaced(r, c) } }
            existing.onPassRequest = { runOnUiThread { handlePass(GoGame.PLAYER_WHITE) } }
            existing.onStartOrRestart = { runOnUiThread { onStartOrRestart(GoGame.PLAYER_WHITE) } }
            existing.onUndoRequest = { runOnUiThread { requestUndo(GoGame.PLAYER_WHITE) } }
            existing.getMainActivity = { this@MainActivity }
            existing.refreshView()
            updateCapturesDisplay()
            updateStatusDisplay()
            return
        }

        // 没有活跃实例 → 启动新的副屏Activity
        for (display in displayManager.displays) {
            if (display.displayId != myDisplayId && display.isValid) {
                android.util.Log.i("GoGame", "launchWhiteScreen: starting new Activity on display=${display.displayId}, boardSize=${game.boardSize}")
                val intent = Intent(this, GamePresentation::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    try {
                        val options = android.app.ActivityOptions.makeBasic()
                        val method = options.javaClass.getMethod(
                            "setLaunchDisplayId",
                            Int::class.javaPrimitiveType
                        )
                        method.invoke(options, display.displayId)
                        startActivity(intent, options.toBundle())
                    } catch (_: Exception) {
                        startActivity(intent)
                    }
                } else {
                    startActivity(intent)
                }
                // 延迟绑定回调（等待副屏 Activity 完全创建）
                handler.postDelayed({
                    GamePresentation.instance?.let { pres ->
                        pres.onPiecePlaced = { r, c -> runOnUiThread { handlePiecePlaced(r, c) } }
                        pres.onPassRequest = { runOnUiThread { handlePass(GoGame.PLAYER_WHITE) } }
                        pres.onStartOrRestart = { runOnUiThread { onStartOrRestart(GoGame.PLAYER_WHITE) } }
                        pres.onUndoRequest = { runOnUiThread { requestUndo(GoGame.PLAYER_WHITE) } }
                        pres.getMainActivity = { this@MainActivity }
                        gamePresentation = pres
                        pres.refreshView()
                        updateCapturesDisplay()
                        updateStatusDisplay()
                    }
                }, 600)
                return
            }
        }
    }

    internal fun dismissPresentation() {
        gamePresentation?.let {
            try { it.finish() } catch (_: Exception) {}
        }
        gamePresentation = null
    }

    private fun onStartOrRestart(player: Int) {
        if (isReplaying) return  // V8.6: 回放期间禁止操作
        if (game.isPaused) { showMsgToPlayer(player, "\u6E38\u620F\u6682\u505C\u4E2D\uFF0C\u8BF7\u5148\u6062\u590D\u6E38\u620F"); return }
        // ★ V6.1: KataGo 初始化中禁止开始
        if (kataGoEnabled && GameState.kataGoEngine?.isReady != true) {
            showMsgToPlayer(player, "\u23F3 KataGo \u521D\u59CB\u5316\u4E2D\uFF0C\u8BF7\u7A0D\u5019...")
            return
        }
        // ★ V5.5: 模型加载中禁止开始
        val kg = GameState.kataGoEngine
        if (kg != null && kg.boardLoading) {
            showMsgToPlayer(player, "\u23F3 \u6A21\u578B\u52A0\u8F7D\u4E2D\uFF0C\u8BF7\u7A0D\u5019...")
            return
        }
        // 围棋规则：黑方永远先手，无论谁按开始
        if (!game.isActive) { game.startGame(); onGameStarted() }
        else if (!game.isGameOver) requestRestart(player)
        else doRestart()
    }
    /** V4.5: 异步通知 KataGo 落子（不阻塞 UI） */
    private fun notifyKataGoMove(row: Int, col: Int, player: Int) {
        val kg = GameState.kataGoEngine ?: return
        if (!kg.isReady) return
        val color = if (player == GoGame.PLAYER_BLACK) "b" else "w"
        Thread {
            try { kg.playMove(color, row, col) }
            catch (e: Exception) { Log.e("KataGo", "Notify move failed: ${e.message}") }
        }.start()
    }

    /** 重置 KataGo 棋盘（仅清空+贴目，不动 boardsize） */
    private fun resetKataGoBoard() {
        val kg = GameState.kataGoEngine ?: return
        if (!kg.isReady) return
        GameState.initialSyncDone = false  // V10.2: 新对局重置同步标志
        try {
            kg.clearBoard()
            kg.setKomi(GoGame.KOMI.toFloat())
        } catch (e: Exception) {
            Log.e("KataGo", "Board reset failed: ${e.message}")
        }
    }

    /** V9.3: 异步同步让子到 KataGo（不碰 boardsize，只同步棋盘状态） */
    private fun asyncSyncKataGoBoard() {
        val kg = GameState.kataGoEngine ?: return
        if (!kg.isReady) return
        Thread {
            try {
                kg.clearBoard()
                kg.setKomi(GoGame.KOMI.toFloat())
                var count = 0
                for (row in 0 until game.boardSize) {
                    for (col in 0 until game.boardSize) {
                        val piece = game.board[row][col]
                        if (piece != GoGame.EMPTY) {
                            val color = if (piece == GoGame.PLAYER_BLACK) "b" else "w"
                            kg.playMove(color, row, col)
                            count++
                        }
                    }
                }
                Log.i("KataGo", "Board synced async: $count moves")
                GameState.initialSyncDone = true  // V10.2: 标记同步完成
                // V10.2: 同步后对比双方棋盘，诊断让子不同步问题
                val kgBoard = kg.showboard()
                val ggBoard = game.dumpBoard()
                Log.i("KataGo", "=== KataGo showboard after sync ===\n$kgBoard")
                Log.i("KataGo", "=== GoGame dumpBoard after sync ===\n$ggBoard")
            } catch (e: Exception) {
                Log.e("KataGo", "Board sync failed: ${e.message}")
            }
        }.start()
    }

    private fun onGameStarted() {
        goView.clearPreview(); goView.invalidate(); gamePresentation?.refreshView()
        updateButtonState(); updateStatusDisplay(); updateCapturesDisplay()
        gamePresentation?.updateButtonState()
        // V9.3: 先同步 KataGo 棋盘（含让子），再启动计时器
        resetKataGoBoard()
        if (game.handicapStones >= 2) asyncSyncKataGoBoard()
        // V8.5: 让子后白方先行，提示音对应
        val voiceRes = if (game.currentPlayer == GoGame.PLAYER_BLACK) R.raw.your_turn_black else R.raw.your_turn_white
        SoundFX.playVoice(this, voiceRes)
        // V8.6: 启动录像
        if (recordingEnabled) {
            RecordingManager.startRecording(game.boardSize, game.handicapStones)
        }
        // V9.3: 最后启动计时器（确保让子已同步到KataGo）
        startRemindTimers()
    }
    private fun requestRestart(player: Int) {
        if (player == GoGame.PLAYER_BLACK) {
            gamePresentation?.showRestartRequestDialog(game.getPlayerName(player)) { a ->
                if (a) runOnUiThread { doRestart() }
                else runOnUiThread { showMsgToPlayer(player, "\u5BF9\u65B9\u62D2\u7EDD\u91CD\u65B0\u5F00\u59CB") }
            }
        } else {
            showRestartRequestDialog(game.getPlayerName(player)) { a ->
                if (a) doRestart() else showMsgToPlayer(player, "\u5BF9\u65B9\u62D2\u7EDD\u91CD\u65B0\u5F00\u59CB")
            }
        }
    }
    fun doRestart() {
        animator?.cancel(); stopRemindTimers()
        // 围棋规则：黑方永远先手
        game.startGame()
        pauseBtnBlack?.let {
            it.text = "\u23F8\n\u6682\u505C"
            it.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setSize(it.width, it.height)
                colors = intArrayOf(Color.parseColor("#607D8B"), Color.parseColor("#37474F"))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM; setStroke(3, Color.parseColor("#CCFFFFFF"))
            }
        }
        gamePresentation?.updatePauseBtn(false)
        goView.clearAllAnimations(); gamePresentation?.clearAllAnimations()
        goView.clearPreview(); goView.clearMessage(); goView.invalidate()
        gamePresentation?.clearPreview(); gamePresentation?.clearMessage(); gamePresentation?.refreshView()
        updateButtonState(); updateStatusDisplay(); updateCapturesDisplay()
        gamePresentation?.updateButtonState()
        // V9.3: 先同步 KataGo（含让子），再启动计时器
        resetKataGoBoard()
        if (game.handicapStones >= 2) asyncSyncKataGoBoard()
        val voiceRes = if (game.currentPlayer == GoGame.PLAYER_BLACK) R.raw.your_turn_black else R.raw.your_turn_white
        SoundFX.playVoice(this, voiceRes)
        // V8.6: 重开也启动录像
        if (recordingEnabled) {
            RecordingManager.startRecording(game.boardSize, game.handicapStones)
        }
        // V9.3: 最后启动计时器
        startRemindTimers()
    }
    fun showRestartRequestDialog(requesterName: String, callback: (Boolean) -> Unit) {
        AlertDialog.Builder(this).setTitle("\u91CD\u65B0\u5F00\u59CB\u8BF7\u6C42")
            .setMessage("${requesterName}\u8BF7\u6C42\u91CD\u65B0\u5F00\u59CB\uFF0C\u662F\u5426\u540C\u610F\uFF1F")
            .setPositiveButton("\u540C\u610F") { _, _ -> callback(true) }
            .setNegativeButton("\u4E0D\u540C\u610F") { _, _ -> callback(false) }
            .setCancelable(false).show()
    }
    fun showUndoRequestDialog(requesterName: String, callback: (Boolean) -> Unit) {
        AlertDialog.Builder(this).setTitle("\u6094\u68CB\u8BF7\u6C42")
            .setMessage("${requesterName}\u8BF7\u6C42\u6094\u68CB\uFF0C\u662F\u5426\u540C\u610F\uFF1F")
            .setPositiveButton("\u540C\u610F") { _, _ -> callback(true) }
            .setNegativeButton("\u4E0D\u540C\u610F") { _, _ -> callback(false) }
            .setCancelable(false).show()
    }
    // V8.5: 白方请求退出时，黑方确认对话框
    fun showExitRequestDialog(requesterName: String, callback: (Boolean) -> Unit) {
        AlertDialog.Builder(this).setTitle("\u9000\u51FA\u6E38\u620F\u8BF7\u6C42")
            .setMessage("${requesterName}\u8BF7\u6C42\u9000\u51FA\u6E38\u620F\uFF0C\u662F\u5426\u540C\u610F\uFF1F")
            .setPositiveButton("\u540C\u610F") { _, _ -> callback(true) }
            .setNegativeButton("\u4E0D\u540C\u610F") { _, _ -> callback(false) }
            .setCancelable(false).show()
    }
    private fun shakeMainScreen() {
        val v = goView
        ValueAnimator.ofFloat(0f, 16f, -16f, 12f, -12f, 10f, -10f, 8f, -8f, 5f, -5f, 3f, -3f, 0f).apply {
            duration = 2000; addUpdateListener { v.translationX = it.animatedValue as Float }; start()
        }
    }
    private fun requestExit() {
        if (game.isActive && !game.isGameOver) {
            gamePresentation?.showExitRequestDialog(game.getPlayerName(myPlayer)) { a ->
                if (a) runOnUiThread { exitApp() }
                else runOnUiThread { showMsgToPlayer(myPlayer, "\u5BF9\u65B9\u62D2\u7EDD\u9000\u51FA") }
            }
        } else { exitApp() }
    }
    // V10.1: 白方（副屏）请求退出 → 对话框显示在黑方（主屏）
    internal fun requestExitFromWhite() {
        if (game.isActive && !game.isGameOver) {
            showExitRequestDialog(game.getPlayerName(GoGame.PLAYER_WHITE)) { a ->
                if (a) runOnUiThread { exitApp() }
                else runOnUiThread { gamePresentation?.showPopupMessage("\u5BF9\u65B9\u62D2\u7EDD\u9000\u51FA") }
            }
        } else { exitApp() }
    }
    internal fun exitApp() {
        if (recordingEnabled && game.isActive && RecordingManager.currentRecording != null) {
            RecordingManager.finishRecording(this)
        }
        stopRemindTimers(); animator?.cancel(); animator = null
        countdownRunnable?.let { handler.removeCallbacks(it) }; countdownRunnable = null
        goView.clearAllAnimations()
        try { gamePresentation?.clearAllAnimations() } catch (_: Exception) {}
        // V10.0: 双Activity架构 — finish副屏Activity再finish主屏
        dismissPresentation()
        BgMusic.stop()
        GameState.kataGoEngine?.shutdown()
        GameState.kataGoEngine = null
        finish()
    }
    private fun requestUndo(player: Int) {
        if (!game.isActive || game.isGameOver) { showMsg("\u65E0\u6CD5\u6094\u68CB"); return }
        if (game.isPaused) { showMsgToPlayer(player, "\u6E38\u620F\u6682\u505C\u4E2D\uFF0C\u65E0\u6CD5\u6094\u68CB"); return }
        if (!game.canUndo(player)) { showMsg("\u4F60\u8FD8\u6CA1\u6709\u843D\u5B50"); return }
        // V8.5: AI自动时直接悔棋，无需对方确认
        if (player == GoGame.PLAYER_BLACK && autoPlayWhite) { doUndo(player); return }
        val oppScreen = if (player == GoGame.PLAYER_BLACK) gamePresentation else null
        val cb: (Boolean) -> Unit = { a ->
            if (a) doUndo(player)
            else { showMsgToPlayer(player, "\u5BF9\u65B9\u62D2\u7EDD\u4E86\u6094\u68CB\u8BF7\u6C42"); SoundFX.playVoice(this, R.raw.reject_undo) }
        }
        if (player == GoGame.PLAYER_BLACK && oppScreen != null) {
            oppScreen.showUndoRequestDialog(game.getPlayerName(player), cb)
        } else if (player == GoGame.PLAYER_WHITE && oppScreen == null) {
            showUndoRequestDialog(game.getPlayerName(player), cb)
        } else { doUndo(player) }
    }
    private fun doUndo(player: Int) {
        val r = game.undo(player)
        if (r.success) {
            // V9.6: 记录悔棋标记（保留所有历史着法，不删除）
            if (recordingEnabled && RecordingManager.currentRecording != null) {
                RecordingManager.recordUndo(player, r.undoCount)
            }
            SoundFX.playVoice(this, R.raw.taunt_undo)
            goView.clearPreview(); goView.clearMessage(); goView.invalidate()
            gamePresentation?.clearMessage(); gamePresentation?.refreshView()
            updateStatusDisplay(); updateCapturesDisplay()
            // ★ V5.2: 悔棋后重新同步 Katago 棋盘（GoGame 已回滚，KataGo 必须跟进）
            asyncSyncKataGoBoard()
            startRemindTimers()
        }
    }

    private fun showPopupMessage(msg: String) {
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return
        for (i in root.childCount - 1 downTo 0) { if (root.getChildAt(i)?.tag == "popup_msg") root.removeViewAt(i) }
        val c = FrameLayout(this).apply { tag = "popup_msg"; setOnClickListener { } }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(40, 28, 40, 28)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 22f
                setColor(Color.parseColor("#EE2D2D2D")); setStroke(3, Color.parseColor("#CCFFD700"))
            }
            alpha = 0f; animate().alpha(1f).setDuration(250).start()
        }
        card.addView(TextView(this).apply {
            text = msg; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setShadowLayer(4f, 0f, 2f, Color.parseColor("#AA000000"))
        })
        c.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        root.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        c.setOnClickListener { card.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(c) }.start() }
        handler.postDelayed({
            card.animate().alpha(0f).setDuration(300).withEndAction { (c.parent as? ViewGroup)?.removeView(c) }.start()
        }, 2200)
    }
    private fun showMsg(m: String) { showPopupMessage(m); gamePresentation?.showPopupMessage(m) }
    internal fun showMsgToPlayer(player: Int, m: String) {
        if (player == myPlayer) showPopupMessage(m) else gamePresentation?.showPopupMessage(m)
    }

    private fun handlePass(player: Int) {
        if (isReplaying) return  // V8.6: 回放期间禁止操作
        val r = game.pass(player)
        if (!r.success) { showMsg(r.message); return }
        // V8.6: 录像记录虚手
        if (recordingEnabled && RecordingManager.currentRecording != null) {
            RecordingManager.recordPass(player)
        }
        SoundFX.playStoneSound()
        goView.clearPreview(); goView.clearHint(); goView.clearTerritory(); goView.clearMessage(); goView.clearAnalysis(); goView.invalidate()
        gamePresentation?.clearMessage(); gamePresentation?.clearHint(); gamePresentation?.clearAnalysis(); gamePresentation?.refreshView()
        updateStatusDisplay(); updateCapturesDisplay()
        if (r.gameOver) {
            stopRemindTimers(); updateButtonState(); gamePresentation?.updateButtonState()
            if (r.winner == GoGame.PLAYER_BLACK) {
                goView.startWinAnimation(); gamePresentation?.startLoseAnimation()
                SoundFX.playVoice(this, R.raw.win_black)
            } else if (r.winner == GoGame.PLAYER_WHITE) {
                goView.startLoseAnimation(); gamePresentation?.startWinAnimation()
                SoundFX.playVoice(this, R.raw.win_white)
            }
            startAnimationLoop()
            // V8.6: 对局结束，保存录像
            if (recordingEnabled) RecordingManager.finishRecording(this)
        } else startRemindTimers()
    }

    private fun handlePiecePlaced(row: Int, col: Int, isAutoPlay: Boolean = false) {
        if (isReplaying) return  // V8.6: 回放期间禁止操作
        val r = game.placePiece(row, col, game.currentPlayer)
        if (!r.success) { showMsg(r.message); return }
        // V9.1: 录像记录落子（含被提子位置）
        if (recordingEnabled && RecordingManager.currentRecording != null) {
            val who = if (game.currentPlayer == GoGame.PLAYER_BLACK) GoGame.PLAYER_WHITE else GoGame.PLAYER_BLACK
            RecordingManager.recordMove(who, row, col, r.captures, r.capturedStones)
            if (r.captures > 0) Log.i("GoGame", "  📹 录像: ${if(who==GoGame.PLAYER_BLACK)"B" else "W"}提${r.captures}子 ${r.capturedStones}")
            else Log.i("GoGame", "  📹 录像: ${if(who==GoGame.PLAYER_BLACK)"B" else "W"}落子($row,$col)")
        }
        // ★ V5.2: genMove 已自动落子，自动落子不重复通知 KataGo
        if (!isAutoPlay) {
            notifyKataGoMove(row, col, if (game.currentPlayer == GoGame.PLAYER_BLACK) GoGame.PLAYER_WHITE else GoGame.PLAYER_BLACK)
        }
        SoundFX.playStoneSound()
        if (r.captures > 0) SoundFX.playCaptureSound()
        // ★ V6.3: 清除提示圈、领土标记和分析数据
        goView.clearPreview(); goView.clearHint(); goView.clearTerritory(); goView.clearAnalysis(); goView.invalidate()
        gamePresentation?.clearHint(); gamePresentation?.clearAnalysis(); gamePresentation?.refreshView()
        updateStatusDisplay(); updateCapturesDisplay()
        if (r.gameOver) {
            stopRemindTimers(); updateButtonState(); gamePresentation?.updateButtonState()
            if (r.winner == GoGame.PLAYER_BLACK) {
                goView.startWinAnimation(); gamePresentation?.startLoseAnimation()
                SoundFX.playVoice(this, R.raw.win_black)
            } else if (r.winner == GoGame.PLAYER_WHITE) {
                goView.startLoseAnimation(); gamePresentation?.startWinAnimation()
                SoundFX.playVoice(this, R.raw.win_white)
            }
            startAnimationLoop()
            // V8.6: 对局结束，保存录像
            if (recordingEnabled) RecordingManager.finishRecording(this)
        } else startRemindTimers()
    }

    fun updateStatusDisplay() {
        val g = game
        statusText.text = if (!g.isActive) "\u26AB\u9ED1\u65B9\n\u7B49\u5F85\u5F00\u59CB..."
        else if (g.isGameOver) {
            if (g.winner == myPlayer) "\uD83C\uDFC6\u4F60\u8D62\u4E86\uFF01"
            else if (g.winner == GoGame.EMPTY) "\uD83E\uDD1D\u5E73\u5C40"
            else "\uD83D\uDE1E\u4F60\u8F93\u4E86"
        } else if (g.isPaused) {
            if (g.pausedByPlayer == myPlayer) "\u26AB\u9ED1\u65B9\n\u23F8 \u4F60\u6682\u505C\u4E86\u6E38\u620F"
            else "\u26AB\u9ED1\u65B9\n\u23F8 \u5BF9\u65B9\u6682\u505C\u4E2D"
        } else {
            if (g.currentPlayer == myPlayer) "\u26AB\u9ED1\u65B9\n\uD83D\uDC49\u8F6E\u5230\u4F60\u4E86"
            else "\u26AB\u9ED1\u65B9\n\u23F3\u7B49\u5F85\u5BF9\u65B9"
        }
        gamePresentation?.updateStatusText()
    }

    fun updateCapturesDisplay() {
        val cap = "\u26AB \u63D0\u5B50: ${game.capturedByBlack} | \u26AA \u63D0\u5B50: ${game.capturedByWhite}"
        capturesText.text = cap
        gamePresentation?.setCapturesText(cap)
    }

    fun handleHintRequest() { handleHint() }

    /** V6.9: AI提示 - 1s分析3候选，思考期间禁止落子 */
    fun runKataAnalyze(player: Int) {
        val kg = GameState.kataGoEngine ?: return
        if (!kg.isReady || aiThinking) return
        aiThinking = true
        // V6.9: 思考期间禁止落子
        goView.autoPlayBlock = true
        gamePresentation?.goView?.autoPlayBlock = true
        val thinkingMsg = "🤖 AI 思考中..."
        if (player == myPlayer) { goView.message = thinkingMsg; goView.invalidate() }
        else gamePresentation?.showMessage(thinkingMsg)
        val color = if (player == GoGame.PLAYER_BLACK) "b" else "w"
        Thread {
            try {
                val items = kg.analyze(color, maxTime = 1f, maxMoves = 3)
                handler.post {
                    aiThinking = false
                    kg.stopSearch()
                    // V6.9: 恢复落子
                    goView.autoPlayBlock = false
                    gamePresentation?.goView?.autoPlayBlock = false
                    if (player == myPlayer) { goView.clearMessage(); goView.clearAnalysis() }
                    else { gamePresentation?.clearMessage(); gamePresentation?.clearAnalysis() }
                    if (items.isEmpty()) {
                        showMsgToPlayer(player, "🤖 暂无推荐，请自行判断")
                        return@post
                    }
                    val best = items.first()
                    val wrPct = "%.0f".format(best.winrate * 100)
                    val leadDesc = when {
                        best.scoreLead > 0.5f -> "领先${"%.1f".format(best.scoreLead)}目"
                        best.scoreLead < -0.5f -> "落后${"%.1f".format(Math.abs(best.scoreLead))}目"
                        else -> "均势"
                    }
                    val easyMsg = "🤖 推荐落这里！胜率${wrPct}% · $leadDesc"
                    if (player == myPlayer) {
                        goView.showHint(best.row, best.col)
                        goView.message = easyMsg
                    } else {
                        gamePresentation?.showHint(best.row, best.col)
                        gamePresentation?.showMessage(easyMsg)
                    }
                    val pts = items.map { GoView.AnalysisPoint(it.row, it.col, it.winrate, it.visits, it.scoreLead) }
                    if (player == myPlayer) goView.showAnalysis(pts)
                    else gamePresentation?.showAnalysis(pts)
                    goView.invalidate(); gamePresentation?.refreshView()
                }
            } catch (e: Exception) {
                handler.post {
                    aiThinking = false
                    kg.stopSearch()
                    goView.autoPlayBlock = false
                    gamePresentation?.goView?.autoPlayBlock = false
                    showMsgToPlayer(player, "🤖 分析中断，请重试")
                }
            }
        }.start()
    }

    /** 供 GamePresentation 调用的后台 AI 计算 */
    @JvmField var computeHintFor: ((Int, (Triple<Int,Int,String>?) -> Unit) -> Unit) = { player, cb ->
        computeAiAsync(player, cb)
    }

    /** 后台 AI 计算（防 ANR + 超时保护 + V4.8 统一未就绪提示） */
    private var aiThinking = false
    private var aiTimeoutRunnable: Runnable? = null
    private var aiComputeDone = false  // V8.5: 防止超时/线程双重回调
    private var aiSpinnerRunnable: Runnable? = null  // V10.2: 思考动画
    private val spinnerFrames = arrayOf("\u25D0", "\u25D3", "\u25D1", "\u25D2")  // ◐◓◑◒
    private var spinnerIdx = 0
    private fun computeAiAsync(player: Int, onDone: (Triple<Int,Int,String>?) -> Unit) {
        if (isReplaying) return  // V8.6: 回放期间禁止 AI
        if (aiThinking) return
        // ★ V6.1: KataGo 已关闭
        if (!kataGoEnabled) {
            val msg = "⚠️ KataGo已关闭，请在设置中开启"
            goView.message = msg; goView.invalidate()
            gamePresentation?.showMessage(msg)
            onDone(null)
            return
        }
        // ★ V4.8: KataGo 未就绪 → 双方屏幕都提示
        if (GameState.kataGoEngine?.isReady != true) {
            val msg = "\u23F3 KataGo \u5F15\u64CE\u542F\u52A8\u4E2D\uFF0C\u8BF7\u7A0D\u5019..."
            goView.message = msg; goView.invalidate()
            gamePresentation?.showMessage(msg)
            onDone(null)
            return
        }
        aiThinking = true; aiComputeDone = false; spinnerIdx = 0
        // V10.2: 先清理上一次的 timeout/spinner，防止旧回调吞掉新结果
        aiTimeoutRunnable?.let { handler.removeCallbacks(it) }
        aiSpinnerRunnable?.let { handler.removeCallbacks(it) }
        val baseMsg = "\uD83E\uDD16 KataGo \u6DF1\u5EA6\u601D\u8003\u4E2D"
        // V10.2: 旋转动画 — 50ms/帧 = 20fps 丝滑
        aiSpinnerRunnable = object : Runnable {
            override fun run() {
                if (aiComputeDone) return
                spinnerIdx = (spinnerIdx + 1) % spinnerFrames.size
                val animMsg = "$baseMsg ${spinnerFrames[spinnerIdx]}"
                if (player == myPlayer) { goView.message = animMsg; goView.invalidate() }
                else gamePresentation?.showMessage(animMsg)
                handler.postDelayed(this, 50)
            }
        }
        handler.post(aiSpinnerRunnable!!)  // V10.2: 50ms一帧 = 20fps 丝滑旋转
        // ★ V4.4: 30 秒超时保护，防止死锁
        aiTimeoutRunnable = Runnable {
            if (aiComputeDone) return@Runnable
            aiComputeDone = true; aiThinking = false
            aiSpinnerRunnable?.let { handler.removeCallbacks(it) }
            Log.w("KataGo", "AI thinking timeout! Resetting aiThinking flag")
            handler.post {
                if (player == myPlayer) { goView.message = "⚠️ AI 超时，请重试"; goView.invalidate() }
                else gamePresentation?.showMessage("⚠️ AI 超时")
                onDone(null)
            }
        }
        handler.postDelayed(aiTimeoutRunnable!!, 30000)  // V9.3: 30s
        Thread {
            val result = try { game.suggestMove(player) } catch (_: Exception) { null }
            handler.post {
                if (aiComputeDone) return@post
                aiComputeDone = true
                aiSpinnerRunnable?.let { handler.removeCallbacks(it) }
                aiTimeoutRunnable?.let { handler.removeCallbacks(it) }
                aiTimeoutRunnable = null
                aiThinking = false
                onDone(result)
            }
        }.start()
    }

    /** V6.5: 统一使用 kata-analyze 深度分析 */
    private fun handleHint() {
        if (isReplaying) return  // V8.6: 回放期间禁止操作
        if (aiThinking) { showMsgToPlayer(myPlayer, "🤖 正在思考中，请稍候..."); return }
        if (!kataGoEnabled) { showMsgToPlayer(myPlayer, "⚠️ 请先在设置中开启 KataGo"); return }
        if (GameState.kataGoEngine?.isReady != true) {
            showMsgToPlayer(myPlayer, "⏳ KataGo 引擎启动中，请稍候..."); return
        }
        runKataAnalyze(myPlayer)
    }

    /** 自动落子：分析结果显示在落子方屏幕 */
    private fun executeAutoPlay(player: Int) {
        if (aiThinking) return
        computeAiAsync(player) { hint ->
            if (player == myPlayer) goView.clearMessage()
            else gamePresentation?.clearMessage()
            if (hint != null) {
                val (row, col, reason) = hint
                handlePiecePlaced(row, col, isAutoPlay = true)
                if (player == myPlayer) {
                    goView.message = reason
                    goView.invalidate()
                } else {
                    gamePresentation?.showMessage(reason)
                }
            } else {
                // V9.3: AI 虚手/认输时给双方提示
                val whoName = if (player == GoGame.PLAYER_BLACK) "黑" else "白"
                val passMsg = "\uD83E\uDD16 AI\uFF08${whoName}\u65B9\uFF09\u865A\u624B"
                goView.message = passMsg; goView.invalidate()
                gamePresentation?.showMessage(passMsg)
                handlePass(player)
            }
        }
    }

    private fun handleScoring() {
        if (isReplaying) return  // V8.6: 回放期间禁止操作
        if (!game.isActive || game.isGameOver) return
        // V8.5: 根据棋盘大小限制最低手数
        val minMoves = when (game.boardSize) { 9 -> 40; 13 -> 80; else -> 160 }
        if (game.totalMoves < minMoves) { showMsgToPlayer(myPlayer, "\u81F3\u5C11${minMoves}\u624B\u540E\u624D\u80FD\u6570\u5B50"); return }
        game.calculateTerritoryTemp()
        val bTotal = game.blackTerritory + game.capturedByBlack
        val wTotal = game.whiteTerritory + game.capturedByWhite + GoGame.KOMI
        val wTotalStr = String.format("%.1f", wTotal)
        val leadStr: String
        val leadAmt: String
        if (bTotal > wTotal) {
            leadStr = "\u9ED1\u65B9\u9886\u5148"
            leadAmt = String.format("%.1f", bTotal - wTotal)
        } else {
            leadStr = "\u767D\u65B9\u9886\u5148"
            leadAmt = String.format("%.1f", wTotal - bTotal)
        }
        val msg = "\u5F53\u524D\u6570\u76EE\u4F30\u7B97\n" +
            "\u9ED1\u65B9: \u5730\u76D8${game.blackTerritory} + \u63D0\u5B50${game.capturedByBlack} = ${game.blackTerritory + game.capturedByBlack}\n" +
            "\u767D\u65B9: \u5730\u76D8${game.whiteTerritory} + \u63D0\u5B50${game.capturedByWhite} + \u8D34\u76EE${GoGame.KOMI} = $wTotalStr\n" +
            "$leadStr $leadAmt \u76EE"
        showMsgToPlayer(myPlayer, msg)
    }

    private fun startAnimationLoop() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 33; repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val a1 = goView.updateAnimation()
                val a2 = gamePresentation?.updateAnimation() ?: false
                if (!a1 && !a2) cancel()
            }
            start()
        }
    }
    private fun startEggAnimLoop() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 80; repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val a1 = goView.updateAnimation()
                val a2 = gamePresentation?.updateAnimation() ?: false
                if (!a1 && !a2) cancel()
            }
            start()
        }
    }
    private fun startRemindTimers() {
        stopRemindTimers(); autoPlayRunnable?.let { handler.removeCallbacks(it) }; autoPlayRunnable = null
        if (!game.isActive || game.isGameOver) return
        if (game.isPaused) { countdownText.text = "\u23F8"; gamePresentation?.setCountdown(-2); return }
        val cur = game.currentPlayer
        val myTurn = cur == myPlayer
        val isAutoPlay = if (myTurn) autoPlayEnabled else autoPlayWhite
        if (isAutoPlay) {
            // V10.2: 自动落子 — 1s准备 + 旋转动画
            countdownText.text = "\uD83E\uDD16 ◐"
            autoPlayRunnable = object : Runnable {
                var tick = 0
                override fun run() {
                    if (!game.isActive || game.isGameOver || game.isPaused) { autoPlayRunnable = null; return }
                    if (game.currentPlayer != cur) { startRemindTimers(); return }
                    tick++
                    if (tick >= 20) {  // 20 ticks × 50ms = 1s 准备
                        countdownText.text = ""
                        autoPlayRunnable = null
                        executeAutoPlay(cur)
                        return
                    }
                    val frame = spinnerFrames[tick % spinnerFrames.size]
                    countdownText.text = "\uD83E\uDD16 $frame"
                    handler.postDelayed(this, 50)
                }
            }
            handler.postDelayed(autoPlayRunnable!!, 50)
            return
        }
        secondsLeft = 60
        if (myTurn) updateCountdown() else countdownText.text = ""
        gamePresentation?.setCountdown(if (cur == GoGame.PLAYER_WHITE) secondsLeft else -1)
        countdownRunnable = object : Runnable {
            override fun run() {
                if (!game.isActive || game.isGameOver) { stopRemindTimers(); return }
                if (game.isPaused) { countdownText.text = "\u23F8"; gamePresentation?.setCountdown(-2); return }
                if (game.currentPlayer != cur) { startRemindTimers(); return }
                secondsLeft--
                if (secondsLeft <= 0) {
                    if (cur == myPlayer) goView.startEggAnimation() else gamePresentation?.startEggAnimation()
                    startEggAnimLoop()
                    SoundFX.playEggSplat()
                    SoundFX.playVoice(this@MainActivity, if (cur == GoGame.PLAYER_BLACK) R.raw.too_slow_black else R.raw.too_slow_white)
                    secondsLeft = 60
                    if (cur == myPlayer) updateCountdown()
                    gamePresentation?.setCountdown(if (cur == GoGame.PLAYER_WHITE) secondsLeft else -1)
                    handler.postDelayed(this, 1000)
                    return
                }
                if (cur == myPlayer) updateCountdown()
                gamePresentation?.setCountdown(if (cur == GoGame.PLAYER_WHITE) secondsLeft else -1)
                if (secondsLeft == 30) {
                    SoundFX.playVoice(this@MainActivity, if (cur == GoGame.PLAYER_BLACK) R.raw.wait_30s_black else R.raw.wait_30s_white)
                    if (cur == myPlayer) shakeMainScreen() else gamePresentation?.shakeScreen()
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(countdownRunnable!!, 1000)
    }
    private fun updateCountdown() { countdownText.text = "\u23F1${secondsLeft}s" }
    private fun stopRemindTimers() {
        countdownRunnable?.let { handler.removeCallbacks(it) }; countdownRunnable = null
        if (::countdownText.isInitialized) countdownText.text = ""
        gamePresentation?.setCountdown(-1)
    }

    private fun showSettingsDialog() {
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return
        val overlay = FrameLayout(this).apply { tag = "settings_dlg"; setBackgroundColor(Color.parseColor("#80000000")) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(36, 30, 36, 24)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 22f
                setColor(Color.parseColor("#F5F0E8")); setStroke(3, Color.parseColor("#FF8F00"))
            }
            alpha = 0f; animate().alpha(1f).setDuration(250).start()
        }
        card.addView(TextView(this).apply {
            text = "\u2699 \u6E38\u620F\u8BBE\u7F6E"; textSize = 20f; setTextColor(Color.parseColor("#4A3728"))
            gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, 20)
        })
        card.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#DDD7C8"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply { bottomMargin = 16 }
        })
        // 落子音效
        val sRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 12, 16, 12)
        }
        val sLabel = TextView(this).apply {
            text = "\uD83D\uDD0A \u843D\u5B50\u97F3\u6548:"
            textSize = 15f; setTextColor(Color.parseColor("#4A3728"))
        }
        sRow.addView(sLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        val sSw = Switch(this).apply {
            isChecked = SoundFX.stoneSoundEnabled
        }
        sRow.addView(sSw)
        val sTxt = TextView(this).apply {
            text = if (sSw.isChecked) "\u5F00" else "\u5173"
            textSize = 13f; setTextColor(Color.parseColor("#6D4C41"))
            gravity = Gravity.CENTER_VERTICAL; setPadding(10, 0, 0, 0)
        }
        sRow.addView(sTxt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        sSw.setOnCheckedChangeListener { _, on ->
            SoundFX.stoneSoundEnabled = on; prefs.edit().putBoolean("stone_sound", on).apply()
            sTxt.text = if (on) "\u5F00" else "\u5173"
        }
        card.addView(sRow)
        // 语音音色
        val vRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 12, 16, 12)
        }
        val vLabel = TextView(this).apply {
            text = "\uD83C\uDFA4 \u64AD\u62A5\u97F3\u8272:"
            textSize = 15f; setTextColor(Color.parseColor("#4A3728"))
        }
        vRow.addView(vLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        val vSw = Switch(this).apply { isChecked = SoundFX.voiceStyle == 1 }
        vRow.addView(vSw)
        val vTxt = TextView(this).apply {
            text = if (vSw.isChecked) "\u4E1C\u5317\u8BDD" else "\u6807\u51C6"
            textSize = 13f; setTextColor(Color.parseColor("#6D4C41"))
            gravity = Gravity.CENTER_VERTICAL; setPadding(10, 0, 0, 0)
        }
        vRow.addView(vTxt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        vSw.setOnCheckedChangeListener { _, on ->
            SoundFX.voiceStyle = if (on) 1 else 0; prefs.edit().putInt("voice_style", SoundFX.voiceStyle).apply()
            vTxt.text = if (on) "\u4E1C\u5317\u8BDD" else "\u6807\u51C6"
        }
        card.addView(vRow)
        // 棋子序号
        val pRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 12, 16, 16)
        }
        val pLabel = TextView(this).apply {
            text = "\uD83D\uDD22 \u68CB\u5B50\u5E8F\u53F7:"
            textSize = 15f; setTextColor(Color.parseColor("#4A3728"))
        }
        pRow.addView(pLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        val pSw = Switch(this).apply { isChecked = prefs.getBoolean("piece_order", false) }
        pRow.addView(pSw)
        val pTxt = TextView(this).apply {
            text = if (pSw.isChecked) "\u663E\u793A" else "\u9690\u85CF"
            textSize = 13f; setTextColor(Color.parseColor("#6D4C41"))
            gravity = Gravity.CENTER_VERTICAL; setPadding(10, 0, 0, 0)
        }
        pRow.addView(pTxt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        pSw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("piece_order", on).apply()
            syncPieceOrder(on); gamePresentation?.updatePieceOrder(on)
            pTxt.text = if (on) "\u663E\u793A" else "\u9690\u85CF"
        }
        card.addView(pRow)

        // 难度等级
        val diffLabel = TextView(this).apply {
            text = "\uD83C\uDFAF \u96BE\u5EA6\u7B49\u7EA7: ${game.getDifficultyLabel()}"
            textSize = 16f; setTextColor(Color.parseColor("#4A3728"))
            setPadding(0, 8, 0, 8)
        }
        card.addView(diffLabel)
        val diffRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 0, 0, 12) }
        val diffs = arrayOf("\u4F4E 9\u00D79", "\u4E2D 13\u00D713", "\u9AD8 19\u00D719")
        val diffSizes = intArrayOf(9, 13, 19)
        val curDiffIdx = diffSizes.indexOf(game.boardSize).coerceAtLeast(0)
        for (i in diffs.indices) {
            val db = Button(this).apply {
                text = diffs[i]; textSize = 12f; setTextColor(Color.WHITE)
                setPadding(10, 8, 10, 8); isAllCaps = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = 12f
                    setColor(Color.parseColor(if (i == curDiffIdx) "#FF6F00" else "#8D6E63"))
                }
                setOnClickListener {
                    if (game.isActive) { showPopupMessage("\u8BF7\u5148\u7ED3\u675F\u5F53\u524D\u5BF9\u5C40\u540E\u5207\u6362\u96BE\u5EA6"); return@setOnClickListener }
                    // V6.1: KataGo 初始化中禁止切换，避免状态异常
                    if (kataGoEnabled && GameState.kataGoEngine?.isReady != true) {
                        showPopupMessage("\u23F3 KataGo \u521D\u59CB\u5316\u4E2D\uFF0C\u8BF7\u7A0D\u5019...")
                        return@setOnClickListener
                    }
                    val kg = GameState.kataGoEngine
                    // V6.1: 模型加载中禁止切换
                    if (kg != null && kg.boardLoading) {
                        showPopupMessage("\u23F3 \u6A21\u578B\u52A0\u8F7D\u4E2D\uFF0C\u8BF7\u7A0D\u5019...")
                        return@setOnClickListener
                    }
                    val newSize = diffSizes[i]
                    game.setBoardSize(newSize)
                    prefs.edit().putInt("board_size", newSize).apply()
                    // V10.0: 重建副屏Activity确保棋盘格同步
                    goView.invalidate(); launchWhiteScreen()
                    // V10.1: 棋盘切换时重置副屏让子UI
                    gamePresentation?.resetHandicapForBoardChange()
                    updateStatusDisplay(); updateCapturesDisplay()
                    // ★ V5.5: 异步预加载 KataGo 模型
                    if (kg != null && kg.isReady && kg.currentBoardSize != newSize) {
                        goView.message = "\u23F3 \u6B63\u5728\u5207\u6362\u68CB\u76D8\u6A21\u578B..."
                        goView.invalidate()
                        kg.loadBoardSize(newSize,
                            onProgress = { msg -> runOnUiThread { goView.message = msg; goView.invalidate() } },
                            onDone = { runOnUiThread { goView.message = "\u2705 ${newSize}\u8DEF\u68CB\u76D8\u5C31\u7EEA"; goView.invalidate() } }
                        )
                    }
                    // 刷新设置面板
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                    showSettingsDialog()
                }
            }
            diffRow.addView(db, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i < diffs.size - 1) rightMargin = 8
            })
        }
        card.addView(diffRow)

        // ★ V5.6: 背景音乐开关
        val mRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(16, 12, 16, 12) }
        val mLabel = TextView(this).apply { text = "\uD83C\uDFB5 \u80CC\u666F\u97F3\u4E50:"; textSize = 15f; setTextColor(Color.parseColor("#4A3728")) }
        mRow.addView(mLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        val mSw = Switch(this).apply { isChecked = BgMusic.isEnabled() }
        mRow.addView(mSw)
        mSw.setOnCheckedChangeListener { _, on ->
            BgMusic.setEnabled(this@MainActivity, on)
            prefs.edit().putBoolean("bg_music", on).apply()
        }
        card.addView(mRow)

        // ★ V5.6: 背景音乐音量滑块
        val volRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(16, 8, 16, 12) }
        val volLabel = TextView(this).apply { text = "🔊 音量:"; textSize = 13f; setTextColor(Color.parseColor("#4A3728")); setPadding(0, 0, 8, 0) }
        volRow.addView(volLabel)
        val volSeek = SeekBar(this).apply { 
            max = 100; progress = (BgMusic.volume * 100).toInt()
            setPadding(0, 0, 8, 0)
        }
        val volText = TextView(this).apply { 
            text = "${volSeek.progress}"; textSize = 12f; setTextColor(Color.parseColor("#6D4C41"))
            gravity = Gravity.CENTER; minWidth = 40
        }
        volRow.addView(volSeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        volRow.addView(volText)
        volSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                BgMusic.updateVolume(v / 100f)
                volText.text = "$v"
                prefs.edit().putFloat("music_volume", v / 100f).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        card.addView(volRow)

        // ★ V6.1: KataGo AI 开关
        val kRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(16, 12, 16, 12) }
        val kLabel = TextView(this).apply { text = "🤖 KataGo AI:"; textSize = 15f; setTextColor(Color.parseColor("#4A3728")) }
        kRow.addView(kLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        val kSw = Switch(this).apply { isChecked = kataGoEnabled }
        kRow.addView(kSw)
        kSw.setOnCheckedChangeListener { _, on ->
            // V6.1: 仅游戏未开始时可切换
            if (game.isActive) {
                showPopupMessage("⚠️ 请先结束当前对局再切换 KataGo")
                kSw.isChecked = !on
                return@setOnCheckedChangeListener
            }
            kataGoEnabled = on
            prefs.edit().putBoolean("katago_enabled", on).apply()
            if (on) {
                // 开启 KataGo：如果引擎未初始化则启动
                if (GameState.kataGoEngine?.isReady != true) {
                    startKataGoEngine()
                }
            } else {
                // 关闭 KataGo：停止引擎 + 关闭自动落子
                GameState.kataGoEngine?.shutdown()
                GameState.kataGoEngine = null
                GameState.useKataGo = false
                autoPlayEnabled = false; autoPlayWhite = false
                goView.autoPlayBlock = false
            }
        }
        card.addView(kRow)

        // V8.6: 保存录像开关
        val recRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(16, 12, 16, 12) }
        val recLabel = TextView(this).apply { text = "\uD83C\uDFAC \u4FDD\u5B58\u5F55\u50CF:"; textSize = 15f; setTextColor(Color.parseColor("#4A3728")) }
        recRow.addView(recLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        val recSw = Switch(this).apply { isChecked = recordingEnabled }
        recRow.addView(recSw)
        val recTxt = TextView(this).apply {
            text = if (recordingEnabled) "\u5F00" else "\u5173"
            textSize = 13f; setTextColor(Color.parseColor("#6D4C41"))
            gravity = Gravity.CENTER_VERTICAL; setPadding(10, 0, 0, 0)
        }
        recRow.addView(recTxt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        recSw.setOnCheckedChangeListener { _, on ->
            recordingEnabled = on
            prefs.edit().putBoolean("recording_enabled", on).apply()
            recTxt.text = if (on) "\u5F00" else "\u5173"
            if (on && RecordingManager.currentRecording == null && game.isActive && !game.isGameOver) {
                RecordingManager.startRecording(game.boardSize, game.handicapStones)
            } else if (!on) {
                RecordingManager.cancelRecording()
            }
        }
        card.addView(recRow)

        // V8.6: Load录像按钮
        val loadBtn = Button(this).apply {
            text = "\uD83D\uDCC2 Load\u5F55\u50CF"; setTextColor(Color.WHITE); textSize = 14f
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 12f; setColor(Color.parseColor("#607D8B")) }
            setPadding(24, 10, 24, 10)
            isAllCaps = false
            setOnClickListener {
                if (game.isActive && !game.isGameOver) {
                    showPopupMessage("\u8BF7\u5148\u7ED3\u675F\u5F53\u524D\u5BF9\u5C40\u540E\u52A0\u8F7D\u5F55\u50CF")
                    return@setOnClickListener
                }
                if (isReplaying) {
                    showPopupMessage("\u5F55\u50CF\u64AD\u653E\u4E2D\uFF0C\u8BF7\u5148\u9000\u51FA")
                    return@setOnClickListener
                }
                showLoadRecordingDialog()
            }
        }
        card.addView(loadBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8; bottomMargin = 4 })

        // V8.8: 复盘检查按钮
        val reviewBtn = Button(this).apply {
            text = "\uD83D\uDD0D \u590D\u76D8\u68C0\u67E5"; setTextColor(Color.WHITE); textSize = 14f
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 12f; setColor(Color.parseColor("#8D6E63")) }
            setPadding(24, 10, 24, 10)
            isAllCaps = false
            setOnClickListener {
                if (isReplaying) {
                    showPopupMessage("\u5F55\u50CF\u64AD\u653E\u4E2D\uFF0C\u8BF7\u5148\u9000\u51FA")
                    return@setOnClickListener
                }
                showValidateRecordingDialog()
            }
        }
        card.addView(reviewBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 })

        // 关闭按钮
        val closeBtn = Button(this).apply {
            text = "\u5173\u95ED"; setTextColor(Color.WHITE); textSize = 14f
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 16f; setColor(Color.parseColor("#8D6E63")) }
            setPadding(32, 10, 32, 10); setOnClickListener { (overlay.parent as? ViewGroup)?.removeView(overlay) }
        }
        card.addView(closeBtn)
        overlay.addView(card, FrameLayout.LayoutParams(
            (root.width * 0.6).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        overlay.setOnClickListener { (overlay.parent as? ViewGroup)?.removeView(overlay) }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // V8.6: 录像回放功能
    private fun showLoadRecordingDialog() {
        // V9.1: KataGo 初始化/切换棋盘期间禁止加载录像
        val kg = GameState.kataGoEngine
        if (kg != null && (!kg.isReady || kg.boardLoading)) {
            showPopupMessage("\u23F3 KataGo \u521D\u59CB\u5316\u4E2D\uFF0C\u8BF7\u7A0D\u5019...")
            return
        }
        val files = RecordingManager.listRecordings(this)
        if (files.isEmpty()) {
            showPopupMessage("\u6CA1\u6709\u4FDD\u5B58\u7684\u5F55\u50CF")
            return
        }
        val names = files.map { RecordingManager.getDisplayName(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("\uD83D\uDCC2 \u9009\u62E9\u5F55\u50CF")
            .setItems(names) { _, which -> (findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)?.parent as? ViewGroup)?.let { parent ->
                // 关闭设置对话框
                for (i in parent.childCount - 1 downTo 0) {
                    if (parent.getChildAt(i).tag == "settings_dlg") parent.removeViewAt(i)
                }
            }; startReplay(files[which]) }
            // V8.9: 删除全部录像按钮
            .setNeutralButton("\uD83D\uDDD1 \u5220\u9664\u5168\u90E8") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("\u786E\u8BA4\u5220\u9664")
                    .setMessage("\u786E\u5B9A\u5220\u9664\u6240\u6709 ${files.size} \u4E2A\u5F55\u50CF\uFF1F\u6B64\u64CD\u4F5C\u4E0D\u53EF\u64A4\u9500\u3002")
                    .setPositiveButton("\u5220\u9664") { _, _ ->
                        for (f in files) RecordingManager.deleteRecording(f)
                        showPopupMessage("\u5DF2\u5220\u9664 ${files.size} \u4E2A\u5F55\u50CF")
                    }
                    .setNegativeButton("\u53D6\u6D88", null)
                    .show()
            }
            .setNegativeButton("\u53D6\u6D88", null)
            .show()
    }

    // V8.8: 复盘检查 — 选择录像并验证游戏逻辑
    private fun showValidateRecordingDialog() {
        // V9.1: KataGo 初始化/切换棋盘期间禁止复盘检查
        val kg = GameState.kataGoEngine
        if (kg != null && (!kg.isReady || kg.boardLoading)) {
            showPopupMessage("\u23F3 KataGo \u521D\u59CB\u5316\u4E2D\uFF0C\u8BF7\u7A0D\u5019...")
            return
        }
        val files = RecordingManager.listRecordings(this)
        if (files.isEmpty()) {
            showPopupMessage("\u6CA1\u6709\u4FDD\u5B58\u7684\u5F55\u50CF")
            return
        }
        val names = files.map { RecordingManager.getDisplayName(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("\uD83D\uDD0D \u9009\u62E9\u8981\u68C0\u67E5\u7684\u5F55\u50CF")
            .setItems(names) { _, which -> doValidateRecording(files[which]) }
            .setNegativeButton("\u53D6\u6D88", null)
            .show()
    }

    private fun doValidateRecording(file: File) {
        val data = RecordingManager.loadRecording(file) ?: run {
            showPopupMessage("\u5F55\u50CF\u6587\u4EF6\u635F\u574F\uFF0C\u65E0\u6CD5\u52A0\u8F7D")
            return
        }
        goView.message = "\uD83D\uDD0D \u6B63\u5728\u590D\u76D8\u68C0\u67E5..."; goView.invalidate()
        // 在后台线程执行验证
        Thread {
            val issues = RecordingManager.validateRecording(data)
            runOnUiThread {
                goView.clearMessage(); goView.invalidate()
                if (issues.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("\u2705 \u590D\u76D8\u7ED3\u679C")
                        .setMessage("\u5F55\u50CF\u903B\u8F91\u6B63\u786E\uFF0C\u672A\u53D1\u73B0\u95EE\u9898\u3002\n\u68CB\u76D8: ${data.boardSize}\u00D7${data.boardSize}\n\u603B\u624B\u6570: ${data.moves.size}")
                        .setPositiveButton("\u786E\u5B9A", null)
                        .show()
                } else {
                    val msg = buildString {
                        append("\u53D1\u73B0 ${issues.size} \u4E2A\u903B\u8F91\u95EE\u9898:\n\n")
                        for ((i, issue) in issues.withIndex()) {
                            append("${i + 1}. $issue\n")
                        }
                    }
                    AlertDialog.Builder(this)
                        .setTitle("\u26A0\uFE0F \u590D\u76D8\u7ED3\u679C")
                        .setMessage(msg)
                        .setPositiveButton("\u786E\u5B9A", null)
                        .show()
                }
            }
        }.start()
    }

    private fun startReplay(file: File) {
        val data = RecordingManager.loadRecording(file) ?: run {
            showPopupMessage("\u5F55\u50CF\u6587\u4EF6\u635F\u574F\uFF0C\u65E0\u6CD5\u52A0\u8F7D")
            return
        }
        if (data.moves.isEmpty()) {
            showPopupMessage("\u5F55\u50CF\u4E3A\u7A7A")
            return
        }
        // V9.2: 保存原始棋盘大小，回放结束后恢复
        replayOrigBoardSize = game.boardSize
        // 设置棋盘大小（不加载KataGo模型，快速切换）
        if (data.boardSize != game.boardSize) {
            game.setBoardSize(data.boardSize)
            prefs.edit().putInt("board_size", data.boardSize).apply()
            goView.invalidate(); gamePresentation?.refreshView()
        }
        // 开始对局（如果未开始）
        if (!game.isActive || game.isGameOver) {
            if (data.handicap > 0) game.setHandicap(data.handicap)
            game.startGame()
            // V9.3: 回放期间不操作KataGo（避免boardsize GTP导致卡顿）
        }

        isReplaying = true
        replayData = data
        replayIndex = 0
        replayPlaying = true

        // 阻止触摸操作
        goView.replayMode = true
        gamePresentation?.goView?.replayMode = true

        // 禁用所有按钮
        disableButtonsForReplay()

        // 添加回放控制栏
        addReplayControls()

        updateStatusDisplay()
        updateCapturesDisplay()
        gamePresentation?.updateStatusText()

        // 开始自动播放
        startReplayAutoPlay()
    }

    private fun stopReplay() {
        replayRunnable?.let { replayHandler.removeCallbacks(it) }
        replayRunnable = null
        replayPlaying = false
        isReplaying = false
        replayData = null
        replayIndex = 0

        // V8.9: 停止所有计时器，防止回放结束后持续提醒
        stopRemindTimers()

        // 恢复触摸
        goView.replayMode = false
        gamePresentation?.goView?.replayMode = false

        // 移除回放控制栏
        removeReplayControls()

        // 恢复按钮
        enableButtonsAfterReplay()

        // V8.7: 恢复棋盘到程序刚启动状态
        game.restart()
        GameState.initialSyncDone = false  // V10.2: 回放结束重置同步标志
        // V9.3: 回放结束后恢复原始棋盘大小并重新对齐KataGo
        if (replayOrigBoardSize != game.boardSize) {
            game.setBoardSize(replayOrigBoardSize)
            prefs.edit().putInt("board_size", replayOrigBoardSize).apply()
            goView.invalidate(); gamePresentation?.refreshView()
            // 重置KataGo到原始棋盘（boardsize GTP可能较慢但仅此一次）
            val kg = GameState.kataGoEngine
            if (kg != null && kg.isReady) {
                goView.message = "\u23F3 \u5207\u6362\u56DE ${replayOrigBoardSize}\u8DEF\u6A21\u578B..."
                goView.invalidate()
                kg.loadBoardSize(replayOrigBoardSize,
                    onProgress = { msg -> runOnUiThread { goView.message = msg; goView.invalidate() } },
                    onDone = { runOnUiThread { goView.clearMessage(); goView.invalidate() } }
                )
            }
        }
        goView.clearPreview(); goView.clearHint(); goView.clearTerritory(); goView.clearAnalysis()
        goView.clearMessage(); goView.invalidate()
        gamePresentation?.clearHint(); gamePresentation?.clearAnalysis()
        gamePresentation?.clearMessage(); gamePresentation?.refreshView()
        updateButtonState(); gamePresentation?.updateButtonState()
        updateStatusDisplay()
        updateCapturesDisplay()
        gamePresentation?.updateStatusText()
    }

    private fun addReplayControls() {
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return
        removeReplayControls()

        val screenW = resources.displayMetrics.widthPixels
        val barW = (screenW * 0.5).toInt()  // V9.0: 50% 屏宽更紧凑

        val bar = LinearLayout(this).apply {
            tag = "replay_bar"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 6)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 12f
                setColor(Color.parseColor("#F0EBE0")); setStroke(1, Color.parseColor("#C8B898"))
            }
            alpha = 0f; animate().alpha(1f).setDuration(300).start()
        }

        val totalMoves = replayData?.moves?.size ?: 1
        replayProgressBar = SeekBar(this).apply {
            max = totalMoves; progress = 0; setPadding(2, 0, 2, 0)
            // V9.0: 缩小进度条高度
            minimumHeight = 8; thumb = null
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) seekReplayTo(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    replayPlaying = false
                    replayRunnable?.let { replayHandler.removeCallbacks(it) }
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    replayPlaying = true
                    startReplayAutoPlay()
                }
            })
        }
        bar.addView(replayProgressBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        replayMoveText = TextView(this).apply {
            text = "0/$totalMoves"; textSize = 10f
            setTextColor(Color.parseColor("#5D4037")); gravity = Gravity.CENTER
            setPadding(4, 0, 4, 0); minWidth = 42
        }
        bar.addView(replayMoveText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val btnSize = 52  // V9.0: 更小按钮
        replayPlayBtn = Button(this).apply {
            text = "\u23F8"; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(10, 4, 10, 4)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setSize(btnSize, btnSize)
                setColor(Color.parseColor("#546E7A")); setStroke(2, Color.parseColor("#90A4AE"))
            }
            setOnClickListener { toggleReplayPlayPause() }
        }
        bar.addView(replayPlayBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply { leftMargin = 6; rightMargin = 4 })

        replayExitBtn = Button(this).apply {
            text = "\u2715"; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(10, 4, 10, 4)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setSize(btnSize, btnSize)
                setColor(Color.parseColor("#C62828")); setStroke(2, Color.parseColor("#EF5350"))
            }
            setOnClickListener { stopReplay() }
        }
        bar.addView(replayExitBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply { leftMargin = 4 })

        // V9.0: 更往下移，不遮挡棋盘
        val params = FrameLayout.LayoutParams(barW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 28
        }
        root.addView(bar, params)
    }

    private fun removeReplayControls() {
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return
        for (i in root.childCount - 1 downTo 0) {
            if (root.getChildAt(i).tag == "replay_bar") root.removeViewAt(i)
        }
    }

    private fun startReplayAutoPlay() {
        replayRunnable?.let { replayHandler.removeCallbacks(it) }
        replayRunnable = object : Runnable {
            override fun run() {
                if (!isReplaying || !replayPlaying) return
                replayStep()
                if (replayIndex < (replayData?.moves?.size ?: 0)) {
                    replayHandler.postDelayed(this, 800)  // 每步800ms
                } else {
                    replayPlaying = false
                    replayPlayBtn?.text = "\u25B6"
                }
            }
        }
        replayHandler.postDelayed(replayRunnable!!, 500)
    }

    private fun replayStep() {
        val data = replayData ?: return
        if (replayIndex >= data.moves.size) return
        val move = data.moves[replayIndex]
        // V9.6: 悔棋标记 → 实际执行回滚
        if (move.isUndo) {
            game.undo(move.player)
            goView.invalidate(); gamePresentation?.refreshView()
            replayIndex++
            replayProgressBar?.progress = replayIndex
            replayMoveText?.text = "${replayIndex}/${data.moves.size}"
            updateStatusDisplay(); updateCapturesDisplay(); gamePresentation?.updateStatusText()
            return
        }
        if (move.row >= 0) {
            val r = game.placePiece(move.row, move.col, move.player)
            // V9.0: 回放也播放落子音效
            if (r.captures > 0) SoundFX.playCaptureSound() else SoundFX.playStoneSound()
            goView.clearPreview(); goView.clearHint(); goView.clearTerritory(); goView.clearAnalysis(); goView.invalidate()
            gamePresentation?.clearHint(); gamePresentation?.clearAnalysis(); gamePresentation?.refreshView()
        } else {
            game.pass(move.player)
            SoundFX.playStoneSound()  // V9.0: 虚手也有音效
        }
        replayIndex++
        replayProgressBar?.progress = replayIndex
        // V8.7: 更新步数文本
        replayMoveText?.text = "${replayIndex}/${data.moves.size}"
        updateStatusDisplay()
        updateCapturesDisplay()
        gamePresentation?.updateStatusText()
        if (replayIndex >= data.moves.size) {
            replayPlaying = false
            replayPlayBtn?.text = "\u25B6"
            // V8.7: 播放完后1.5秒自动恢复到初始状态
            replayHandler.postDelayed({ stopReplay() }, 1500)
        }
    }

    private fun seekReplayTo(targetIndex: Int) {
        val data = replayData ?: return
        if (targetIndex < 0 || targetIndex > data.moves.size) return
        // 重新开始对局并快进到目标位置
        if (data.handicap > 0) game.setHandicap(data.handicap)
        game.startGame()
        replayIndex = 0
        for (i in 0 until targetIndex) {
            val move = data.moves[i]
            if (move.isUndo) {
                // V9.6: 悔棋标记 → 回滚
                game.undo(move.player)
            } else if (move.row >= 0) {
                game.placePiece(move.row, move.col, move.player)
            } else {
                game.pass(move.player)
            }
            replayIndex++
        }
        replayProgressBar?.progress = replayIndex
        goView.clearPreview(); goView.clearHint(); goView.clearTerritory(); goView.clearAnalysis(); goView.invalidate()
        gamePresentation?.clearHint(); gamePresentation?.clearAnalysis(); gamePresentation?.refreshView()
        updateStatusDisplay()
        updateCapturesDisplay()
        gamePresentation?.updateStatusText()
    }

    private fun toggleReplayPlayPause() {
        if (!isReplaying) return
        replayPlaying = !replayPlaying
        if (replayPlaying) {
            replayPlayBtn?.text = "\u23F8"
            startReplayAutoPlay()
        } else {
            replayPlayBtn?.text = "\u25B6"
            replayRunnable?.let { replayHandler.removeCallbacks(it) }
        }
    }

    private fun disableButtonsForReplay() {
        // V8.8: 回放期间禁用所有操作按钮（仅回放退出按钮可用）
        for (v in replaySensitiveViews) {
            v.isEnabled = false
            v.alpha = 0.4f
        }
        // V9.1: 副屏也禁用
        gamePresentation?.disableButtonsForReplay()
    }

    private fun enableButtonsAfterReplay() {
        // V8.8: 恢复所有按钮
        for (v in replaySensitiveViews) {
            v.isEnabled = true
            v.alpha = 1.0f
        }
        // V9.1: 副屏恢复
        gamePresentation?.enableButtonsAfterReplay()
    }

    // V10.0: 双Activity架构 — Home键直接退出（双屏应用不适合后台）
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        finishAffinity()
    }
    // V10.1: 返回键 — 对局中需对方同意才能退出
    override fun onBackPressed() {
        if (isReplaying) { stopReplay(); return }
        requestExit()
    }
    override fun onRestart() { super.onRestart(); if (gamePresentation == null) launchWhiteScreen() }
    // V6.5: 后台暂停 KataGo 搜索释放GPU
    override fun onPause() {
        super.onPause()
        GameState.kataGoEngine?.stopSearch()
    }
    override fun onResume() {
        super.onResume()
        GameState.kataGoEngine?.stopSearch()  // 恢复时也确保搜索已停止
    }
    override fun onDestroy() {
        // V8.9: 仅当对局已开始时保存录像（兜底）
        if (recordingEnabled && game.isActive && RecordingManager.currentRecording != null) {
            RecordingManager.finishRecording(this)
        }
        if (::goView.isInitialized) { stopRemindTimers(); animator?.cancel() }
        SoundFX.release()
        BgMusic.stop()
        // V10.0: 双Activity架构 — finish副屏Activity
        try { gamePresentation?.finish() } catch (_: Exception) {}
        gamePresentation = null
        GameState.kataGoEngine?.shutdown()
        GameState.kataGoEngine = null
        super.onDestroy()
    }
}
