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

    init { initBoards() }

    private fun initBoards() {
        board = Array(boardSize) { IntArray(boardSize) { EMPTY } }
        pieceOrder = Array(boardSize) { IntArray(boardSize) { 0 } }
    }

    fun setBoardSize(size: Int) {
        if (isActive) return
        boardSize = size
        restart()
    }

    data class MoveRecord(
        val row: Int, val col: Int, val player: Int,
        val capturedStones: List<Pair<Int, Int>>,
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
        for ((nr, nc) in getNeighbors(row, col)) {
            if (board[nr][nc] == opponent) {
                val group = getGroup(nr, nc)
                if (countLiberties(group) == 0) {
                    for ((gr, gc) in group) {
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
        var cnt = 0
        for (r in 0 until boardSize) for (c in 0 until boardSize) if (board[r][c] == player) cnt++
        pieceOrder[row][col] = cnt
        lastRow = row; lastCol = col

        moveHistory.add(MoveRecord(row, col, player, captured,
            prevKoRow, prevKoCol, 0,
            if (player == PLAYER_BLACK) capturedByBlack - capCount else capturedByBlack,
            if (player == PLAYER_WHITE) capturedByWhite - capCount else capturedByWhite))

        currentPlayer = opponent
        val nextName = if (currentPlayer == PLAYER_BLACK) "黑方" else "白方"
        val capMsg = if (capCount > 0) "，提${capCount}子" else ""
        return PlaceResult(true, "轮到${nextName}落子$capMsg", captures = capCount)
    }

    // ==================== Pass ====================

    fun pass(player: Int): PlaceResult {
        if (!isActive) return PlaceResult(false, "请先点击「开始」开始对局")
        if (isGameOver) return PlaceResult(false, "对局已结束，请重新开始")
        if (player != currentPlayer) {
            return PlaceResult(false, if (currentPlayer == PLAYER_BLACK) "现在轮到黑方操作，请等待" else "现在轮到白方操作，请等待")
        }
        consecutivePasses++
        moveHistory.add(MoveRecord(-1, -1, player, emptyList(),
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
                for ((cr, cc) in op.capturedStones) board[cr][cc] = if (op.player == PLAYER_BLACK) PLAYER_WHITE else PLAYER_BLACK
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
            for ((cr, cc) in my.capturedStones) board[cr][cc] = if (my.player == PLAYER_BLACK) PLAYER_WHITE else PLAYER_BLACK
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
        val startMs = System.currentTimeMillis(); val deadline = startMs + 3000L
        val opp = if (player==PLAYER_BLACK) PLAYER_WHITE else PLAYER_BLACK

        // === 开局 <4手：星位/角部 ===
        if (totalMoves < 4) {
            for ((sr,sc) in getStarPoints()) if (board[sr][sc]==EMPTY) { val cl=('A'+sc.coerceIn(0,25)); val rl=boardSize-sr; return Triple(sr,sc,"$cl$rl 星位 | 开局定式") }
            val cor=listOf(Pair(0,0),Pair(0,boardSize-1),Pair(boardSize-1,0),Pair(boardSize-1,boardSize-1))
            for ((cr,cc) in cor) if (board[cr][cc]==EMPTY) { val cl=('A'+cc.coerceIn(0,25)); val rl=boardSize-cr; return Triple(cr,cc,"$cl$rl 占角 | 开局定式") }
        }

        // === 根节点候选 ===
        val rootWb = WorkBoard(boardSize); rootWb.snapFrom(this)
        val rootMoves = genOrderedMoves(rootWb, player)
        if (rootMoves.isEmpty()) return null
        // 限制候选（开局多，后期少）
        val maxCands = when { totalMoves<12 -> 30; totalMoves<30 -> 45; else -> 60 }
        val topMoves = rootMoves.take(maxCands)

        // === 多核并行：每线程用独立 WorkBoard 评估自己的候选子集 ===
        val numThreads = maxOf(1, minOf(Runtime.getRuntime().availableProcessors(), 7))
        val bestAtomic = java.util.concurrent.atomic.AtomicReference<Pair<Pair<Int,Int>, Double>>()
        val latch = java.util.concurrent.CountDownLatch(numThreads)
        val chunk = (topMoves.size + numThreads - 1) / numThreads
        var searchDepth = 0

        repeat(numThreads) { t ->
            Thread {
                try {
                    val wb = WorkBoard(boardSize); wb.snapFrom(this@GoGame)
                    val st=t*chunk; val ed=minOf(st+chunk, topMoves.size)
                    var localR=-1; var localC=-1; var localS=Double.NEGATIVE_INFINITY
                    // 迭代加深：先搜depth=2，时间充裕再搜depth=3
                    for (d in 2..3) {
                        if (System.currentTimeMillis() > deadline) break
                        searchDepth = d
                        for (i in st until ed) {
                            if (System.currentTimeMillis() > deadline) break
                            val (r,c)=topMoves[i]
                            val undo=wb.tryPlace(r,c,player) ?: continue
                            val v=alphaBeta(wb, d-1, -1e9, 1e9, opp, player)
                            wb.undoPlace(r,c,player,undo)
                            if (v>localS) { localS=v; localR=r; localC=c }
                        }
                    }
                    if (localR>=0) { while(true){val cur=bestAtomic.get();if(cur!=null&&cur.second>=localS)break;if(bestAtomic.compareAndSet(cur,Pair(Pair(localR,localC),localS)))break} }
                } catch (_: Exception) {}
                latch.countDown()
            }.start()
        }
        try { latch.await(3500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}

        val best = bestAtomic.get() ?: return null
        val (row,col) = best.first; val usedMs = System.currentTimeMillis()-startMs
        val cl=('A'+col.coerceIn(0,25)); val rl=boardSize-row

        val myT=terrCntFast(player); val oppT=terrCntFast(opp)
        val myC=if(player==PLAYER_BLACK)capturedByBlack else capturedByWhite
        val oppC=if(player==PLAYER_BLACK)capturedByWhite else capturedByBlack
        val lead=(myT+myC)-(oppT+oppC+KOMI)
        val emoji=when{lead>8->"△";lead>2->"○";lead>-2->"=";lead>-8->"·";else->"▽"}
        val szLabel=when(boardSize){9->"9路";13->"13路";else->""}
        val detail="$cl$rl | 深度${searchDepth} ${usedMs}ms $szLabel | $emoji${"%.0f".format(Math.abs(lead))}目"
        return Triple(row,col,detail)
    }

    // ==================== 游戏控制 ====================

    fun startGame() { restart(); currentPlayer = PLAYER_BLACK; isActive = true }

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
    val captures: Int = 0
)

data class UndoResult(
    val success: Boolean,
    val message: String,
    val undoCount: Int = 0
)
