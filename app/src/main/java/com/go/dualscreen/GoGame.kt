package com.go.dualscreen

/**
 * 围棋游戏核心逻辑
 * 支持 9×9 / 13×13 / 19×19 棋盘
 * 中国规则：黑贴 3¾ 子（7.5 目），黑棋 ≥184.5 子获胜
 * 气计算、提子、劫检测、数目、Pass
 */
class GoGame(boardSize: Int = 19) {
    companion object {
        const val EMPTY = 0
        const val PLAYER_BLACK = 1
        const val PLAYER_WHITE = 2

        /** 中国规则贴目：黑方贴 7.5 目（相当于 3¾ 子） */
        const val KOMI = 7.5

        val STAR_19 = arrayOf(
            intArrayOf(3, 3), intArrayOf(3, 9), intArrayOf(3, 15),
            intArrayOf(9, 3), intArrayOf(9, 9), intArrayOf(9, 15),
            intArrayOf(15, 3), intArrayOf(15, 9), intArrayOf(15, 15)
        )
        val STAR_13 = arrayOf(
            intArrayOf(3, 3), intArrayOf(3, 6), intArrayOf(3, 9),
            intArrayOf(6, 3), intArrayOf(6, 6), intArrayOf(6, 9),
            intArrayOf(9, 3), intArrayOf(9, 6), intArrayOf(9, 9)
        )
        val STAR_9 = arrayOf(
            intArrayOf(2, 2), intArrayOf(2, 4), intArrayOf(2, 6),
            intArrayOf(4, 2), intArrayOf(4, 4), intArrayOf(4, 6),
            intArrayOf(6, 2), intArrayOf(6, 4), intArrayOf(6, 6)
        )
    }

    /** 棋盘大小 (9/13/19) */
    var boardSize = boardSize
        private set

    lateinit var board: Array<IntArray>
        private set
    lateinit var pieceOrder: Array<IntArray>
        private set

    var currentPlayer = PLAYER_BLACK
        private set
    var isGameOver = false
        private set
    var isActive = false
        private set
    var winner = EMPTY
        private set

    var capturedByBlack = 0
        private set
    var capturedByWhite = 0
        private set

    var isPaused = false
    var pausedByPlayer = EMPTY
    var pauseCountBlack = 1
    var pauseCountWhite = 1

    var lastRow = -1
        private set
    var lastCol = -1
        private set

    private val moveHistory = mutableListOf<MoveRecord>()
    /** V10.3: 暴露着法历史供诊断用 */
    val debugMoveHistory: List<MoveRecord> get() = moveHistory.toList()

    private var koRestrictedRow = -1
    private var koRestrictedCol = -1

    var consecutivePasses = 0
        private set

    var gameOverReason: String = ""
        private set

    var blackTerritory = 0
        private set
    var whiteTerritory = 0
        private set

    var totalMoves = 0
        private set
    // V5.6: 黑白各自计数
    private var blackMoves = 0
    private var whiteMoves = 0

    init { initBoards() }

    private fun initBoards() {
        board = Array(boardSize) { IntArray(boardSize) { EMPTY } }
        pieceOrder = Array(boardSize) { IntArray(boardSize) { 0 } }
    }

    fun setBoardSize(size: Int) {
        if (isActive) return
        boardSize = size
        handicapStones = 0  // V9.6: 切换棋盘格时重置让子
        restart()
    }

    data class MoveRecord(
        val row: Int, val col: Int, val player: Int,
        val capturedStones: List<Pair<Int, Int>>,
        val capturedPieceOrders: Map<Pair<Int,Int>, Int>,
        val prevKoRow: Int, val prevKoCol: Int,
        val prevConsecutivePasses: Int,
        val prevCapturedByBlack: Int,
        val prevCapturedByWhite: Int
    )

    fun getStarPoints(): Array<IntArray> = when (boardSize) {
        9 -> STAR_9; 13 -> STAR_13; else -> STAR_19
    }

    // ==================== 落子 ====================

