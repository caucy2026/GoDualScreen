package com.go.dualscreen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 录像数据模型
 * 保存落子顺序、时间、位置，支持完美回放
 */
data class MoveEntry(
    val player: Int,       // GoGame.PLAYER_BLACK / PLAYER_WHITE
    val row: Int,          // -1=Pass, -2=Undo
    val col: Int,          // -1=Pass, -2=Undo
    val timeMs: Long,      // 从对局开始到落子的毫秒数
    val captures: Int,     // 本次落子提子数
    val capturedStones: List<Pair<Int, Int>>,  // 被提棋子位置
    val isUndo: Boolean = false,  // V9.6: 悔棋标记
    val undoCount: Int = 0        // V9.6: 悔棋步数
)

data class RecordingData(
    val boardSize: Int,
    val handicap: Int,
    val date: String,
    val moves: MutableList<MoveEntry> = mutableListOf()
) {
    fun toJson(): String = JSONObject().apply {
        put("boardSize", boardSize)
        put("handicap", handicap)
        put("date", date)
        put("moves", JSONArray().apply {
            for (m in moves) {
                put(JSONObject().apply {
                    put("p", m.player)
                    put("r", m.row)
                    put("c", m.col)
                    put("t", m.timeMs)
                    put("caps", m.captures)
                    if (m.isUndo) { put("undo", true); put("undoN", m.undoCount) }
                    put("capStones", JSONArray().apply {
                        for ((cr, cc) in m.capturedStones) {
                            put(JSONObject().apply { put("r", cr); put("c", cc) })
                        }
                    })
                })
            }
        })
    }.toString(2)

    companion object {
        fun fromJson(json: String): RecordingData {
            val obj = JSONObject(json)
            val moves = mutableListOf<MoveEntry>()
            val arr = obj.getJSONArray("moves")
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val caps = JSONArray()
                try {
                    val cs = m.getJSONArray("capStones")
                    for (j in 0 until cs.length()) {
                        val c = cs.getJSONObject(j)
                        caps.put(c)
                    }
                } catch (_: Exception) {}
                val capList = mutableListOf<Pair<Int, Int>>()
                for (j in 0 until caps.length()) {
                    val c = caps.getJSONObject(j)
                    capList.add(Pair(c.getInt("r"), c.getInt("c")))
                }
                moves.add(MoveEntry(
                    player = m.getInt("p"),
                    row = m.getInt("r"),
                    col = m.getInt("c"),
                    timeMs = m.optLong("t", 0),
                    captures = m.optInt("caps", 0),
                    capturedStones = capList,
                    isUndo = m.optBoolean("undo", false),
                    undoCount = m.optInt("undoN", 0)
                ))
            }
            return RecordingData(
                boardSize = obj.getInt("boardSize"),
                handicap = obj.optInt("handicap", 0),
                date = obj.optString("date", ""),
                moves = moves
            )
        }
    }
}

/**
 * 录像管理器
 */
object RecordingManager {
    private const val DIR = "recordings"
    private val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

    /** 当前正在录制的录像（null=未在录制） */
    var currentRecording: RecordingData? = null
    /** 对局开始时间（用于计算相对时间） */
    private var gameStartTime = 0L

    fun startRecording(boardSize: Int, handicap: Int) {
        currentRecording = RecordingData(
            boardSize = boardSize,
            handicap = handicap,
            date = sdf.format(Date())
        )
        gameStartTime = System.currentTimeMillis()
    }

    fun recordMove(player: Int, row: Int, col: Int, captures: Int = 0, capturedStones: List<Pair<Int, Int>> = emptyList()) {
        currentRecording?.moves?.add(MoveEntry(
            player = player,
            row = row,
            col = col,
            timeMs = System.currentTimeMillis() - gameStartTime,
            captures = captures,
            capturedStones = capturedStones
        ))
    }

    fun recordPass(player: Int) {
        currentRecording?.moves?.add(MoveEntry(
            player = player,
            row = -1,
            col = -1,
            timeMs = System.currentTimeMillis() - gameStartTime,
            captures = 0,
            capturedStones = emptyList()
        ))
    }

    /** V9.6: 记录悔棋操作 */
    fun recordUndo(player: Int, undoCount: Int) {
        currentRecording?.moves?.add(MoveEntry(
            player = player,
            row = -2, col = -2,
            timeMs = System.currentTimeMillis() - gameStartTime,
            captures = 0,
            capturedStones = emptyList(),
            isUndo = true,
            undoCount = undoCount
        ))
    }

