package com.go.dualscreen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class GamePresentation : Activity() {

    companion object {
        var instance: GamePresentation? = null
        lateinit var sharedGame: GoGame
        var sharedPerspective: Int = GoGame.PLAYER_WHITE
    }

    private val playerPerspective: Int get() = sharedPerspective
    private val game: GoGame get() = sharedGame

    private var goView: GoView? = null
    internal val go: GoView? get() = goView
    private var statusText: TextView? = null
    private var capturesText: TextView? = null
    private var countdownText: TextView? = null
    var onPiecePlaced: ((row: Int, col: Int) -> Unit)? = null
    var onPassRequest: (() -> Unit)? = null
    var onStartOrRestart: (() -> Unit)? = null
    var onUndoRequest: (() -> Unit)? = null
    var getMainActivity: (() -> MainActivity)? = null
    private var startBtn: Button? = null
    private var pauseBtnWhite: Button? = null

    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = if (isLandscape()) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#D4C4A8"))
        }
        goView = GoView(this).apply {
            this.playerPerspective = this@GamePresentation.playerPerspective
            this.game = this@GamePresentation.game
            this.showPieceOrder = getSharedPreferences("go_settings", Context.MODE_PRIVATE).getBoolean("piece_order", false)
            onConfirmPlace = { row, col -> this@GamePresentation.onPiecePlaced?.invoke(row, col) }
        }

        val smallSize = (resources.displayMetrics.density * 60).toInt()
        val leftPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(12, 12, 4, 12)
        }
        val logoImg = ImageView(this).apply {
            setImageResource(R.drawable.kemi_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { goView?.startFlowerAnimation(); SoundFX.playCheerfulSound() }
        }
        leftPanel.addView(logoImg, LinearLayout.LayoutParams(smallSize, smallSize).apply { bottomMargin = 12 })

        // 催促按钮
        val hurryBtn = create3DButton("\u23F0\n\u50AC", "#FF9800", "#E65100", smallSize)
        hurryBtn.setOnClickListener {
            if (!game.isActive || game.isGameOver) { showPopupMessage("\u6E38\u620F\u672A\u5F00\u59CB"); return@setOnClickListener }
            if (game.isPaused && game.pausedByPlayer != playerPerspective) { showPopupMessage("\u6E38\u620F\u6682\u505C\u4E2D\uFF0C\u8BF7\u7B49\u5F85\u5BF9\u65B9\u6062\u590D"); return@setOnClickListener }
            if (game.currentPlayer == playerPerspective) { showPopupMessage("\u8F6E\u5230\u4F60\u4E86\uFF0C\u4E0D\u80FD\u50AC\u81EA\u5DF1"); return@setOnClickListener }
            val rid = if (playerPerspective == GoGame.PLAYER_BLACK) R.raw.hurry_black else R.raw.hurry_white
            SoundFX.playVoice(this, rid)
            getMainActivity?.invoke()?.shakeMainForWhite()
        }
        leftPanel.addView(hurryBtn, LinearLayout.LayoutParams(smallSize, smallSize).apply { bottomMargin = 8 })

        // Pass 虚手按钮
        val passBtn = create3DButton("\u270B\n\u865A\u624B", "#A08060", "#806040", smallSize)
        passBtn.setOnClickListener {
            if (!game.isActive || game.isGameOver) { showPopupMessage("\u6E38\u620F\u672A\u5F00\u59CB"); return@setOnClickListener }
            if (game.isPaused) { showPopupMessage("\u6E38\u620F\u6682\u505C\u4E2D\uFF0C\u8BF7\u5148\u6062\u590D"); return@setOnClickListener }
            if (game.currentPlayer != playerPerspective) { showPopupMessage("\u8BF7\u7B49\u5F85\u5BF9\u65B9\u64CD\u4F5C"); return@setOnClickListener }
            onPassRequest?.invoke()
        }
        leftPanel.addView(passBtn, LinearLayout.LayoutParams(smallSize, smallSize).apply { bottomMargin = 8 })

        // 提示按钮（通过 MainActivity 后台计算，防 ANR）
        val hintBtn = create3DButton("\uD83D\uDCA1\nAI\u63D0\u793A", "#607888", "#405868", smallSize)
        hintBtn.setOnClickListener {
            if (!game.isActive || game.isGameOver) { showPopupMessage("游戏未开始"); return@setOnClickListener }
            if (game.isPaused) { showPopupMessage("游戏暂停中"); return@setOnClickListener }
            if (getMainActivity?.invoke()?.autoPlayWhite == true) { showPopupMessage("🤖 AI自动进行中，无需提示"); return@setOnClickListener }
            if (game.currentPlayer != playerPerspective) { showPopupMessage("现在是对手的回合，不能使用提示"); return@setOnClickListener }
            // V6.1: KataGo 关闭检查
            if (getMainActivity?.invoke()?.kataGoEnabled != true) {
                showPopupMessage("⚠️ 请先在设置中开启 KataGo")
                return@setOnClickListener
            }
            // ★ V6.5: 统一使用 kata-analyze 深度分析
            getMainActivity?.invoke()?.runKataAnalyze(playerPerspective)
        }
        leftPanel.addView(hintBtn, LinearLayout.LayoutParams(smallSize, smallSize).apply { bottomMargin = 8 })

        val pauseBtn = create3DButton("\u23F8\n\u6682\u505C", "#988878", "#786858", smallSize)
        pauseBtnWhite = pauseBtn
        pauseBtn.setOnClickListener {
            if (!game.isActive || game.isGameOver) { showPopupMessage("\u6E38\u620F\u672A\u5F00\u59CB"); return@setOnClickListener }
            if (game.isPaused) {
                if (game.pausedByPlayer == playerPerspective) {
                    game.isPaused = false; game.pausedByPlayer = GoGame.EMPTY
                    updatePauseBtn(false)
                    getMainActivity?.invoke()?.updatePauseBtnForWhite(false)
                    updateStatusText()
                    getMainActivity?.invoke()?.updateStatusDisplay()
                    getMainActivity?.invoke()?.restartCountdown()
                } else { showPopupMessage("\u7B49\u5F85\u5BF9\u65B9\u6062\u590D\u6E38\u620F") }
            } else {
                val myPauseCount = if (playerPerspective == GoGame.PLAYER_BLACK) game.pauseCountBlack else game.pauseCountWhite
                if (myPauseCount <= 0) { showPopupMessage("\u6682\u505C\u6B21\u6570\u5DF2\u7528\u5B8C"); return@setOnClickListener }
                if (playerPerspective == GoGame.PLAYER_BLACK) game.pauseCountBlack-- else game.pauseCountWhite--
                game.isPaused = true; game.pausedByPlayer = playerPerspective
                updatePauseBtn(true)
                getMainActivity?.invoke()?.updatePauseBtnForWhite(true)
                updateStatusText()
                getMainActivity?.invoke()?.updateStatusDisplay()
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
        val autoSwGP = Switch(this).apply {
            isChecked = false
            setOnCheckedChangeListener { _, on ->
                if (on && getMainActivity?.invoke()?.kataGoEnabled != true) {
                    showPopupMessage("⚠️ 请先在设置中开启 KataGo")
                    isChecked = false
                    return@setOnCheckedChangeListener
                }
                goView?.autoPlayBlock = on
                getMainActivity?.invoke()?.setAutoPlayWhite(on)
                // 如果正在白方回合，立即触发自动落子
                if (on) getMainActivity?.invoke()?.triggerAutoPlayIfMyTurn()
            }
        }
        autoRow.addView(autoSwGP)
        leftPanel.addView(autoRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 4 })

        // ★ V8.5: 让子系统 (白方让黑方弱方, 2-9子, 左右滑动)
        // 注意: 滑动条和数值View必须在 Switch 之前声明, 因为 Switch 监听器引用它们
        val handiValText = TextView(this).apply {
            text = "2"; textSize = 11f; setTextColor(Color.parseColor("#6D4C41"))
            gravity = Gravity.CENTER; visibility = View.GONE; setPadding(6, 0, 6, 0)
        }
        val handiSeek = SeekBar(this).apply {
            max = 7; progress = 0; visibility = View.GONE  // 0→2子, 7→9子
        }
        handiSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                val n = v + 2
                handiValText.text = n.toString()
                game.setHandicap(n)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val handiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(4, 4, 4, 4)
        }
        handiRow.addView(TextView(this).apply {
            text = "\uD83C\uDFAF\u8BA9\u5B50"; textSize = 10f; setTextColor(Color.parseColor("#6D4C41"))
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = 4 })
        val handiSw = Switch(this).apply {
            isChecked = false
            setOnCheckedChangeListener { _, on ->
                if (game.isActive) { showPopupMessage("\u8BF7\u5148\u7ED3\u675F\u5F53\u524D\u5BF9\u5C40"); isChecked = false; return@setOnCheckedChangeListener }
                handiSeek.visibility = if (on) View.VISIBLE else View.GONE
                handiValText.visibility = if (on) View.VISIBLE else View.GONE
                if (on) {
                    val n = handiSeek.progress + 2
                    game.setHandicap(n); handiValText.text = n.toString()
                } else {
                    game.setHandicap(0)
                }
            }
        }
        handiRow.addView(handiSw)
        leftPanel.addView(handiRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 2 })
        // 让子数值行: 左右滑动条
        val handiSeekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(4, 0, 4, 2)
        }
        handiSeekRow.addView(handiSeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        handiSeekRow.addView(handiValText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = 4 })
        leftPanel.addView(handiSeekRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 4 })

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
            text = "\u26AA\u767D\u65B9\n\u7B49\u5F85\u5F00\u59CB..."; textSize = 13f
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
        startBtn!!.setOnClickListener { onStartOrRestart?.invoke() }
        val undoBtn = createPlaqueButton("\u21A9\n\u6094\u68CB", "#6B5540", "#A89078", btnSize)
        undoBtn.setOnClickListener { onUndoRequest?.invoke() }
        panel.addView(startBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply { bottomMargin = btnMargin; rightMargin = btnMargin2 })
        panel.addView(undoBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply { bottomMargin = 4; rightMargin = btnMargin2 })

        panel.addView(TextView(this).apply {
            text = ""; textSize = 10f; setTextColor(Color.parseColor("#998B7355")); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val panelLp = if (isLandscape()) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT) else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(panel, panelLp)
        setContentView(root)
    }

    private fun create3DButton(text: String, colorTop: String, colorBottom: String, size: Int): Button = Button(this).apply {
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

    /** V7.2: 古典匾额风格大按钮 */
    private fun createPlaqueButton(text: String, woodDark: String, woodLight: String, size: Int): Button {
        return Button(this).apply {
            this.text = text; textSize = 17f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#F5EDE0"))
            setShadowLayer(2f, 1f, 1f, Color.parseColor("#60000000"))
            setPadding(20, 12, 20, 12); setBackgroundColor(Color.TRANSPARENT)
            isAllCaps = false; typeface = android.graphics.Typeface.DEFAULT_BOLD
            val radius = size * 0.18f; val inset = (size * 0.08f).toInt()
            val frame = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = radius
                setSize(size, size)
                colors = intArrayOf(Color.parseColor(woodDark), Color.parseColor("#3A2010"))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            }
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

    fun showPopupMessage(msg: String) {
        val container = findViewById<FrameLayout>(android.R.id.content) ?: return
        for (i in container.childCount - 1 downTo 0) { if (container.getChildAt(i)?.tag == "popup_msg") container.removeViewAt(i) }
        val overlay = FrameLayout(this).apply { tag = "popup_msg" }
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
        overlay.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        overlay.setOnClickListener { card.animate().alpha(0f).setDuration(200).withEndAction { container.removeView(overlay) }.start() }
        container.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        goView?.postDelayed({
            card.animate().alpha(0f).setDuration(300).withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }.start()
        }, 2200)
    }

    fun setCapturesText(cap: String) { capturesText?.text = cap }
    fun updatePieceOrder(on: Boolean) { goView?.showPieceOrder = on; goView?.invalidate() }
    fun clearAllAnimations() = goView?.clearAllAnimations()
    fun startEggAnimation() = goView?.startEggAnimation()
    fun refreshView() = goView?.invalidate()
    fun clearPreview() = goView?.clearPreview()
    fun showMessage(msg: String) = goView?.showMessage(msg)
    fun clearMessage() = goView?.clearMessage()
    fun showHint(row: Int, col: Int) { goView?.showHint(row, col) }
    fun clearHint() { goView?.clearHint(); goView?.clearTerritory() }
    fun showTerritory(pts: Set<Pair<Int,Int>>) { goView?.showTerritory(pts) }
    fun showAnalysis(pts: List<GoView.AnalysisPoint>) { goView?.showAnalysis(pts) }
    fun clearAnalysis() { goView?.clearAnalysis() }
    fun startWinAnimation() = goView?.startWinAnimation()
    fun startLoseAnimation() = goView?.startLoseAnimation()
    fun updateAnimation(): Boolean = goView?.updateAnimation() ?: false

    fun updatePauseBtn(paused: Boolean) {
        pauseBtnWhite?.let {
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

    fun setCountdown(sec: Int) {
        countdownText?.text = when {
            sec == -2 -> "\u23F8"
            sec >= 0 -> "\u23F1${sec}s"
            else -> ""
        }
    }

    fun updateStatusText() {
        val g = game
        val pc = if (playerPerspective == GoGame.PLAYER_BLACK) "\u26AB\u9ED1\u65B9" else "\u26AA\u767D\u65B9"
        statusText?.text = if (!g.isActive) "$pc\n\u7B49\u5F85\u5F00\u59CB..."
        else if (g.isGameOver) {
            if (g.winner == playerPerspective) "\uD83C\uDFC6\u4F60\u8D62\u4E86\uFF01"
            else if (g.winner == GoGame.EMPTY) "\uD83E\uDD1D\u5E73\u5C40"
            else "\uD83D\uDE1E\u4F60\u8F93\u4E86"
        } else if (g.isPaused) {
            if (g.pausedByPlayer == playerPerspective) "$pc\n\u23F8 \u4F60\u6682\u505C\u4E86\u6E38\u620F"
            else "$pc\n\u23F8 \u5BF9\u65B9\u6682\u505C\u4E2D"
        } else {
            if (g.currentPlayer == playerPerspective) "$pc\n\uD83D\uDC49\u8F6E\u5230\u4F60\u4E86"
            else "$pc\n\u23F3\u7B49\u5F85\u5BF9\u65B9"
        }
    }

    fun showUndoRequestDialog(requesterName: String, callback: (Boolean) -> Unit) {
        val container = findViewById<FrameLayout>(android.R.id.content) ?: return
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#80000000")); setOnClickListener { } }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(40, 30, 40, 30)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 24f; setColor(Color.WHITE); setStroke(3, Color.parseColor("#FF8F00"))
            }
        }
        card.addView(TextView(this).apply {
            text = "\u6094\u68CB\u8BF7\u6C42"; textSize = 20f; setTextColor(Color.parseColor("#E65100"))
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 16)
        })
        card.addView(TextView(this).apply {
            text = "${requesterName}\u8BF7\u6C42\u6094\u68CB\n\u662F\u5426\u540C\u610F\uFF1F"; textSize = 16f
            setTextColor(Color.DKGRAY); gravity = Gravity.CENTER; setPadding(0, 0, 0, 24)
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnRow.addView(makeDialogBtn("\u540C\u610F", "#4CAF50") { container.removeView(overlay); callback(true) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 24 })
        btnRow.addView(makeDialogBtn("\u4E0D\u540C\u610F", "#F44336") { container.removeView(overlay); callback(false) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(btnRow)
        overlay.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        container.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun updateButtonState() {
        val btn = startBtn ?: return
        if (game.isActive && !game.isGameOver) {
            btn.text = "\uD83D\uDD04\n\u91CD\u65B0\u5F00\u59CB"
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.parseColor("#FF8F00"), Color.parseColor("#E65100"))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM; setStroke(3, Color.parseColor("#CCFFFFFF"))
            }
        } else {
            btn.text = "\u25B6\n\u5F00\u59CB"
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.parseColor("#4CAF50"), Color.parseColor("#2E7D32"))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM; setStroke(3, Color.parseColor("#CCFFFFFF"))
            }
        }
    }

    fun showRestartRequestDialog(requesterName: String, callback: (Boolean) -> Unit) {
        val container = findViewById<FrameLayout>(android.R.id.content) ?: return
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#80000000")); setOnClickListener { } }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(40, 30, 40, 30)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 24f; setColor(Color.WHITE); setStroke(3, Color.parseColor("#FF8F00"))
            }
        }
        card.addView(TextView(this).apply {
            text = "\u91CD\u65B0\u5F00\u59CB\u8BF7\u6C42"; textSize = 20f; setTextColor(Color.parseColor("#E65100"))
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 16)
        })
        card.addView(TextView(this).apply {
            text = "${requesterName}\u8BF7\u6C42\u91CD\u65B0\u5F00\u59CB\n\u662F\u5426\u540C\u610F\uFF1F"; textSize = 16f
            setTextColor(Color.DKGRAY); gravity = Gravity.CENTER; setPadding(0, 0, 0, 24)
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnRow.addView(makeDialogBtn("\u540C\u610F", "#4CAF50") { container.removeView(overlay); callback(true) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 16 })
        btnRow.addView(makeDialogBtn("\u4E0D\u540C\u610F", "#F44336") { container.removeView(overlay); callback(false) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(btnRow)
        overlay.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        container.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun makeDialogBtn(text: String, color: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 16f
        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 20f; setColor(Color.parseColor(color)) }
        setPadding(24, 14, 24, 14); setOnClickListener { onClick() }
    }

    fun showExitRequestDialog(requesterName: String, callback: (Boolean) -> Unit) {
        val container = findViewById<FrameLayout>(android.R.id.content) ?: return
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#80000000")); setOnClickListener { } }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(40, 30, 40, 30)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 24f; setColor(Color.WHITE); setStroke(3, Color.parseColor("#C62828"))
            }
        }
        card.addView(TextView(this).apply {
            text = "\u9000\u51FA\u6E38\u620F\u8BF7\u6C42"; textSize = 20f; setTextColor(Color.parseColor("#C62828"))
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 16)
        })
        card.addView(TextView(this).apply {
            text = "${requesterName}\u8BF7\u6C42\u9000\u51FA\u6E38\u620F\uFF0C\u662F\u5426\u540C\u610F\uFF1F"; textSize = 16f
            setTextColor(Color.DKGRAY); gravity = Gravity.CENTER; setPadding(0, 0, 0, 24)
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnRow.addView(makeDialogBtn("\u540C\u610F", "#4CAF50") { container.removeView(overlay); callback(true) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 16 })
        btnRow.addView(makeDialogBtn("\u4E0D\u540C\u610F", "#F44336") { container.removeView(overlay); callback(false) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(btnRow)
        overlay.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        container.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun shakeScreen() {
        val v = goView ?: return
        android.animation.ValueAnimator.ofFloat(0f, 16f, -16f, 12f, -12f, 10f, -10f, 8f, -8f, 5f, -5f, 3f, -3f, 0f).apply {
            duration = 2000; addUpdateListener { v.translationX = it.animatedValue as Float }; start()
        }
    }

    // V8.5: 返回键需对方确认后才能退出
    override fun onBackPressed() {
        if (game.isActive && !game.isGameOver) {
            getMainActivity?.invoke()?.showExitRequestDialog(game.getPlayerName(playerPerspective)) { a ->
                if (a) { getMainActivity?.invoke()?.exitApp() }
                else runOnUiThread { getMainActivity?.invoke()?.showMsgToPlayer(playerPerspective, "\u5BF9\u65B9\u62D2\u7EDD\u9000\u51FA") }
            }
        } else {
            getMainActivity?.invoke()?.finishAffinity()
            instance = null
            finishAffinity()
        }
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (game.isActive && !game.isGameOver) {
            getMainActivity?.invoke()?.showExitRequestDialog(game.getPlayerName(playerPerspective)) { a ->
                if (a) { getMainActivity?.invoke()?.exitApp() }
            }
        } else {
            getMainActivity?.invoke()?.finishAffinity()
            instance = null
            finishAffinity()
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
