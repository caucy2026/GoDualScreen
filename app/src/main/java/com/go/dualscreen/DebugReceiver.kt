package com.go.dualscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * V10.3: adb 广播触发状态保存
 * 用法: adb shell am broadcast -n com.go.dualscreen/.DebugReceiver -a com.go.dualscreen.DEBUG_DUMP
 * 不破坏现场，只保存数据和日志到文件
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        Log.w("GoGame", "========================================")
        Log.w("GoGame", "DEBUG_DUMP received! Saving game state...")
        Log.w("GoGame", "========================================")
        try {
            val dir = File(ctx.filesDir, "debug_dumps")
            dir.mkdirs()
            val ts = System.currentTimeMillis()
            
            // 1. GoGame 棋盘
            try {
                val g = GameState.game
                File(dir, "board_${ts}.txt").writeText(g.dumpBoard())
                Log.w("GoGame", "Board dumped")
                // 3. 录像
                val rec = RecordingManager.currentRecording
                if (rec != null && g.isActive) {
                    File(dir, "recording_${ts}.json").writeText(rec.toJson())
                    Log.w("GoGame", "Recording saved (${rec.moves.size} moves)")
                }
                // 4. moveHistory
                val sb = StringBuilder()
                for ((i, m) in g.debugMoveHistory.withIndex()) {
                    sb.appendLine("$i: ${if(m.player==GoGame.PLAYER_BLACK)"B" else "W"} (${m.row},${m.col})")
                }
                File(dir, "movehistory_${ts}.txt").writeText(sb.toString())
                Log.w("GoGame", "Move history dumped (${g.debugMoveHistory.size} moves)")
            } catch (e: Exception) {
                Log.w("GoGame", "GoGame state dump failed: ${e.message}")
            }
            
            // 2. KataGo 内部棋盘
            try {
                val kg = GameState.kataGoEngine
                if (kg?.isReady == true) {
                    File(dir, "katago_${ts}.txt").writeText(kg.showboard())
                    Log.w("GoGame", "KataGo board dumped")
                }
            } catch (e: Exception) {
                Log.w("GoGame", "KataGo dump failed: ${e.message}")
            }
            
            Log.w("GoGame", "DEBUG_DUMP done: ${dir.absolutePath}")
        } catch (e: Exception) {
            Log.w("GoGame", "DEBUG_DUMP failed: ${e.message}")
        }
    }
}