    fun placePiece(row: Int, col: Int, player: Int): PlaceResult {
        if (!isActive) return PlaceResult(false, "请先点击「开始」开始对局")
        if (isGameOver) return PlaceResult(false, "对局已结束，请重新开始")
        if (player != currentPlayer) {
            return PlaceResult(false, if (currentPlayer == PLAYER_BLACK) "现在轮到黑方落子，请等待" else "现在轮到白方落子，请等待")
        }
        if (row < 0 || row >= boardSize || col < 0 || col >= boardSize) {
            return PlaceResult(false, "落子位置超出棋盘范围")
        }
        if (board[row][col] != EMPTY) {
            return PlaceResult(false, "此处已有棋子，请选择空交叉点")
        }
        if (row == koRestrictedRow && col == koRestrictedCol) {
            return PlaceResult(false, "劫争——不可立即提回！\n须先在别处走一手（找劫材），对方应劫后才能回提")
        }

        board[row][col] = player
        consecutivePasses = 0
        val opponent = if (player == PLAYER_BLACK) PLAYER_WHITE else PLAYER_BLACK

        val captured = mutableListOf<Pair<Int, Int>>()
        // V5.6: 记录被提子原有的序号，用于悔棋恢复
        val capturedOrders = mutableMapOf<Pair<Int,Int>, Int>()
        for ((nr, nc) in getNeighbors(row, col)) {
            if (board[nr][nc] == opponent) {
                val group = getGroup(nr, nc)
                if (countLiberties(group) == 0) {
                    for ((gr, gc) in group) {
                        capturedOrders[Pair(gr, gc)] = pieceOrder[gr][gc]
                        board[gr][gc] = EMPTY
                        pieceOrder[gr][gc] = 0
                        captured.add(Pair(gr, gc))
                    }
                }
            }
        }

        val myGroup = getGroup(row, col)
        if (countLiberties(myGroup) == 0 && captured.isEmpty()) {
            board[row][col] = EMPTY
            consecutivePasses = 0
            return PlaceResult(false, "禁入点——落子后己方无气且无法提掉对方棋子（禁止自杀）")
        }

        val capCount = captured.size
        if (player == PLAYER_BLACK) capturedByBlack += capCount
        else capturedByWhite += capCount

        val prevKoRow = koRestrictedRow
        val prevKoCol = koRestrictedCol
        if (captured.size == 1 && countLiberties(myGroup) == 1) {
            koRestrictedRow = captured[0].first
            koRestrictedCol = captured[0].second
        } else { koRestrictedRow = -1; koRestrictedCol = -1 }

        totalMoves++
        // V5.6: 黑白各数各的，都从1开始
        if (player == PLAYER_BLACK) { blackMoves++; pieceOrder[row][col] = blackMoves }
        else { whiteMoves++; pieceOrder[row][col] = whiteMoves }
        lastRow = row; lastCol = col

        moveHistory.add(MoveRecord(row, col, player, captured, capturedOrders,
            prevKoRow, prevKoCol, 0,
            if (player == PLAYER_BLACK) capturedByBlack - capCount else capturedByBlack,
            if (player == PLAYER_WHITE) capturedByWhite - capCount else capturedByWhite))

        currentPlayer = opponent
        val nextName = if (currentPlayer == PLAYER_BLACK) "黑方" else "白方"
        val capMsg = if (capCount > 0) "，提${capCount}子" else ""
        // V8.5 debug: 每次落子记录棋子序号，方便通过序号定位问题
        android.util.Log.i("GoGame", "place: ${if (player == PLAYER_BLACK) "B" else "W"} #${pieceOrder[row][col]} at ($row,$col) caps=$capCount next=${if (currentPlayer == PLAYER_BLACK) "B" else "W"}")
        if (capCount > 0) android.util.Log.i("GoGame", "  captured: ${captured.map { "(${it.first},${it.second})" }}")
        return PlaceResult(true, "轮到${nextName}落子$capMsg", captures = capCount, capturedStones = captured.toList())
    }

    // ==================== Pass ====================

    fun pass(player: Int): PlaceResult {
        if (!isActive) return PlaceResult(false, "请先点击「开始」开始对局")
        if (isGameOver) return PlaceResult(false, "对局已结束，请重新开始")
        if (player != currentPlayer) {
            return PlaceResult(false, if (currentPlayer == PLAYER_BLACK) "现在轮到黑方操作，请等待" else "现在轮到白方操作，请等待")
        }
        consecutivePasses++
        moveHistory.add(MoveRecord(-1, -1, player, emptyList(), emptyMap(),
            koRestrictedRow, koRestrictedCol, consecutivePasses - 1,
            capturedByBlack, capturedByWhite))
        koRestrictedRow = -1; koRestrictedCol = -1

        if (consecutivePasses >= 2) {
            isGameOver = true
            calculateTerritory()
            val bTotal = blackTerritory + capturedByBlack
            val wTotal = whiteTerritory + capturedByWhite + KOMI
            winner = if (bTotal > wTotal) PLAYER_BLACK else PLAYER_WHITE
            gameOverReason = buildString {
                append("双方虚手，对局结束\n")
                append("黑方: 地盘${blackTerritory} + 提子${capturedByBlack} = ${blackTerritory + capturedByBlack}\n")
                append("白方: 地盘${whiteTerritory} + 提子${capturedByWhite} + 贴目${KOMI} = ${"%.1f".format(wTotal)}\n")
                append(if (winner == PLAYER_BLACK) "黑方胜 ${"%.1f".format(bTotal - wTotal)} 目" else "白方胜 ${"%.1f".format(wTotal - bTotal)} 目")
            }
            return PlaceResult(true, gameOverReason, gameOver = true, winner = winner)
        }
        currentPlayer = if (player == PLAYER_BLACK) PLAYER_WHITE else PLAYER_BLACK
        val nextName = if (currentPlayer == PLAYER_BLACK) "黑方" else "白方"
        return PlaceResult(true, "${getPlayerName(player)}虚手，轮到${nextName}")
    }

    // ==================== 数目 ====================

    fun endGameAndScore(): PlaceResult {
        if (!isActive) return PlaceResult(false, "请先点击「开始」开始对局")
        if (isGameOver) return PlaceResult(false, "对局已结束")
        isGameOver = true
        calculateTerritory()
        val bTotal = blackTerritory + capturedByBlack
        val wTotal = whiteTerritory + capturedByWhite + KOMI
        winner = if (bTotal > wTotal) PLAYER_BLACK else PLAYER_WHITE
        gameOverReason = buildString {
            append("终局数目\n")
            append("黑方: 地盘${blackTerritory} + 提子${capturedByBlack} = ${blackTerritory + capturedByBlack}\n")
            append("白方: 地盘${whiteTerritory} + 提子${capturedByWhite} + 贴目${KOMI} = ${"%.1f".format(wTotal)}\n")
            append(if (winner == PLAYER_BLACK) "黑方胜 ${"%.1f".format(bTotal - wTotal)} 目" else "白方胜 ${"%.1f".format(wTotal - bTotal)} 目")
        }
        return PlaceResult(true, gameOverReason, gameOver = true, winner = winner)
    }

