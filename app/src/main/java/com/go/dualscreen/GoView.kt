package com.go.dualscreen

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI

/**
 * 围棋棋盘自定义View - 3D立体棋子 + 最大化棋盘
 * 棋子下在交叉点上（围棋标准）
 */
class GoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var playerPerspective: Int = GoGame.PLAYER_BLACK
    var game: GoGame? = null
    var onPiecePlaced: ((row: Int, col: Int) -> Unit)? = null
    var onConfirmPlace: ((row: Int, col: Int) -> Unit)? = null
    var message: String = ""
    @JvmField var autoPlayBlock = false  // 自动落子时阻止手动触摸

    // 预览状态
    private var pendingRow = -1
    private var pendingCol = -1

    // 动画状态
    private var animState = 0         // 0=none, 1=win, 2=lose, 3=egg, 4=flower
    private var animProgress = 0f
    private val particles = mutableListOf<Particle>()

    // 棋子顺序显示
    var showPieceOrder = false

    // 动画防抖
    private var lastAnimStartMs = 0L

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#50000000"); style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A3728"); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val starPtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A3728"); style = Paint.Style.FILL
    }
    private val lastMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val infoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A3728"); textSize = 22f; textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E65100"); textSize = 28f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    private var cellSize = 0f
    private var boardOffsetX = 0f
    private var boardOffsetY = 0f
    private var boardPixelW = 0f
    private var boardPixelH = 0f

    /** 动态获取棋盘大小 */
    private val boardSz get() = game?.boardSize ?: 19

    data class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float,
                        var life: Float, val maxLife: Float, val color: Int, var size: Float)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()

        // 棋盘最大化，四周留舒适边距
        val margin = 65f
        boardPixelW = w - margin * 2; boardPixelH = h - margin * 2
        val gridCount = (boardSz - 1).toFloat()
        cellSize = minOf(boardPixelW, boardPixelH) / gridCount
        val actualW = cellSize * gridCount; val actualH = cellSize * gridCount
        boardOffsetX = (w - actualW) / 2f; boardOffsetY = (h - actualH) / 2f

        // 背景
        canvas.drawPaint(Paint().apply {
            shader = RadialGradient(w / 2f, h / 2f, maxOf(w, h) * 0.7f,
                intArrayOf(Color.parseColor("#F5DEB3"), Color.parseColor("#DEB887"), Color.parseColor("#C4A265")),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })

        // 木板阴影
        val pad = 10f
        canvas.drawRoundRect(boardOffsetX - pad, boardOffsetY - pad,
            boardOffsetX + actualW + pad, boardOffsetY + actualH + pad,
            6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#35000000"); style = Paint.Style.FILL
            })

        // 棋盘面板
        canvas.drawRoundRect(boardOffsetX - pad / 2, boardOffsetY - pad / 2,
            boardOffsetX + actualW + pad / 2, boardOffsetY + actualH + pad / 2,
            5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(boardOffsetX, boardOffsetY, boardOffsetX + actualW, boardOffsetY + actualH,
                    intArrayOf(Color.parseColor("#E8D5A3"), Color.parseColor("#D4B896"), Color.parseColor("#C9A96E")),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                style = Paint.Style.FILL
            })

        // 网格线
        for (i in 0 until boardSz) {
            val p = i * cellSize
            canvas.drawLine(boardOffsetX, boardOffsetY + p, boardOffsetX + actualW, boardOffsetY + p, linePaint)
            canvas.drawLine(boardOffsetX + p, boardOffsetY, boardOffsetX + p, boardOffsetY + actualH, linePaint)
        }

        val stars = game?.getStarPoints() ?: GoGame.STAR_19
        for ((r, c) in stars) {
            canvas.drawCircle(boardOffsetX + c * cellSize, boardOffsetY + r * cellSize,
                cellSize * 0.09f, starPtPaint)
        }

        // 领地可视化区域（嵌套圆圈+多色合围之势）
        if (terrHighlight.isNotEmpty()) {
            val terrColors = intArrayOf(
                Color.parseColor("#334CAF50"),  // 绿色
                Color.parseColor("#332196F3"),  // 蓝色
                Color.parseColor("#33FF9800"),  // 橙色
                Color.parseColor("#339C27B0"),  // 紫色
                Color.parseColor("#33009688")   // 青色
            )
            val borderColors = intArrayOf(
                Color.parseColor("#AA388E3C"),
                Color.parseColor("#AA1565C0"),
                Color.parseColor("#AAE65100"),
                Color.parseColor("#AA6A1B9A"),
                Color.parseColor("#AA00695C")
            )
            // 找到领地连通分量
            val visited = mutableSetOf<Pair<Int,Int>>()
            val components = mutableListOf<List<Pair<Int,Int>>>()
            for ((tr, tc) in terrHighlight) {
                if (Pair(tr,tc) in visited) continue
                val comp = mutableListOf<Pair<Int,Int>>()
                val stack = mutableListOf(Pair(tr,tc))
                visited.add(Pair(tr,tc))
                while (stack.isNotEmpty()) {
                    val (cr, cc) = stack.removeAt(stack.size-1)
                    comp.add(Pair(cr,cc))
                    for ((dr,dc) in listOf(Pair(-1,0),Pair(1,0),Pair(0,-1),Pair(0,1))) {
                        val nr=cr+dr; val nc=cc+dc
                        if (Pair(nr,nc) in terrHighlight && Pair(nr,nc) !in visited) {
                            visited.add(Pair(nr,nc)); stack.add(Pair(nr,nc))
                        }
                    }
                }
                components.add(comp)
            }
            // 为每个连通分量绘制包围圈
            for ((ci, comp) in components.withIndex()) {
                val fillColor = terrColors[ci % terrColors.size]
                val borderColor = borderColors[ci % borderColors.size]
                // 计算包围圈（椭圆形）
                if (comp.size >= 2) {
                    var minR=999; var maxR=-1; var minC=999; var maxC=-1
                    for ((cr,cc) in comp) { minR=minOf(minR,cr); maxR=maxOf(maxR,cr); minC=minOf(minC,cc); maxC=maxOf(maxC,cc) }
                    val cx = boardOffsetX + (minC + maxC) / 2f * cellSize
                    val cy = boardOffsetY + (minR + maxR) / 2f * cellSize
                    val rx = (maxC - minC + 1.8f) * cellSize / 2
                    val ry = (maxR - minR + 1.8f) * cellSize / 2
                    // 半透明填充椭圆
                    canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = fillColor; style = Paint.Style.FILL
                    })
                    // 虚线边框
                    canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = borderColor; style = Paint.Style.STROKE; strokeWidth = 3f
                        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 6f), 0f)
                    })
                }
                // 每个领地点绘制小圆点
                for ((cr, cc) in comp) {
                    val px = boardOffsetX + cc * cellSize
                    val py = boardOffsetY + cr * cellSize
                    canvas.drawCircle(px, py, cellSize * 0.18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = borderColor; style = Paint.Style.FILL; alpha = 100
                    })
                }
            }
        }

        // 棋子（画在交叉点上）
        val g = game ?: return
        for (r in 0 until boardSz) {
            for (c in 0 until boardSz) {
                if (g.board[r][c] != GoGame.EMPTY) {
                    val cx = boardOffsetX + c * cellSize
                    val cy = boardOffsetY + r * cellSize
                    drawPiece(canvas, cx, cy, g.board[r][c], r == g.lastRow && c == g.lastCol, 1f, r, c)
                }
            }
        }

        // 预览棋子（半透明 + 蓝色勾选圈）
        if (pendingRow >= 0 && pendingCol >= 0 && g.board[pendingRow][pendingCol] == GoGame.EMPTY) {
            val pcx = boardOffsetX + pendingCol * cellSize
            val pcy = boardOffsetY + pendingRow * cellSize
            val pr = cellSize * 0.38f
            // 半透明预览棋子
            drawPiece(canvas, pcx, pcy, playerPerspective, false, 0.45f)
            // 蓝色勾选圈
            val checkR = pr * 0.6f
            canvas.drawCircle(pcx, pcy, checkR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#AA2196F3"); style = Paint.Style.FILL
            })
            canvas.drawCircle(pcx, pcy, checkR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f
            })
            // 白色勾号
            val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3.5f
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            val s = checkR * 0.5f
            val path = android.graphics.Path().apply {
                moveTo(pcx - s * 0.5f, pcy)
                lineTo(pcx - s * 0.1f, pcy + s * 0.55f)
                lineTo(pcx + s * 0.6f, pcy - s * 0.4f)
            }
            canvas.drawPath(path, checkPaint)
        }

        // 提示标记（金色闪烁圈，小于棋子避免覆盖）
        if (hintRow >= 0 && hintCol >= 0 && g.board[hintRow][hintCol] == GoGame.EMPTY) {
            val hx = boardOffsetX + hintCol * cellSize
            val hy = boardOffsetY + hintRow * cellSize
            val hr = cellSize * 0.35f
            // 外圈金色闪烁
            canvas.drawCircle(hx, hy, hr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#AAFFD700"); style = Paint.Style.STROKE; strokeWidth = 4f
            })
            // 内圈
            canvas.drawCircle(hx, hy, hr * 0.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#66FFD700"); style = Paint.Style.FILL
            })
            // 中心问号
            val qPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF6F00"); textSize = cellSize * 0.35f
                textAlign = Paint.Align.CENTER; isFakeBoldText = true
            }
            canvas.drawText("?", hx, hy + qPaint.textSize * 0.35f, qPaint)
        }

        // 鸡蛋飞行阶段
        if (animState == 3 && eggFlyPhase == 0) {
            val ex = eggFlyX; val ey = eggFlyY; val er = 24f
            canvas.drawOval(ex - er * 0.7f, ey - er, ex + er * 0.7f, ey + er, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFF8E1"); style = Paint.Style.FILL
            })
            canvas.drawOval(ex - er * 0.7f, ey - er, ex + er * 0.7f, ey + er, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#DDD7B8"); style = Paint.Style.STROKE; strokeWidth = 2f
            })
            canvas.drawLine(ex - 7f, ey - 4f, ex + 5f, ey + 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#998B72"); strokeWidth = 1.5f
            })
            canvas.drawLine(ex + 7f, ey - 7f, ex - 3f, ey + 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#998B72"); strokeWidth = 1.5f
            })
        }

        // 花蕾
        if (animState == 4 && flowerBloomPhase == 0) {
            val cxb = width / 2f; val cyb = height / 2f
            canvas.drawCircle(cxb, cyb, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#4CAF50"); style = Paint.Style.FILL
            })
            canvas.drawCircle(cxb - 3f, cyb - 5f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF80AB"); style = Paint.Style.FILL
            })
        }

        // 冲击波环
        if (showEggOverlay && shockwaveAlpha > 0 && shockwaveRadius > 0 && eggFlyPhase >= 1) {
            val cx = w / 2f; val cy = h / 2f
            canvas.drawCircle(cx, cy, shockwaveRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFFFF176")
                alpha = (shockwaveAlpha * 150).toInt().coerceIn(0, 255)
                style = Paint.Style.STROKE; strokeWidth = 5f
            })
            canvas.drawCircle(cx, cy, shockwaveRadius * 0.7f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFFFD54F")
                alpha = (shockwaveAlpha * 100).toInt().coerceIn(0, 255)
                style = Paint.Style.STROKE; strokeWidth = 2.5f
            })
        }

        // 动画粒子
        for (p in particles) {
            val alpha = (p.life / p.maxLife * 255).toInt().coerceIn(0, 255)
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = p.color; this.alpha = alpha; style = Paint.Style.FILL
            }.let { canvas.drawCircle(p.x, p.y, p.size * (p.life / p.maxLife), it) }
        }

        // 消息（屏幕底部，靠近边缘）
        if (message.isNotEmpty()) {
            canvas.drawText(message, w / 2f, h - 18f, msgPaint)
        }
    }

    private fun drawPiece(canvas: Canvas, cx: Float, cy: Float, player: Int, isLast: Boolean, alpha: Float, row: Int = -1, col: Int = -1) {
        val r = cellSize * 0.38f  // 棋子略小，避免与提示标记覆盖
        // 阴影
        canvas.drawCircle(cx + cellSize * 0.03f, cy + cellSize * 0.05f, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#50000000"); style = Paint.Style.FILL
                this.alpha = (alpha * 255).toInt()
            })
        val colors: IntArray; val stops: FloatArray
        if (player == GoGame.PLAYER_BLACK) {
            colors = intArrayOf(Color.parseColor("#666666"), Color.parseColor("#2A2A2A"),
                Color.parseColor("#111111"), Color.BLACK)
            stops = floatArrayOf(0f, 0.3f, 0.65f, 1f)
        } else {
            colors = intArrayOf(Color.WHITE, Color.parseColor("#F5F5F5"),
                Color.parseColor("#DDDDDD"), Color.parseColor("#C0C0C0"))
            stops = floatArrayOf(0f, 0.3f, 0.65f, 1f)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r, colors, stops, Shader.TileMode.CLAMP)
            style = Paint.Style.FILL; this.alpha = (alpha * 255).toInt()
        }
        canvas.drawCircle(cx, cy, r, p)
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (player == GoGame.PLAYER_BLACK) Color.parseColor("#444444") else Color.parseColor("#BBBBBB")
            style = Paint.Style.STROKE; strokeWidth = 0.8f; this.alpha = (alpha * 255).toInt()
        })
        if (isLast && alpha > 0.9f) canvas.drawCircle(cx, cy, r * 0.28f, lastMarkPaint)
        // 棋子顺序数字
        if (showPieceOrder && game != null && row >= 0 && col >= 0) {
            val order = game!!.pieceOrder[row][col]
            if (order > 0) {
                val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = cellSize * 0.30f; textAlign = Paint.Align.CENTER
                    isFakeBoldText = true
                    color = if (player == GoGame.PLAYER_BLACK) Color.WHITE else Color.parseColor("#333333")
                }
                canvas.drawText("$order", cx, cy + numPaint.textSize * 0.35f, numPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val g = game ?: return true
        if (autoPlayBlock) { message = "自动落子中，请勿手动操作"; invalidate(); return true }
        if (g.isPaused) { message = "游戏暂停中"; invalidate(); return true }
        if (g.isGameOver) { message = "游戏已结束，请重新开始"; invalidate(); return true }
        if (g.currentPlayer != playerPerspective) { message = "请等待对方落子！"; invalidate(); return true }

        // 计算点击的交叉点位置（围棋下在交叉点上）
        val col = ((event.x - boardOffsetX + cellSize / 2) / cellSize).toInt()
        val row = ((event.y - boardOffsetY + cellSize / 2) / cellSize).toInt()

        if (row < 0 || row >= boardSz || col < 0 || col >= boardSz) {
            pendingRow = -1; pendingCol = -1; message = "请在棋盘内落子"; invalidate(); return true
        }
        if (g.board[row][col] != GoGame.EMPTY) {
            pendingRow = -1; pendingCol = -1; message = "该位置已有棋子"; invalidate(); return true
        }
        // 点击预览位置的确认圈 → 真正落子
        if (pendingRow == row && pendingCol == col) {
            pendingRow = -1; pendingCol = -1; message = ""
            onConfirmPlace?.invoke(row, col)
            return true
        }
        // 设置/移动预览
        pendingRow = row; pendingCol = col; message = ""
        invalidate()
        return true
    }

    // ===== 胜利动画 =====
    private var winCleanupRunnable: Runnable? = null
    private var winPhase2Runnable: Runnable? = null
    private var winPhase3Runnable: Runnable? = null

    fun startWinAnimation() {
        winCleanupRunnable?.let { removeCallbacks(it) }
        winPhase2Runnable?.let { removeCallbacks(it) }
        winPhase3Runnable?.let { removeCallbacks(it) }
        selfAnimRunnable?.let { removeCallbacks(it) }; selfAnimRunnable = null
        animState = 1; animProgress = 0f; particles.clear()
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val colors = intArrayOf(
            Color.parseColor("#FFD700"), Color.parseColor("#FF6D00"),
            Color.parseColor("#FF1744"), Color.parseColor("#00E676"),
            Color.parseColor("#2979FF"), Color.parseColor("#FF4081"),
            Color.parseColor("#FFEA00"), Color.parseColor("#AA00FF"),
            Color.parseColor("#00BCD4"), Color.parseColor("#FF5722"))
        for (i in 0..120) {
            val angle = Math.random() * 2 * PI
            val speed = 5f + Math.random().toFloat() * 18f
            particles.add(Particle(cx, cy,
                (Math.cos(angle) * speed).toFloat(), (Math.sin(angle) * speed).toFloat(),
                3.0f, 3.0f, colors[i % colors.size], 8f + Math.random().toFloat() * 16f))
        }
        for (i in 0..100) {
            particles.add(Particle(
                (Math.random() * w).toFloat(), -20f - Math.random().toFloat() * h * 0.3f,
                ((Math.random() - 0.5) * 3f).toFloat(), 2f + Math.random().toFloat() * 6f,
                2.8f, 2.8f, colors[i % colors.size], 5f + Math.random().toFloat() * 12f))
        }
        for (i in 0..80) {
            particles.add(Particle(
                (Math.random() * w).toFloat(), (Math.random() * h).toFloat(),
                ((Math.random() - 0.5) * 2f).toFloat(), ((Math.random() - 0.5) * 2f).toFloat(),
                2.0f, 2.0f, Color.parseColor("#FFD700"), 3f + Math.random().toFloat() * 10f))
        }
        message = "胜利！"
        invalidate()
        ensureAnimating()
        winPhase2Runnable = Runnable {
            if (animState != 1) return@Runnable
            val cx2 = w * (0.2f + Math.random().toFloat() * 0.6f)
            val cy2 = h * (0.2f + Math.random().toFloat() * 0.6f)
            for (i in 0..80) {
                val angle = Math.random() * 2 * PI
                val speed = 4f + Math.random().toFloat() * 14f
                particles.add(Particle(cx2, cy2,
                    (Math.cos(angle) * speed).toFloat(), (Math.sin(angle) * speed).toFloat(),
                    2.5f, 2.5f, colors[(i + 3) % colors.size], 6f + Math.random().toFloat() * 14f))
            }
            for (i in 0..50) {
                particles.add(Particle(
                    (Math.random() * w).toFloat(), (Math.random() * h).toFloat(),
                    ((Math.random() - 0.5) * 1.5f).toFloat(), ((Math.random() - 0.5) * 1.5f).toFloat(),
                    2.0f, 2.0f, Color.parseColor("#FFD700"), 4f + Math.random().toFloat() * 8f))
            }
        }
        postDelayed(winPhase2Runnable!!, 1500)
        winPhase3Runnable = Runnable {
            if (animState != 1) return@Runnable
            for (i in 0..60) {
                particles.add(Particle(
                    (Math.random() * w).toFloat(), h + 20f,
                    ((Math.random() - 0.5) * 2f).toFloat(), -(6f + Math.random().toFloat() * 12f),
                    2.2f, 2.2f, colors[i % colors.size], 5f + Math.random().toFloat() * 10f))
            }
        }
        postDelayed(winPhase3Runnable!!, 2800)
        winCleanupRunnable = Runnable {
            message = ""; particles.clear(); animState = 0
            selfAnimRunnable?.let { removeCallbacks(it) }; selfAnimRunnable = null
            invalidate()
        }
        postDelayed(winCleanupRunnable!!, 5000)
    }

    // ===== 失败动画 =====
    private var loseCleanupRunnable: Runnable? = null
    private var losePhase2Runnable: Runnable? = null
    private var losePhase3Runnable: Runnable? = null

    fun startLoseAnimation() {
        loseCleanupRunnable?.let { removeCallbacks(it) }
        losePhase2Runnable?.let { removeCallbacks(it) }
        losePhase3Runnable?.let { removeCallbacks(it) }
        selfAnimRunnable?.let { removeCallbacks(it) }; selfAnimRunnable = null
        animState = 2; animProgress = 0f; particles.clear()
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val darkColors = intArrayOf(
            Color.parseColor("#455A64"), Color.parseColor("#37474F"),
            Color.parseColor("#546E7A"), Color.parseColor("#263238"),
            Color.parseColor("#607D8B"), Color.parseColor("#1A237E"),
            Color.parseColor("#311B92"), Color.parseColor("#004D40"))
        for (i in 0..120) {
            val angle = Math.random() * 2 * PI
            val speed = 3f + Math.random().toFloat() * 14f
            particles.add(Particle(cx, cy,
                (Math.cos(angle) * speed).toFloat(), (Math.sin(angle) * speed).toFloat(),
                3.0f, 3.0f, darkColors[i % darkColors.size], 6f + Math.random().toFloat() * 16f))
        }
        for (i in 0..100) {
            particles.add(Particle(
                (Math.random() * w).toFloat(), -20f - Math.random().toFloat() * h * 0.3f,
                ((Math.random() - 0.5) * 1.5f).toFloat(), 1.2f + Math.random().toFloat() * 4f,
                2.5f, 2.5f, darkColors[i % darkColors.size], 3f + Math.random().toFloat() * 12f))
        }
        for (i in 0..60) {
            particles.add(Particle(
                (Math.random() * w).toFloat(), (Math.random() * h).toFloat(),
                ((Math.random() - 0.5) * 1f).toFloat(), ((Math.random() - 0.5) * 1f).toFloat(),
                2.2f, 2.2f, Color.parseColor("#33222222"), 12f + Math.random().toFloat() * 28f))
        }
        for (i in 0..50) {
            particles.add(Particle(
                (Math.random() * w).toFloat(), (Math.random() * h).toFloat(),
                ((Math.random() - 0.5) * 2.5f).toFloat(), ((Math.random() - 0.5) * 2.5f).toFloat(),
                2.0f, 2.0f, Color.parseColor("#CC37474F"), 2f + Math.random().toFloat() * 4f))
        }
        message = "失败"
        invalidate()
        ensureAnimating()
        losePhase2Runnable = Runnable {
            if (animState != 2) return@Runnable
            for (i in 0..80) {
                val angle = Math.random() * 2 * PI
                val dist = 100f + Math.random().toFloat() * 200f
                val sx = cx + (Math.cos(angle) * dist).toFloat()
                val sy = cy + (Math.sin(angle) * dist).toFloat()
                particles.add(Particle(sx, sy,
                    ((cx - sx) * 0.03f).toFloat(), ((cy - sy) * 0.03f).toFloat(),
                    2.2f, 2.2f, darkColors[i % darkColors.size], 5f + Math.random().toFloat() * 12f))
            }
        }
        postDelayed(losePhase2Runnable!!, 1500)
        losePhase3Runnable = Runnable {
            if (animState != 2) return@Runnable
            for (i in 0..60) {
                particles.add(Particle(
                    (Math.random() * w).toFloat(), -30f,
                    ((Math.random() - 0.5) * 2f).toFloat(), 3f + Math.random().toFloat() * 8f,
                    1.8f, 1.8f, darkColors[i % darkColors.size], 4f + Math.random().toFloat() * 10f))
            }
        }
        postDelayed(losePhase3Runnable!!, 2800)
        loseCleanupRunnable = Runnable {
            message = ""; particles.clear(); animState = 0
            selfAnimRunnable?.let { removeCallbacks(it) }; selfAnimRunnable = null
            invalidate()
        }
        postDelayed(loseCleanupRunnable!!, 5000)
    }

    // ===== 臭鸡蛋动画 =====
    private var showEggOverlay = false
    private var eggOverlayAlpha = 0f
    private var shockwaveRadius = 0f
    private var shockwaveAlpha = 0f
    private var eggFlyPhase = 0
    private var eggFlyX = 0f; private var eggFlyY = 0f

    private var eggCleanupRunnable: Runnable? = null
    private var flowerCleanupRunnable: Runnable? = null

    fun startEggAnimation() {
        val now = System.currentTimeMillis()
        if (now - lastAnimStartMs < 500) return
        lastAnimStartMs = now
        eggCleanupRunnable?.let { removeCallbacks(it) }
        selfAnimRunnable?.let { removeCallbacks(it) }; selfAnimRunnable = null
        animState = 3; animProgress = 0f; particles.clear()
        showEggOverlay = true; eggOverlayAlpha = 1f
        shockwaveRadius = 0f; shockwaveAlpha = 0f
        eggFlyPhase = 0
        eggFlyX = -80f; eggFlyY = -80f
        message = ""
        invalidate()
        ensureAnimating()
        postDelayed({
            eggFlyPhase = 1; shockwaveAlpha = 1f
            val w = width.toFloat(); val h = height.toFloat()
            val cx = w / 2f; val cy = h / 2f
            val yolkC = intArrayOf(Color.parseColor("#FFD700"), Color.parseColor("#FFA500"),
                Color.parseColor("#FFC107"), Color.parseColor("#FF9800"), Color.parseColor("#FF8F00"))
            for (i in 0..40) {
                val a = Math.random() * 2 * PI; val spd = 6f + Math.random().toFloat() * 18f
                particles.add(Particle(cx, cy, (Math.cos(a) * spd).toFloat(),
                    (Math.sin(a) * spd).toFloat() - 2f, 2f, 2f, yolkC[i % yolkC.size],
                    15f + Math.random().toFloat() * 30f))
            }
            val whiteC = intArrayOf(Color.parseColor("#FFFACD"), Color.parseColor("#FFFFF0"),
                Color.parseColor("#FFF8DC"), Color.parseColor("#FAFAD2"))
            for (i in 0..60) {
                val a = Math.random() * 2 * PI; val spd = 4f + Math.random().toFloat() * 14f
                particles.add(Particle(cx, cy, (Math.cos(a) * spd).toFloat(),
                    (Math.sin(a) * spd).toFloat() - 1f, 1.6f, 1.6f, whiteC[i % whiteC.size],
                    8f + Math.random().toFloat() * 22f))
            }
            val shC = intArrayOf(Color.parseColor("#F5DEB3"), Color.parseColor("#FAEBD7"),
                Color.parseColor("#FFF8DC"), Color.parseColor("#FFE4B5"))
            for (i in 0..70) {
                val a = Math.random() * 2 * PI; val spd = 8f + Math.random().toFloat() * 22f
                particles.add(Particle(cx, cy, (Math.cos(a) * spd).toFloat(),
                    (Math.sin(a) * spd).toFloat() - 5f, 1f, 1f, shC[i % shC.size],
                    5f + Math.random().toFloat() * 16f))
            }
            val stC = intArrayOf(Color.parseColor("#7CB342"), Color.parseColor("#558B2F"),
                Color.parseColor("#9CCC65"))
            for (i in 0..20) {
                val a = Math.random() * 2 * PI; val r = 50f + Math.random().toFloat() * 150f
                particles.add(Particle(cx + (Math.cos(a) * r).toFloat(), cy + (Math.sin(a) * r).toFloat(),
                    0f, -3f - Math.random().toFloat() * 6f, 1.5f, 1.5f, stC[i % stC.size],
                    12f + Math.random().toFloat() * 20f))
            }
            message = "啪！"
            invalidate()
        }, 250)
        postDelayed({ shockwaveAlpha = 0f; invalidate() }, 1200)
        eggCleanupRunnable = Runnable {
            showEggOverlay = false; eggOverlayAlpha = 0f; message = ""; particles.clear(); invalidate()
        }
        postDelayed(eggCleanupRunnable!!, 2800)
    }

    // ===== 自驱动动画循环 =====
    private var selfAnimRunnable: Runnable? = null

    private fun ensureAnimating() {
        if (selfAnimRunnable != null) return
        selfAnimRunnable = object : Runnable {
            override fun run() {
                if (!updateAnimation()) { selfAnimRunnable = null; return }
                postDelayed(this, 33)
            }
        }
        postDelayed(selfAnimRunnable!!, 33)
    }

    fun updateAnimation(): Boolean {
        if (animState == 0) return false
        animProgress += 0.03f
        if (animState == 3 && eggFlyPhase == 0) {
            val cx = width / 2f; val cy = height / 2f
            eggFlyX += (cx - eggFlyX) * 0.12f
            eggFlyY += (cy - eggFlyY) * 0.12f
        }
        if (shockwaveAlpha > 0) shockwaveRadius += 18f
        val iter = particles.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.x += p.vx; p.y += p.vy
            if (animState == 4 && flowerBloomPhase == 1) {
                p.size += 0.5f
                p.vx *= 1.04f; p.vy *= 1.04f
            } else if (animState == 4 && flowerBloomPhase == 2) {
                p.vx += ((Math.random() - 0.5) * 0.5f).toFloat()
                p.vy += 0.03f
            } else {
                p.vy += 0.2f
            }
            p.life -= 0.018f
            if (p.life <= 0) iter.remove()
        }
        if (animProgress > 1.5f && particles.isEmpty() && eggFlyPhase >= 1 && animState != 1 && animState != 2) {
            animState = 0; showEggOverlay = false; eggOverlayAlpha = 0f
            shockwaveAlpha = 0f; eggFlyPhase = 0; flowerBloomPhase = 0; message = ""
        }
        invalidate()
        return animState != 0
    }

    // ===== 鲜花绽放动画 =====
    private var flowerBloomPhase = 0

    fun startFlowerAnimation() {
        val now = System.currentTimeMillis()
        if (now - lastAnimStartMs < 500) return
        lastAnimStartMs = now
        flowerCleanupRunnable?.let { removeCallbacks(it) }
        selfAnimRunnable?.let { removeCallbacks(it) }; selfAnimRunnable = null
        animState = 4; animProgress = 0f; particles.clear()
        flowerBloomPhase = 0
        showEggOverlay = false; eggOverlayAlpha = 0f
        message = ""
        invalidate()
        ensureAnimating()
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        postDelayed({
            flowerBloomPhase = 1
            val petalC = intArrayOf(Color.parseColor("#FF6B8A"), Color.parseColor("#FF4081"),
                Color.parseColor("#FF80AB"), Color.parseColor("#E91E63"), Color.parseColor("#F48FB1"),
                Color.parseColor("#FF8A80"))
            for (i in 0..80) {
                val angle = (i.toDouble() / 80.0) * 2 * PI
                particles.add(Particle(cx, cy, (Math.cos(angle) * 0.5f).toFloat(),
                    (Math.sin(angle) * 0.5f).toFloat(), 1.2f, 1.2f, petalC[i % petalC.size], 8f))
            }
            message = ""
            invalidate()
        }, 200)
        postDelayed({
            flowerBloomPhase = 2
            val petalC2 = intArrayOf(Color.parseColor("#FFD54F"), Color.parseColor("#FFC107"),
                Color.parseColor("#CE93D8"), Color.parseColor("#BA68C8"), Color.parseColor("#90CAF9"),
                Color.parseColor("#64B5F6"), Color.parseColor("#FFAB91"), Color.parseColor("#A5D6A7"))
            for (i in 0..120) {
                val angle = (i.toDouble() / 120.0) * 2 * PI
                val spd = 3f + Math.random().toFloat() * 8f
                particles.add(Particle(cx, cy, (Math.cos(angle) * spd).toFloat(),
                    (Math.sin(angle) * spd).toFloat(), 1.5f, 1.5f, petalC2[i % petalC2.size],
                    8f + Math.random().toFloat() * 16f))
            }
            for (i in 0..40) {
                particles.add(Particle(cx + (Math.random().toFloat() - 0.5f) * 200f,
                    cy + (Math.random().toFloat() - 0.5f) * 200f,
                    0f, 0f, 0.8f, 0.8f, Color.parseColor("#FFFFFF"),
                    3f + Math.random().toFloat() * 8f))
            }
            message = ""
            invalidate()
        }, 800)
        flowerCleanupRunnable = Runnable {
            message = ""; particles.clear(); animState = 0; flowerBloomPhase = 0
            selfAnimRunnable?.let { removeCallbacks(it) }; selfAnimRunnable = null
            invalidate()
        }
        postDelayed(flowerCleanupRunnable!!, 2800)
    }

    fun clearAllAnimations() {
        winCleanupRunnable?.let { removeCallbacks(it) }; winCleanupRunnable = null
        loseCleanupRunnable?.let { removeCallbacks(it) }; loseCleanupRunnable = null
        eggCleanupRunnable?.let { removeCallbacks(it) }; eggCleanupRunnable = null
        flowerCleanupRunnable?.let { removeCallbacks(it) }; flowerCleanupRunnable = null
        selfAnimRunnable?.let { removeCallbacks(it) }; selfAnimRunnable = null
        animState = 0; particles.clear()
        showEggOverlay = false; eggOverlayAlpha = 0f; shockwaveAlpha = 0f
        flowerBloomPhase = 0; eggFlyPhase = 0
        message = ""; invalidate()
    }

    fun clearPreview() { pendingRow = -1; pendingCol = -1 }
    fun clearMessage() { message = ""; invalidate() }
    fun showMessage(msg: String) { message = msg; invalidate() }

    /** 提示位置标记 */
    private var hintRow = -1
    private var hintCol = -1
    fun showHint(row: Int, col: Int) { hintRow = row; hintCol = col; invalidate() }
    fun clearHint() { hintRow = -1; hintCol = -1 }

    /** 领地可视化区域 */
    private var terrHighlight: Set<Pair<Int,Int>> = emptySet()
    fun showTerritory(pts: Set<Pair<Int,Int>>) { terrHighlight = pts; invalidate() }
    fun clearTerritory() { terrHighlight = emptySet() }
}
