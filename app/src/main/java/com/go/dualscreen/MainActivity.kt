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
        if (prefs.getBoolean("bg_music", true)) {
            BgMusic.setEnabled(this, true)
        }
        // V6.1: KataGo开关状态（默认开启）
        kataGoEnabled = prefs.getBoolean("katago_enabled", true)
        initMainUI()
        goView.showPieceOrder = prefs.getBoolean("piece_order", false)
        launchWhiteScreen()

        // ★ V6.1: 初始化 KataGo 引擎（后台加载模型，不阻塞 UI）
        if (kataGoEnabled) startKataGoEngine()
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
            if (!game.isActive || game.isGameOver) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u672A\u5F00\u59CB"); return@setOnClickListener }
            if (game.isPaused) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u6682\u505C\u4E2D\uFF0C\u8BF7\u5148\u6062\u590D"); return@setOnClickListener }
            if (game.currentPlayer != myPlayer) { showMsgToPlayer(myPlayer, "\u8BF7\u7B49\u5F85\u5BF9\u65B9\u64CD\u4F5C"); return@setOnClickListener }
            handlePass(myPlayer)
        }
        leftPanel.addView(passBtn, LinearLayout.LayoutParams(smallSize, smallSize).apply { bottomMargin = 8 })

        // 提示按钮（黑方在虚手下方）
        val hintBtn = create3DButton("\uD83D\uDCA1\nAI\u63D0\u793A", "#607888", "#405868", smallSize)
        hintBtn.setOnClickListener {
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
            if (!game.isActive || game.isGameOver) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u672A\u5F00\u59CB"); return@setOnClickListener }
            if (game.isPaused) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u6682\u505C\u4E2D"); return@setOnClickListener }
            handleScoring()
        }
        leftPanel.addView(scoreBtn, LinearLayout.LayoutParams(smallSize, smallSize).apply { bottomMargin = 8 })

        val pauseBtn = create3DButton("\u23F8\n\u6682\u505C", "#988878", "#786858", smallSize)
        pauseBtnBlack = pauseBtn
        var pauseBtnRef: Button? = null; pauseBtnRef = pauseBtn
        pauseBtn.setOnClickListener {
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

        // 版本信息（左下角）
        val verLabel = TextView(this).apply {
            text = "KataGo围棋双屏\nV8.5"; textSize = 9f
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
                if (game.isPaused && game.pausedByPlayer != myPlayer) { showMsgToPlayer(myPlayer, "\u6E38\u620F\u6682\u505C\u4E2D\uFF0C\u8BF7\u7B49\u5F85\u5BF9\u65B9\u6062\u590D"); return@setOnClickListener }
                showSettingsDialog()
            }
        }
        panel.addView(settingsBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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

    private fun launchWhiteScreen() {
        try { gamePresentation?.finish() } catch (_: Exception) {}
        gamePresentation = null
        val myDisplayId = display?.displayId ?: 0
        for (display in displayManager.displays) {
            if (display.displayId != myDisplayId && display.isValid) {
                GamePresentation.sharedPerspective = GoGame.PLAYER_WHITE
                val otherPerspective = GoGame.PLAYER_WHITE
                GamePresentation.sharedGame = game
                val intent = Intent(this, GamePresentation::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    try {
                        val options = android.app.ActivityOptions.makeBasic()
                        val method = options.javaClass.getMethod("setLaunchDisplayId", Int::class.javaPrimitiveType)
                        method.invoke(options, display.displayId)
                        startActivity(intent, options.toBundle())
                    } catch (_: Exception) { startActivity(intent) }
                } else { startActivity(intent) }
                handler.postDelayed({
                    GamePresentation.instance?.let { pres ->
                        pres.onPiecePlaced = { r, c -> runOnUiThread { handlePiecePlaced(r, c) } }
                        pres.onPassRequest = { runOnUiThread { handlePass(GoGame.PLAYER_WHITE) } }
                        pres.onStartOrRestart = { runOnUiThread { onStartOrRestart(otherPerspective) } }
                        pres.onUndoRequest = { runOnUiThread { requestUndo(otherPerspective) } }
                        pres.getMainActivity = { this@MainActivity }
                        gamePresentation = pres
                        updateCapturesDisplay()
                        updateStatusDisplay()
                    }
                }, 600)
                return
            }
        }
    }

    private fun onStartOrRestart(player: Int) {
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

    /** 重置 KataGo 棋盘 */
    /** V5.1: 同步重置 KataGo 棋盘基础设置（快速，3条命令~150ms） */
    private fun resetKataGoBoard() {
        val kg = GameState.kataGoEngine ?: return
        if (!kg.isReady) return
        // 同步执行基础设置，确保任何后续操作前棋盘已就绪
        try {
            kg.setBoardSize(game.boardSize)
            kg.clearBoard()
            kg.setKomi(GoGame.KOMI.toFloat())
        } catch (e: Exception) {
            Log.e("KataGo", "Board reset failed: ${e.message}")
        }
    }

    /** V4.4: 异步回放所有历史落子，不阻塞 UI */
    private fun asyncSyncKataGoBoard() {
        val kg = GameState.kataGoEngine ?: return
        if (!kg.isReady) return
        Thread {
            try {
                kg.setBoardSize(game.boardSize)
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
            } catch (e: Exception) {
                Log.e("KataGo", "Board sync failed: ${e.message}")
            }
        }.start()
    }

    private fun onGameStarted() {
        goView.clearPreview(); goView.invalidate(); gamePresentation?.refreshView()
        updateButtonState(); updateStatusDisplay(); updateCapturesDisplay()
        gamePresentation?.updateButtonState(); startRemindTimers()
        resetKataGoBoard()
        // V8.5: 让子后白方先行，提示音对应
        val voiceRes = if (game.currentPlayer == GoGame.PLAYER_BLACK) R.raw.your_turn_black else R.raw.your_turn_white
        SoundFX.playVoice(this, voiceRes)
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
        gamePresentation?.updateButtonState(); startRemindTimers()
        resetKataGoBoard()  // ★ 重置 KataGo 棋盘
        val voiceRes = if (game.currentPlayer == GoGame.PLAYER_BLACK) R.raw.your_turn_black else R.raw.your_turn_white
        SoundFX.playVoice(this, voiceRes)
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
    internal fun exitApp() {
        stopRemindTimers(); animator?.cancel(); animator = null
        countdownRunnable?.let { handler.removeCallbacks(it) }; countdownRunnable = null
        goView.clearAllAnimations()
        try { gamePresentation?.clearAllAnimations() } catch (_: Exception) {}
        try { gamePresentation?.finish() } catch (_: Exception) {}
        gamePresentation = null; GamePresentation.instance = null
        BgMusic.stop()
        GameState.kataGoEngine?.shutdown()
        GameState.kataGoEngine = null
        handler.postDelayed({ finishAffinity() }, 150)
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
        val r = game.pass(player)
        if (!r.success) { showMsg(r.message); return }
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
        } else startRemindTimers()
    }

    private fun handlePiecePlaced(row: Int, col: Int, isAutoPlay: Boolean = false) {
        val r = game.placePiece(row, col, game.currentPlayer)
        if (!r.success) { showMsg(r.message); return }
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
        gamePresentation?.go?.autoPlayBlock = true
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
                    gamePresentation?.go?.autoPlayBlock = false
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
                    gamePresentation?.go?.autoPlayBlock = false
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
    private fun computeAiAsync(player: Int, onDone: (Triple<Int,Int,String>?) -> Unit) {
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
        aiThinking = true
        val msg = "\uD83E\uDD16 KataGo \u6DF1\u5EA6\u601D\u8003\u4E2D..."
        if (player == myPlayer) { goView.message = msg; goView.invalidate() }
        else gamePresentation?.showMessage(msg)
        // ★ V4.4: 10 秒超时保护，防止死锁
        aiTimeoutRunnable = Runnable {
            aiThinking = false
            Log.w("KataGo", "AI thinking timeout! Resetting aiThinking flag")
            handler.post {
                if (player == myPlayer) { goView.message = "⚠️ AI 超时，请重试"; goView.invalidate() }
                else gamePresentation?.showMessage("⚠️ AI 超时")
                onDone(null)
            }
        }
        handler.postDelayed(aiTimeoutRunnable!!, 10000)
        Thread {
            val result = try { game.suggestMove(player) } catch (_: Exception) { null }
            handler.post {
                aiTimeoutRunnable?.let { handler.removeCallbacks(it) }
                aiTimeoutRunnable = null
                aiThinking = false
                onDone(result)
            }
        }.start()
    }

    /** V6.5: 统一使用 kata-analyze 深度分析 */
    private fun handleHint() {
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
                handlePass(player)
            }
        }
    }

    private fun handleScoring() {
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
            // 自动落子模式：3秒延迟后自动落子
            countdownText.text = "\uD83E\uDD16 3s..."
            autoPlayRunnable = object : Runnable {
                var sec = 3
                override fun run() {
                    if (!game.isActive || game.isGameOver || game.isPaused) { autoPlayRunnable = null; return }
                    if (game.currentPlayer != cur) { startRemindTimers(); return }
                    sec--
                    if (sec <= 0) {
                        countdownText.text = ""
                        autoPlayRunnable = null
                        executeAutoPlay(cur)
                        return
                    }
                    countdownText.text = "\uD83E\uDD16 ${sec}s..."
                    handler.postDelayed(this, 1000)
                }
            }
            handler.postDelayed(autoPlayRunnable!!, 1000)
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
                    goView.invalidate(); gamePresentation?.refreshView()
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

    // V8.5: 返回键需对方确认后才能退出
    override fun onBackPressed() { requestExit() }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); finishAffinity() }
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
        if (::goView.isInitialized) { stopRemindTimers(); animator?.cancel() }
        SoundFX.release()
        BgMusic.stop()
        try { gamePresentation?.finish() } catch (_: Exception) {}
        gamePresentation = null; GamePresentation.instance = null
        GameState.kataGoEngine?.shutdown()
        GameState.kataGoEngine = null
        super.onDestroy()
    }
}