    /** 临时数目估算（不结束游戏，用于数目按钮） */
    fun calculateTerritoryTemp() { calculateTerritory() }

    private fun calculateTerritory() {
        blackTerritory = 0; whiteTerritory = 0
        val visited = Array(boardSize) { BooleanArray(boardSize) }
        for (r in 0 until boardSize) for (c in 0 until boardSize) {
            if (board[r][c] == EMPTY && !visited[r][c]) {
                val region = mutableListOf<Pair<Int, Int>>()
                val borders = mutableSetOf<Int>()
                floodFill(r, c, visited, region, borders)
                if (borders.size == 1) {
                    when (borders.first()) {
                        PLAYER_BLACK -> blackTerritory += region.size
                        PLAYER_WHITE -> whiteTerritory += region.size
                    }
                }
            }
        }
    }

    private fun floodFill(r: Int, c: Int, visited: Array<BooleanArray>,
                          region: MutableList<Pair<Int, Int>>, borders: MutableSet<Int>) {
        if (r < 0 || r >= boardSize || c < 0 || c >= boardSize || visited[r][c]) return
        if (board[r][c] != EMPTY) { borders.add(board[r][c]); return }
        visited[r][c] = true; region.add(Pair(r, c))
        floodFill(r - 1, c, visited, region, borders)
        floodFill(r + 1, c, visited, region, borders)
        floodFill(r, c - 1, visited, region, borders)
        floodFill(r, c + 1, visited, region, borders)
    }

    // ==================== 气与连通 ====================

    private fun getNeighbors(row: Int, col: Int): List<Pair<Int, Int>> {
        val r = mutableListOf<Pair<Int, Int>>()
        if (row > 0) r.add(Pair(row - 1, col))
        if (row < boardSize - 1) r.add(Pair(row + 1, col))
        if (col > 0) r.add(Pair(row, col - 1))
        if (col < boardSize - 1) r.add(Pair(row, col + 1))
        return r
    }

    fun getGroup(row: Int, col: Int): Set<Pair<Int, Int>> {
        val color = board[row][col]
        if (color == EMPTY) return emptySet()
        val group = mutableSetOf<Pair<Int, Int>>()
        val stack = mutableListOf(Pair(row, col))
        val visited = Array(boardSize) { BooleanArray(boardSize) }
        visited[row][col] = true
        while (stack.isNotEmpty()) {
            val (r, c) = stack.removeAt(stack.size - 1)
            group.add(Pair(r, c))
            for ((nr, nc) in getNeighbors(r, c)) {
                if (!visited[nr][nc] && board[nr][nc] == color) {
                    visited[nr][nc] = true; stack.add(Pair(nr, nc))
                }
            }
        }
        return group
    }

    fun countLiberties(group: Set<Pair<Int, Int>>): Int {
        val libs = mutableSetOf<Pair<Int, Int>>()
        for ((r, c) in group) for ((nr, nc) in getNeighbors(r, c)) {
            if (board[nr][nc] == EMPTY) libs.add(Pair(nr, nc))
        }
        return libs.size
    }

    // ==================== 悔棋 ====================

    fun undo(requestingPlayer: Int): UndoResult {
        if (!isActive) return UndoResult(false, "游戏尚未开始", 0)
        if (isGameOver) return UndoResult(false, "游戏已结束，无法悔棋", 0)
        if (moveHistory.isEmpty()) return UndoResult(false, "没有可悔的棋", 0)
        val myIdx = moveHistory.indexOfLast { it.player == requestingPlayer }
        if (myIdx < 0) return UndoResult(false, "你还没有落子，无法悔棋", 0)
        val lastMove = moveHistory.last()
        var undoCount = 0
        if (lastMove.player != requestingPlayer) {
            val op = moveHistory.removeAt(moveHistory.size - 1)
            if (op.row >= 0) {
                board[op.row][op.col] = EMPTY; pieceOrder[op.row][op.col] = 0
                // V5.6: 恢复被提子及序号
                for ((cr, cc) in op.capturedStones) {
                    board[cr][cc] = if (op.player == PLAYER_BLACK) PLAYER_WHITE else PLAYER_BLACK
                    pieceOrder[cr][cc] = op.capturedPieceOrders[Pair(cr, cc)] ?: 0
                }
                if (op.player == PLAYER_BLACK) blackMoves-- else whiteMoves--
            }
            koRestrictedRow = op.prevKoRow; koRestrictedCol = op.prevKoCol
            consecutivePasses = op.prevConsecutivePasses
            capturedByBlack = op.prevCapturedByBlack; capturedByWhite = op.prevCapturedByWhite
            undoCount++
        }
        val myNewIdx = moveHistory.indexOfLast { it.player == requestingPlayer }
        val my = moveHistory.removeAt(myNewIdx)
        if (my.row >= 0) {
            board[my.row][my.col] = EMPTY; pieceOrder[my.row][my.col] = 0
            // V5.6: 恢复被提子及序号
            for ((cr, cc) in my.capturedStones) {
                board[cr][cc] = if (my.player == PLAYER_BLACK) PLAYER_WHITE else PLAYER_BLACK
                pieceOrder[cr][cc] = my.capturedPieceOrders[Pair(cr, cc)] ?: 0
            }
            if (my.player == PLAYER_BLACK) blackMoves-- else whiteMoves--
        }
        koRestrictedRow = my.prevKoRow; koRestrictedCol = my.prevKoCol
        consecutivePasses = my.prevConsecutivePasses
        capturedByBlack = my.prevCapturedByBlack; capturedByWhite = my.prevCapturedByWhite
        undoCount++
        totalMoves -= undoCount
        currentPlayer = requestingPlayer
        lastRow = moveHistory.lastOrNull()?.let { if (it.row >= 0) it.row else -1 } ?: -1
        lastCol = moveHistory.lastOrNull()?.let { if (it.row >= 0) it.col else -1 } ?: -1
        if (moveHistory.isEmpty()) {
            for (r in 0 until boardSize) for (c in 0 until boardSize) pieceOrder[r][c] = 0
            totalMoves = 0
        }
        return UndoResult(true, "已悔${undoCount}步，轮到${getPlayerName(requestingPlayer)}", undoCount)
    }