    fun finishRecording(context: Context): String? {
        val rec = currentRecording ?: return null
        currentRecording = null
        // V9.0: 空录像不保存
        if (rec.moves.isEmpty()) return null
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        val filename = "${rec.date}_${rec.boardSize}x${rec.boardSize}_h${rec.handicap}.json"
        val file = File(dir, filename)
        file.writeText(rec.toJson())
        return file.absolutePath
    }

    fun cancelRecording() {
        currentRecording = null
    }

    fun listRecordings(context: Context): List<File> {
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun loadRecording(file: File): RecordingData? {
        return try {
            RecordingData.fromJson(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun deleteRecording(file: File): Boolean = file.delete()

    /** 获取录像显示名称 */
    fun getDisplayName(file: File): String {
        val name = file.nameWithoutExtension
        return name.replace("_", " ")
    }

    /**
     * V8.8: 复盘验证录像 — 在独立棋盘上逐手回放，检测游戏逻辑问题
     * @return 问题列表，空列表表示录像逻辑正确
     */
    fun validateRecording(data: RecordingData): List<String> {
        val issues = mutableListOf<String>()
        android.util.Log.i("GoGame", "=== 复盘验证开始: ${data.boardSize}x${data.boardSize} 让${data.handicap}子 共${data.moves.size}手 ===")
        val g = GoGame(data.boardSize)
        if (data.handicap > 0) {
            g.setHandicap(data.handicap)
        }
        g.startGame()

        for ((i, move) in data.moves.withIndex()) {
            val step = i + 1
            val who = if (move.player == GoGame.PLAYER_BLACK) "黑" else "白"
            // V9.6: 悔棋标记 → 在验证棋盘上执行回滚
            if (move.isUndo) {
                g.undo(move.player)
                continue
            }
            val curWho = if (g.currentPlayer == GoGame.PLAYER_BLACK) "黑" else "白"
            if (move.row >= 0) {
                // 检查落子合法性
                if (g.currentPlayer != move.player) {
                    val msg = "第${step}手: 轮到${curWho}方，但录像记录${who}方落子"
                    issues.add(msg)
                    android.util.Log.w("GoGame", "  ⚠️ $msg")
                }
                if (move.row < 0 || move.row >= data.boardSize || move.col < 0 || move.col >= data.boardSize) {
                    val msg = "第${step}手: ${who}方落子(${move.row},${move.col})超出棋盘范围"
                    issues.add(msg)
                    android.util.Log.w("GoGame", "  ⚠️ $msg")
                    continue
                }
                if (g.board[move.row][move.col] != GoGame.EMPTY) {
                    val msg = "第${step}手: ${who}方落子(${move.row},${move.col})位置已有棋子"
                    issues.add(msg)
                    android.util.Log.w("GoGame", "  ⚠️ $msg")
                    continue
                }
                val r = g.placePiece(move.row, move.col, move.player)
                if (!r.success) {
                    val msg = "第${step}手: ${who}方落子(${move.row},${move.col})违规 — ${r.message}"
                    issues.add(msg)
                    android.util.Log.w("GoGame", "  ⚠️ $msg")
                } else {
                    // V9.1: 验证提子数是否与录像一致
                    if (r.captures != move.captures) {
                        val msg = "第${step}手: ${who}方落子(${move.row},${move.col})录像记录提${move.captures}子，验证复盘实际提${r.captures}子"
                        issues.add(msg)
                        android.util.Log.w("GoGame", "  ⚠️ $msg")
                    }
                    if (r.captures > 0) {
                        android.util.Log.i("GoGame", "  第${step}手 ${who}提${r.captures}子, 记录=${move.captures}")
                    }
                }
            } else {
                // 虚手
                if (g.currentPlayer != move.player) {
                    val msg = "第${step}手: 轮到${curWho}方，但录像记录${who}方虚手"
                    issues.add(msg)
                    android.util.Log.w("GoGame", "  ⚠️ $msg")
                }
                g.pass(move.player)
            }
        }
        android.util.Log.i("GoGame", "=== 复盘验证结束: ${if(issues.isEmpty())"✅ 正确" else "⚠️ ${issues.size}个问题"} ===")
        for (issue in issues) android.util.Log.w("GoGame", "  $issue")
        return issues
    }
}