    fun canUndo(player: Int): Boolean = !isGameOver && moveHistory.any { it.player == player }

    // ==================== 公用领地/包围圈工具函数 ====================

    /** 某方领地点集（用于提示可视化，距离≤3） */
    fun getTerritoryPoints(who: Int): Set<Pair<Int,Int>> {
        val dist = Array(boardSize) { IntArray(boardSize) { 999 } }
        val q = ArrayDeque<Triple<Int,Int,Int>>()
        for (r in 0 until boardSize) for (c in 0 until boardSize) if (board[r][c] == who) {
            q.addLast(Triple(r, c, 0)); dist[r][c] = 0
        }
        while (q.isNotEmpty()) { val (r,c,d) = q.removeFirst(); if (d >= 4) continue
            for ((nr,nc) in getNeighbors(r,c)) if (dist[nr][nc] > d+1) { dist[nr][nc] = d+1; q.addLast(Triple(nr,nc,d+1)) } }
        val pts = mutableSetOf<Pair<Int,Int>>()
        for (r in 0 until boardSize) for (c in 0 until boardSize) if (board[r][c] == EMPTY && dist[r][c] <= 3) pts.add(Pair(r,c))
        return pts
    }

    /** 落子后新增的领地/包围圈点集 */
    fun getTerritoryGain(player: Int, row: Int, col: Int): Set<Pair<Int,Int>> {
        val before = getTerritoryPoints(player)
        val svd = Array(boardSize) { board[it].copyOf() }
        val sCB = capturedByBlack; val sCW = capturedByWhite
        board[row][col] = player
        val opp = if (player == PLAYER_BLACK) PLAYER_WHITE else PLAYER_BLACK
        for ((nr,nc) in getNeighbors(row,col)) if (board[nr][nc]==opp) {
            val g = getGroup(nr,nc); if (countLiberties(g)==0) for ((gr,gc) in g) board[gr][gc] = EMPTY
        }
        val after = getTerritoryPoints(player)
        for (i in 0 until boardSize) board[i] = svd[i]
        capturedByBlack = sCB; capturedByWhite = sCW
        return after - before
    }

    /** 领地计数（快速版） */
    private fun terrCntFast(who: Int): Int { var t=0; val d=Array(boardSize){IntArray(boardSize){999}}; val q=ArrayDeque<Triple<Int,Int,Int>>()
        for(r in 0 until boardSize) for(c in 0 until boardSize) if(board[r][c]==who){q.addLast(Triple(r,c,0));d[r][c]=0}
        while(q.isNotEmpty()){val(r,c,dd)=q.removeFirst();if(dd>=5)continue
            for((nr,nc)in getNeighbors(r,c))if(d[nr][nc]>dd+1){d[nr][nc]=dd+1;q.addLast(Triple(nr,nc,dd+1))}}
        for(r in 0 until boardSize) for(c in 0 until boardSize) if(board[r][c]==EMPTY&&d[r][c]<=4)t++; return t }

    // ==================== 落子推荐引擎 V2.7 - MCTS ====================

    // --- 仿真辅助：在独立棋盘上操作（不修改游戏状态） ---
    private class SimBoard(val sz: Int) {
        val b = Array(sz) { IntArray(sz) }
        var capB = 0; var capW = 0
        fun copyFrom(src: Array<IntArray>, cb: Int, cw: Int) {
            for (i in 0 until sz) b[i] = src[i].copyOf(); capB = cb; capW = cw
        }
    }

    private fun simNeighbors(r: Int, c: Int): List<Pair<Int,Int>> {
        val lst = mutableListOf<Pair<Int,Int>>()
        if (r > 0) lst.add(Pair(r-1,c))
        if (r < boardSize-1) lst.add(Pair(r+1,c))
        if (c > 0) lst.add(Pair(r,c-1))
        if (c < boardSize-1) lst.add(Pair(r,c+1))
        return lst
    }

    private fun simGroup(sim: SimBoard, r: Int, c: Int): Set<Pair<Int,Int>> {
        val color = sim.b[r][c]; if (color == EMPTY) return emptySet()
        val g = mutableSetOf<Pair<Int,Int>>(); val stk = mutableListOf(Pair(r,c))
        val vis = Array(sim.sz) { BooleanArray(sim.sz) }; vis[r][c] = true
        while (stk.isNotEmpty()) { val (cr,cc) = stk.removeAt(stk.size-1); g.add(Pair(cr,cc))
            for ((nr,nc) in simNeighbors(cr,cc)) if (!vis[nr][nc] && sim.b[nr][nc]==color) { vis[nr][nc]=true; stk.add(Pair(nr,nc)) } }
        return g
    }

    private fun simLiberties(sim: SimBoard, group: Set<Pair<Int,Int>>): Int {
        val libs = mutableSetOf<Pair<Int,Int>>()
        for ((r,c) in group) for ((nr,nc) in simNeighbors(r,c)) if (sim.b[nr][nc]==EMPTY) libs.add(Pair(nr,nc))
        return libs.size
    }

    /** 在仿真棋盘上落子，返回 (成功, 提子数)，不修改游戏状态 */
    private fun simPlaceStone(sim: SimBoard, r: Int, c: Int, player: Int): Pair<Boolean,Int> {
        if (sim.b[r][c] != EMPTY) return Pair(false,0)
        val opp = if (player == PLAYER_BLACK) PLAYER_WHITE else PLAYER_BLACK
        sim.b[r][c] = player
        var caps = 0
        for ((nr,nc) in simNeighbors(r,c)) if (sim.b[nr][nc]==opp) {
            val g = simGroup(sim, nr, nc)
            if (simLiberties(sim, g)==0) { caps += g.size; for ((gr,gc) in g) sim.b[gr][gc]=EMPTY }
        }
        val myG = simGroup(sim, r, c)
        if (simLiberties(sim, myG)==0 && caps==0) { sim.b[r][c] = EMPTY; return Pair(false,0) }
        if (player == PLAYER_BLACK) sim.capB += caps else sim.capW += caps
        return Pair(true, caps)
    }

    /** 仿真棋盘上的快速领地计数 */
    private fun simTerrCnt(sim: SimBoard, who: Int): Int {
        var t=0; val d=Array(sim.sz){IntArray(sim.sz){999}}; val q=ArrayDeque<Triple<Int,Int,Int>>()
        for(r in 0 until sim.sz) for(c in 0 until sim.sz) if(sim.b[r][c]==who){q.add(Triple(r,c,0));d[r][c]=0}
        while(q.isNotEmpty()){val(r,c,dd)=q.removeFirst();if(dd>=5)continue
            for((nr,nc)in simNeighbors(r,c))if(d[nr][nc]>dd+1){d[nr][nc]=dd+1;q.add(Triple(nr,nc,dd+1))}}
        for(r in 0 until sim.sz) for(c in 0 until sim.sz) if(sim.b[r][c]==EMPTY&&d[r][c]<=4)t++; return t
    }

    /** 为 MCTS 生成候选落点（近石2格 + 打吃/提子优先） */
    private fun mctsCandidates(sim: SimBoard, player: Int): List<Pair<Int,Int>> {
        val opp = if (player == PLAYER_BLACK) PLAYER_WHITE else PLAYER_BLACK
        val near = mutableSetOf<Pair<Int,Int>>()
        for (r in 0 until sim.sz) for (c in 0 until sim.sz) if (sim.b[r][c] != EMPTY)
            for (dr in -2..2) for (dc in -2..2) { val nr=r+dr; val nc=c+dc
                if (nr in 0 until sim.sz && nc in 0 until sim.sz && sim.b[nr][nc]==EMPTY) near.add(Pair(nr,nc)) }
        if (near.isEmpty()) for (r in 0 until sim.sz) for (c in 0 until sim.sz) if (sim.b[r][c]==EMPTY) near.add(Pair(r,c))
        // 排序：提子/打吃优先 → 随机
        val lst = near.toList()
        return lst.sortedByDescending { (r,c) ->
            var prio = 0
            sim.b[r][c] = player
            for ((nr,nc) in simNeighbors(r,c)) if (sim.b[nr][nc]==opp) {
                val g = simGroup(sim, nr, nc); if (simLiberties(sim, g)==0) prio += g.size * 100
                else if (simLiberties(sim, g)==1) prio += 10
            }
            sim.b[r][c] = EMPTY
            prio
        }
    }

    // ==================== 落子推荐引擎 V3.4 - Alpha-Beta 多层搜索 ====================

    /** 工作副本：独立棋盘状态，绝不触及真实棋盘 */
    private class WorkBoard(val sz: Int) {
        val b: Array<IntArray> = Array(sz) { IntArray(sz) }
        var capB = 0; var capW = 0
        var koR = -1; var koC = -1

        fun snapFrom(src: GoGame) { for (i in 0 until sz) b[i] = src.board[i].copyOf(); capB = src.capturedByBlack; capW = src.capturedByWhite; koR = src.koRestrictedRow; koC = src.koRestrictedCol }
        fun neighbors(r: Int, c: Int): List<Pair<Int,Int>> {
            val l = mutableListOf<Pair<Int,Int>>(); if (r>0) l.add(Pair(r-1,c)); if (r<sz-1) l.add(Pair(r+1,c)); if (c>0) l.add(Pair(r,c-1)); if (c<sz-1) l.add(Pair(r,c+1)); return l }
        fun group(r: Int, c: Int): Set<Pair<Int,Int>> {
            val col = b[r][c]; if (col==EMPTY) return emptySet(); val g = mutableSetOf<Pair<Int,Int>>(); val stk=mutableListOf(Pair(r,c)); val vis=Array(sz){BooleanArray(sz)}; vis[r][c]=true
            while(stk.isNotEmpty()){val(cr,cc)=stk.removeAt(stk.size-1);g.add(Pair(cr,cc));for((nr,nc)in neighbors(cr,cc))if(!vis[nr][nc]&&b[nr][nc]==col){vis[nr][nc]=true;stk.add(Pair(nr,nc))}}; return g }
        fun liberties(g: Set<Pair<Int,Int>>): Int { val libs=mutableSetOf<Pair<Int,Int>>(); for((r,c)in g) for((nr,nc)in neighbors(r,c)) if(b[nr][nc]==EMPTY) libs.add(Pair(nr,nc)); return libs.size }
        fun terrCnt(who: Int): Int { var t=0; val d=Array(sz){IntArray(sz){999}}; val q=ArrayDeque<Triple<Int,Int,Int>>()
            for(r in 0 until sz) for(c in 0 until sz) if(b[r][c]==who){q.add(Triple(r,c,0));d[r][c]=0}
            while(q.isNotEmpty()){val(r,c,dd)=q.removeFirst();if(dd>=5)continue;for((nr,nc)in neighbors(r,c))if(d[nr][nc]>dd+1){d[nr][nc]=dd+1;q.add(Triple(nr,nc,dd+1))}}
            for(r in 0 until sz) for(c in 0 until sz) if(b[r][c]==EMPTY&&d[r][c]<=4)t++; return t }

        // --- 落子/撤销（高效，不拷贝全盘） ---
        data class UndoInfo(val capStones: List<Pair<Int,Int>>, val capColor: Int, val prevKoR: Int, val prevKoC: Int)

        fun tryPlace(r: Int, c: Int, player: Int): UndoInfo? {
            if (b[r][c] != EMPTY || (r==koR && c==koC)) return null
            val opp = if (player==PLAYER_BLACK) PLAYER_WHITE else PLAYER_BLACK
            b[r][c] = player
            val caps = mutableListOf<Pair<Int,Int>>()
            for ((nr,nc) in neighbors(r,c)) if (b[nr][nc]==opp) { val g=group(nr,nc); if (liberties(g)==0) { caps.addAll(g); for ((gr,gc) in g) b[gr][gc]=EMPTY } }
            if (liberties(group(r,c))==0 && caps.isEmpty()) { b[r][c]=EMPTY; return null }
            if (player==PLAYER_BLACK) capB+=caps.size else capW+=caps.size
            val pkr=koR; val pkc=koC
            if (caps.size==1 && liberties(group(r,c))==1) { koR=caps[0].first; koC=caps[0].second } else { koR=-1; koC=-1 }
            return UndoInfo(caps, opp, pkr, pkc)
        }

        fun undoPlace(r: Int, c: Int, player: Int, info: UndoInfo) {
            if (player==PLAYER_BLACK) capB-=info.capStones.size else capW-=info.capStones.size
            b[r][c] = EMPTY; for ((cr,cc) in info.capStones) b[cr][cc]=info.capColor
            koR=info.prevKoR; koC=info.prevKoC
        }
    }

    /** 叶节点评估：领地+提子+贴目，正数=player有利 */
    private fun evalLeaf(wb: WorkBoard, player: Int): Double {
        val opp = if (player==PLAYER_BLACK) PLAYER_WHITE else PLAYER_BLACK
        val myT=wb.terrCnt(player); val oppT=wb.terrCnt(opp)
        val myC=if(player==PLAYER_BLACK) wb.capB else wb.capW
        val oppC=if(player==PLAYER_BLACK) wb.capW else wb.capB
        val komi=if(player==PLAYER_BLACK) KOMI else 0.0
        return (myT+myC) - (oppT+oppC+komi)
    }

    /** 生成排序后的候选（打吃/提子优先，大幅提升剪枝效率） */
    private fun genOrderedMoves(wb: WorkBoard, player: Int): List<Pair<Int,Int>> {
        val opp=if(player==PLAYER_BLACK) PLAYER_WHITE else PLAYER_BLACK
        val set=linkedSetOf<Pair<Int,Int>>()
        // 1. 能提子
        for(r in 0 until wb.sz) for(c in 0 until wb.sz) if(wb.b[r][c]==opp){val g=wb.group(r,c);if(wb.liberties(g)==1)for((gr,gc)in g)for((nr,nc)in wb.neighbors(gr,gc))if(wb.b[nr][nc]==EMPTY)set.add(Pair(nr,nc))}
        // 2. 能救自己1气棋
        for(r in 0 until wb.sz) for(c in 0 until wb.sz) if(wb.b[r][c]==player){val g=wb.group(r,c);if(wb.liberties(g)==1)for((gr,gc)in g)for((nr,nc)in wb.neighbors(gr,gc))if(wb.b[nr][nc]==EMPTY)set.add(Pair(nr,nc))}
        // 3. 近石2格
        for(r in 0 until wb.sz) for(c in 0 until wb.sz) if(wb.b[r][c]!=EMPTY)for(dr in -2..2)for(dc in -2..2){val nr=r+dr;val nc=c+dc;if(nr in 0 until wb.sz&&nc in 0 until wb.sz&&wb.b[nr][nc]==EMPTY)set.add(Pair(nr,nc))}
        if(set.isEmpty()) for(r in 0 until wb.sz) for(c in 0 until wb.sz) if(wb.b[r][c]==EMPTY)set.add(Pair(r,c))
        // 按紧迫度排序
        return set.toList().sortedByDescending{(r,c)->
            var p=0; wb.b[r][c]=player
            for((nr,nc)in wb.neighbors(r,c)){if(wb.b[nr][nc]==opp){val g=wb.group(nr,nc);val l=wb.liberties(g);if(l==0)p+=g.size*200;else if(l==1)p+=g.size*40}else if(wb.b[nr][nc]==player){val g=wb.group(nr,nc);if(wb.liberties(g)<=2)p+=g.size*30}}
            wb.b[r][c]=EMPTY; p
        }
    }

    /** Alpha-Beta 搜索（带迭代加深） */
    private fun alphaBeta(wb: WorkBoard, depth: Int, alpha: Double, beta: Double, player: Int, rootPlayer: Int): Double {
        if (depth <= 0) return evalLeaf(wb, rootPlayer)
        val opp = if (player==PLAYER_BLACK) PLAYER_WHITE else PLAYER_BLACK
        val moves = genOrderedMoves(wb, player)
        if (moves.isEmpty()) return evalLeaf(wb, rootPlayer)
        val maximizing = (player == rootPlayer)

        if (maximizing) {
            var a = alpha
            for ((r,c) in moves) {
                val undo = wb.tryPlace(r, c, player) ?: continue
                val v = alphaBeta(wb, depth-1, a, beta, opp, rootPlayer)
                wb.undoPlace(r, c, player, undo)
                if (v > a) a = v; if (a >= beta) return a
            }
            return a
        } else {
            var b = beta
            for ((r,c) in moves) {
                val undo = wb.tryPlace(r, c, player) ?: continue
                val v = alphaBeta(wb, depth-1, alpha, b, opp, rootPlayer)
                wb.undoPlace(r, c, player, undo)
                if (v < b) b = v; if (alpha >= b) return b
            }
            return b
        }
    }

    fun suggestMove(player: Int): Triple<Int, Int, String>? {
        if (!isActive || isGameOver) return null

        val kg = GameState.kataGoEngine
        if (kg != null && kg.isReady) {
            // V9.3: 等待 loadBoardSize 完成
            var waited = 0
            while (kg.boardLoading && waited < 15000) {
                Thread.sleep(100); waited += 100
            }
            // V10.2: 让子对局 genmove 前确保首次同步完成
            if (handicapStones >= 2 && !GameState.initialSyncDone) {
                // 等待 asyncSyncKataGoBoard 完成（最多 5s，通常 <1s）
                var syncWait = 0
                while (!GameState.initialSyncDone && syncWait < 5000) {
                    Thread.sleep(50); syncWait += 50
                }
                // 超时或同步标志仍未设置 → 手动同步兜底
                if (!GameState.initialSyncDone) {
                    try {
                        kg.clearBoard()
                        kg.setKomi(KOMI.toFloat())
                        var cnt = 0
                        for (r in 0 until boardSize) for (c in 0 until boardSize) {
                            if (board[r][c] != EMPTY) {
                                val clr = if (board[r][c] == PLAYER_BLACK) "b" else "w"
                                kg.playMove(clr, r, c); cnt++
                            }
                        }
                        GameState.initialSyncDone = true
                        android.util.Log.i("GoGame", "Pre-genmove fallback sync: $cnt stones")
                    } catch (e: Exception) {
                        android.util.Log.e("GoGame", "Pre-genmove sync failed: ${e.message}")
                    }
                }
            }
            val color = if (player == PLAYER_BLACK) "b" else "w"
            val startMs = System.currentTimeMillis()
            // V10.3: 全路径诊断日志 — 每一步都记录
            android.util.Log.i("GoGame", "suggestMove: start player=$player handicap=$handicapStones boardSize=$boardSize")
            var retries = 0
            while (retries < 3) {
                android.util.Log.i("GoGame", "suggestMove: genMove attempt ${retries+1}/3...")
                val result = kg.genMove(color)
                if (result != null) {
                    val (row, col, detail) = result
                    android.util.Log.i("GoGame", "suggestMove: genMove returned ($row,$col) board[$row][$col]=${board[row][col]} isEmpty=${board[row][col]==EMPTY}")
                    if (row in 0 until boardSize && col in 0 until boardSize && board[row][col] == EMPTY) {
                        // V10.2: 快速自杀检测 — 模拟落子看是否禁入点
                        board[row][col] = player
                        val opp = if (player == PLAYER_BLACK) PLAYER_WHITE else PLAYER_BLACK
                        var capturesAny = false
                        for ((nr, nc) in getNeighbors(row, col)) {
                            if (board[nr][nc] == opp && countLiberties(getGroup(nr, nc)) == 0) {
                                capturesAny = true; break
                            }
                        }
                        val myLibs = countLiberties(getGroup(row, col))
                        val isSuicide = !capturesAny && myLibs == 0
                        board[row][col] = EMPTY  // 恢复
                        android.util.Log.i("GoGame", "suggestMove: suicide check capturesAny=$capturesAny myLibs=$myLibs isSuicide=$isSuicide")
                        if (isSuicide) {
                            android.util.Log.w("GoGame", "genmove suicide detected at ($row,$col), retry ${retries+1}/3 — calling undo...")
                            kg.undoMove()
                            android.util.Log.i("GoGame", "undoMove done, retrying genmove...")
                            retries++
                            continue
                        }
                        val usedMs = System.currentTimeMillis() - startMs
                        val myT=terrCntFast(player); val oppT=terrCntFast(opp)
                        val myC=if(player==PLAYER_BLACK)capturedByBlack else capturedByWhite
                        val oppC=if(player==PLAYER_BLACK)capturedByWhite else capturedByBlack
                        val lead=(myT+myC)-(oppT+oppC+KOMI)
                        val emoji=when{lead>8->"▲";lead>2->"●";lead>-2->"·";lead>-8->"○";else->"△"}
                        val szLabel=when(boardSize){9->"9路";13->"13路";else->""}
                        val infoStr = "🤖 KataGo ${usedMs}ms $szLabel | $emoji${"%.0f".format(Math.abs(lead))}目"
                        val fullStr = if (detail.isNotEmpty()) "$infoStr\n$detail" else infoStr
                        android.util.Log.i("GoGame", "suggestMove: SUCCESS returning ($row,$col)")
                        return Triple(row, col, fullStr)
                    } else {
                        // 位置被占 → 重试
                        android.util.Log.w("GoGame", "genmove OCCUPIED ($row,$col) board[$row][$col]=${board[row][col]}, retry ${retries+1}/3 — calling undo...")
                        kg.undoMove()
                        android.util.Log.i("GoGame", "undoMove done after occupied")
                        retries++
                        continue
                    }
                } else {
                    android.util.Log.w("GoGame", "suggestMove: genMove returned NULL (pass/resign), breaking out")
                }
                break  // genMove 返回 null → 不再重试
            }
            android.util.Log.w("GoGame", "suggestMove: exhausted retries or null, returning null")
        }
        return null
    }

    /** V5.2: 导出 GoGame 棋盘字符串（用于与 KataGo showboard 对比） */
    fun dumpBoard(): String {
        val sb = StringBuilder()
        val turn = if (currentPlayer == PLAYER_BLACK) "B" else "W"
        sb.appendLine("=== GoGame Board " + boardSize + "x" + boardSize + " move " + totalMoves + " " + turn + " to play ===")
        sb.append("   ")
        for (c in 0 until boardSize) {
            // GTP 列名跳过 I
            val colChar = if (c >= 8) ('A' + c + 1) else ('A' + c)
            sb.append(colChar.toString().padStart(2))
        }
        sb.appendLine()
        for (r in 0 until boardSize) {
            sb.append((boardSize - r).toString().padStart(2) + " ")
            for (c in 0 until boardSize) {
                sb.append(when (board[r][c]) {
                    PLAYER_BLACK -> "X "
                    PLAYER_WHITE -> "O "
                    else -> ". "
                })
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    // ==================== 游戏控制 ====================

    /** V8.0: 让子数 (0=无让子, 2-9) */
    var handicapStones = 0
        private set

    fun setHandicap(n: Int) {
        if (isActive) return
        handicapStones = n.coerceIn(0, 9)
    }

    fun startGame() {
        restart()
        // V8.1: 让子处理 (2-9子)
        if (handicapStones >= 2) {
            placeHandicapStones()
            currentPlayer = PLAYER_WHITE  // 让子后白方先行
        } else {
            currentPlayer = PLAYER_BLACK
        }
        isActive = true
    }

    /** V8.0: 按标准星位放置让子 */
    private fun placeHandicapStones() {
        val stars = getStarPoints()
        // 标准让子顺序 (围棋规则)
        val order = when (boardSize) {
            19 -> when (handicapStones) {
                2 -> listOf(0, 2)       // 对角星
                3 -> listOf(0, 2, 4)    // 对角+天元
                4 -> listOf(0, 2, 6, 8) // 四角星
                5 -> listOf(0, 2, 4, 6, 8) // 四角+天元
                6 -> listOf(0, 2, 3, 5, 6, 8) // 六星
                7 -> listOf(0, 2, 3, 4, 5, 6, 8)
                8 -> listOf(0, 1, 2, 3, 5, 6, 7, 8)
                else -> (0 until handicapStones).toList()
            }
            13 -> when (handicapStones) {
                2 -> listOf(0, 2)
                3 -> listOf(0, 2, 4)
                4 -> listOf(0, 2, 6, 8)
                5 -> listOf(0, 2, 4, 6, 8)
                else -> (0 until minOf(handicapStones, stars.size)).toList()
            }
            9 -> when (handicapStones) {
                2 -> listOf(0, 2)
                3 -> listOf(0, 2, 4)
                4 -> listOf(0, 2, 6, 8)
                5 -> listOf(0, 2, 4, 6, 8)
                else -> (0 until minOf(handicapStones, stars.size)).toList()
            }
            else -> (0 until minOf(handicapStones, stars.size)).toList()
        }
        for (idx in order) {
            if (idx < stars.size) {
                val (r, c) = Pair(stars[idx][0], stars[idx][1])
                if (board[r][c] == EMPTY) {
                    board[r][c] = PLAYER_BLACK
                    totalMoves++
                    blackMoves++
                    pieceOrder[r][c] = blackMoves
                    lastRow = r; lastCol = c
                    moveHistory.add(MoveRecord(PLAYER_BLACK, r, c, emptyList(), emptyMap(),
                        -1, -1, 0, 0, 0))
                }
            }
        }
    }

    fun restart() {
        initBoards()
        currentPlayer = PLAYER_BLACK; isGameOver = false; isActive = false; winner = EMPTY
        isPaused = false; pausedByPlayer = EMPTY
        pauseCountBlack = 1; pauseCountWhite = 1
        lastRow = -1; lastCol = -1
        capturedByBlack = 0; capturedByWhite = 0
        consecutivePasses = 0
        koRestrictedRow = -1; koRestrictedCol = -1
        blackTerritory = 0; whiteTerritory = 0
        totalMoves = 0
        blackMoves = 0; whiteMoves = 0  // V6.2: 重新开始后棋子序号复位
        moveHistory.clear()
        gameOverReason = ""
    }

    fun getCurrentPlayerName() = if (currentPlayer == PLAYER_BLACK) "黑方" else "白方"
    fun getPlayerName(player: Int) = when (player) {
        PLAYER_BLACK -> "黑方"; PLAYER_WHITE -> "白方"; else -> ""
    }
    fun getDifficultyLabel() = when (boardSize) {
        9 -> "初级 9×9"; 13 -> "中级 13×13"; else -> "高级 19×19"
    }
}

data class PlaceResult(
    val success: Boolean,
    val message: String,
    val gameOver: Boolean = false,
    val winner: Int = GoGame.EMPTY,
    val captures: Int = 0,
    val capturedStones: List<Pair<Int, Int>> = emptyList()  // V9.1: 被提子位置
)

data class UndoResult(
    val success: Boolean,
    val message: String,
    val undoCount: Int = 0
)
