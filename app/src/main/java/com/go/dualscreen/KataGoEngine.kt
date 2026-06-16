package com.go.dualscreen

import android.content.Context
import android.util.Log
import java.io.*

/**
 * KataGo GTP 引擎封装 V5
 * 从 assets/katago/ 提取所有资源 + 复制设备 GPU 驱动 + 进度回调
 */
class KataGoEngine(private val context: Context) {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    @Volatile var isReady = false
    @Volatile var engineInfo: String = ""
    @Volatile var lastError: String = ""
    // ★ V4.6: GTP 管道锁，防止多线程命令串扰
    private val gtpLock = Any()
    private var restartCallback: ((Boolean, String) -> Unit)? = null
    private var progressCallback: ((String) -> Unit)? = null
    // ★ V4.8: 动态棋盘大小
    @Volatile var currentBoardSize = 13
    // ★ V5.5: 模型加载状态
    @Volatile var boardLoading = false
    @Volatile var boardSizeReady = true

    /** 检查进程是否存活 */
    fun isProcessAlive(): Boolean = process?.isAlive == true

    /** 初始化（带进度回调） */
    fun init(onProgress: (String) -> Unit, onReady: (Boolean, String) -> Unit) {
        progressCallback = onProgress
        restartCallback = onReady
        Thread {
            try {
                val engineDir = File(context.filesDir, "katago")
                engineDir.mkdirs()
                onProgress("📦 提取引擎文件...")

                // 解压基础文件 (katago, libc++, cfg)
                // V5.4: gtp.cfg 强制每次更新（防止旧配置残留导致参数不生效）
                for (name in listOf("katago", "libc++_shared.so", "gtp.cfg")) {
                    val f = File(engineDir, name)
                    val needsExtract = when {
                        name == "gtp.cfg" -> true  // 始终覆盖配置文件
                        name == "katago" -> !f.exists() || f.length() < 100000
                        else -> !f.exists()
                    }
                    if (needsExtract) {
                        Log.i("KataGo", "Extracting $name...")
                        context.assets.open("katago/$name").use { input ->
                            FileOutputStream(f).use { out -> input.copyTo(out) }
                        }
                        if (name == "katago") f.setExecutable(true)
                    }
                }

                // 模型文件: Android aapt 自动解压 .gz → 实际名叫 model.bin
                val modelFile = File(engineDir, "model.bin")
                if (!modelFile.exists()) {
                    Log.i("KataGo", "Extracting model...")
                    onProgress("🧠 加载神经网络模型 (105MB)...")
                    try {
                        context.assets.open("katago/model.bin").use { input ->
                            FileOutputStream(modelFile).use { out -> input.copyTo(out) }
                        }
                    } catch (e1: Exception) {
                        // 尝试 .gz 后缀
                        try {
                            context.assets.open("katago/model.bin.gz").use { input ->
                                FileOutputStream(modelFile).use { out -> input.copyTo(out) }
                            }
                        } catch (e2: Exception) {
                            Log.e("KataGo", "Model not found in assets!")
                            onReady(false, "model file missing"); return@Thread
                        }
                    }
                }
                Log.i("KataGo", "Model ready: ${modelFile.length()} bytes")

                // ★ V5: 复制设备真实 OpenCL GPU 驱动到引擎目录（绕过 Android linker namespace 限制）
                onProgress("🎮 复制 GPU 驱动...")
                val realOpenCL = File("/vendor/lib64/libOpenCL.so")
                val realGLES = File("/vendor/lib64/egl/libGLES_mali.so")
                val localOpenCL = File(engineDir, "libOpenCL.so")
                val localGLES = File(engineDir, "libGLES_mali.so")
                if (!localOpenCL.exists() && realOpenCL.exists()) {
                    Log.i("KataGo", "Copying real libOpenCL.so from /vendor...")
                    realOpenCL.copyTo(localOpenCL)
                }
                if (!localGLES.exists() && realGLES.exists()) {
                    Log.i("KataGo", "Copying real libGLES_mali.so from /vendor/egl...")
                    realGLES.copyTo(localGLES)
                }
                Log.i("KataGo", "OpenCL driver: localOpenCL=${localOpenCL.length()}, localGLES=${localGLES.length()}")

                // ★ V4.6: 预创建默认调优文件，跳过 120s 自动调优
                onProgress("📝 写入标准调优参数...")
                createDefaultTuningFile(engineDir)

                // 启动 GTP 进程
                val exeFile = File(engineDir, "katago")
                val cfgFile = File(engineDir, "gtp.cfg")
                Log.i("KataGo", "Launching katago GTP...")
                onProgress("🚀 启动 KataGo GTP 引擎...")
                val pb = ProcessBuilder(exeFile.absolutePath, "gtp",
                    "-model", modelFile.absolutePath,
                    "-config", cfgFile.absolutePath)
                pb.directory(engineDir)
                // 真实 OpenCL 驱动已复制到 engineDir，LD_LIBRARY_PATH 让 linker 能找到它
                pb.environment()["LD_LIBRARY_PATH"] = engineDir.absolutePath
                pb.redirectErrorStream(true)
                process = pb.start()
                writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
                reader = BufferedReader(InputStreamReader(process!!.inputStream))

                // 等待 "GTP ready"
                val sb = StringBuilder()
                val deadline = System.currentTimeMillis() + 120000
                while (System.currentTimeMillis() < deadline) {
                    if (reader!!.ready()) {
                        val line = reader!!.readLine() ?: break
                        sb.append(line).append("\n")
                        Log.d("KataGo", line)
                        // ★ V5: 解析进度信息
                        when {
                            line.contains("KataGo v") -> onProgress("✅ KataGo ${line.substringAfter("KataGo v").trim()}")
                            line.contains("Creating context for OpenCL") -> onProgress("🔧 初始化 OpenCL GPU...")
                            line.contains("Using OpenCL Device") -> {
                                val dev = line.substringAfter("Using OpenCL Device 0:").substringBefore("(ARM)").trim()
                                onProgress("🎯 GPU: $dev")
                            }
                            line.contains("Performing autotuning") || line.contains("Beginning GPU tuning") ->
                                onProgress("⚙️ GPU 自动调优中...")
                            line.contains("Testing") && line.contains("different configs") -> {
                                val cnt = line.substringAfter("Testing ").substringBefore(" different").trim()
                                onProgress("⚙️ 测试 $cnt 种配置...")
                            }
                            line.contains("Tuning ") && line.contains("/") -> {
                                val progress = line.substringAfter("Tuning ").substringBefore(" Calls").trim()
                                onProgress("⚙️ 调优 $progress")
                            }
                            line.contains("Calls/sec") -> {} // 跳过详细性能数据
                            line.contains("All neural net configs autotuned") -> onProgress("✅ GPU 调优完成")
                            line.contains("Loaded config") -> onProgress("📋 加载配置完成")
                            line.contains("Model name:") -> onProgress("🧠 模型就绪")
                        }
                        if (line.contains("GTP ready")) break
                    } else Thread.sleep(200)
                }
                engineInfo = sb.toString(); isReady = true
                Log.i("KataGo", "ENGINE READY!")
                onReady(true, engineInfo)
            } catch (e: Exception) {
                Log.e("KataGo", "Init failed: ${e.message}", e)
                lastError = e.message ?: "unknown"; isReady = false
                onReady(false, lastError)
            }
        }.start()
    }

    /** V5.2: GTP 命令——完整消费响应（读到空行，避免串扰下一条命令） */
    private fun gtp(cmd: String, timeoutMs: Long = 15000): String {
        if (!isReady) return "? not ready"
        synchronized(gtpLock) {
        if (process?.isAlive != true) {
            Log.e("KataGo", "Process died! Resetting engine state")
            isReady = false
            lastError = "KataGo process crashed"
            return "? process died"
        }
        try {
            val startMs = System.currentTimeMillis()
            writer?.write(cmd); writer?.newLine(); writer?.flush()
            val sb = StringBuilder()
            val deadline = startMs + timeoutMs
            var seenResponse = false
            while (System.currentTimeMillis() < deadline) {
                if (process?.isAlive != true) {
                    Log.e("KataGo", "Process died during cmd: $cmd")
                    isReady = false
                    return "? process died"
                }
                if (reader?.ready() == true) {
                    val line = reader?.readLine() ?: break
                    if (!seenResponse) {
                        sb.append(line).append("\n")
                        if (line.startsWith("=") || line.startsWith("?")) seenResponse = true
                    } else {
                        // 已收到响应行("=...")，消费剩余直到空行（GTP 结束标志）
                        if (line.isEmpty()) break  // 空行 = 响应结束
                        sb.append(line).append("\n")  // 多行响应内容
                    }
                } else Thread.sleep(50)
            }
            val elapsed = System.currentTimeMillis() - startMs
            val result = sb.toString().trim()
            Log.i("KataGo", "GTP cmd=[$cmd] elapsed=${elapsed}ms resp=[${result.take(80)}]")
            return result
        } catch (e: Exception) {
            Log.e("KataGo", "GTP error: cmd=[$cmd] ${e.message}")
            isReady = false
            return "? ${e.message}"
        }
        } // synchronized
    }

    /** V5.2: GTP 坐标 ↔ 内部坐标（GTP 列名跳过 'I'） */
    private fun colToGtp(col: Int): Char {
        // GTP 列: A B C D E F G H J K ... (跳过 I)
        return if (col >= 8) ('A' + col + 1) else ('A' + col)
    }
    private fun gtpToCol(c: Char): Int {
        // 反向: J=8, K=9, ... (跳过 I)
        return if (c > 'I') (c - 'A' - 1) else (c - 'A')
    }

    /** GTP 坐标 → (row, col)，使用动态棋盘大小 */
    private fun gtpToCoord(gtp: String): Pair<Int, Int> {
        if (gtp.length < 2) return Pair(-1, -1)
        val col = gtpToCol(gtp[0])
        val row = currentBoardSize - gtp.substring(1).toInt()
        return Pair(row, col)
    }

    /** V5.2: genMove + 自动抓取 KataGo showboard 用于诊断 */
    fun genMove(color: String): Triple<Int, Int, String>? {
        val resp = gtp("genmove $color", 20000)
        val detail = resp.lines()
            .filter { !it.startsWith("=") && !it.startsWith("?") && it.isNotBlank() }
            .joinToString(" | ")
        for (line in resp.lines()) {
            if (line.startsWith("=")) {
                val m = line.substring(1).trim()
                if (m.lowercase() in listOf("pass", "resign")) {
                    Log.w("KataGo", "genMove $color → $m")
                    return null
                }
                val coord = gtpToCoord(m)
                Log.i("KataGo", "genMove $color → $m → ($coord)")
                return Triple(coord.first, coord.second, detail)
            }
        }
        Log.w("KataGo", "genMove $color → NO valid response: [$resp]")
        return null
    }

    /** V5.2: 获取 KataGo 内部棋盘字符串（用于诊断对比） */
    fun showboard(): String = gtp("showboard", 5000)

    fun setBoardSize(size: Int) { currentBoardSize = size; gtp("boardsize $size") }

    /** V5.5: 异步切换棋盘大小（用于设置中预加载模型，不阻塞 UI） */
    fun loadBoardSize(size: Int, onProgress: (String) -> Unit, onDone: () -> Unit) {
        if (boardLoading) { onDone(); return }  // V6.1: 防止并发加载
        boardLoading = true; boardSizeReady = false
        Thread {
            try {
                onProgress("加载 ${size}路棋盘模型...")
                currentBoardSize = size
                gtp("boardsize $size", 60000)
                boardSizeReady = true
                onDone()
            } catch (e: Exception) {
                Log.e("KataGo", "Load board $size failed: ${e.message}")
            }
            boardLoading = false
        }.start()
    }
    fun clearBoard() { gtp("clear_board") }
    fun playMove(color: String, row: Int, col: Int): Boolean {
        val resp = gtp("play $color ${colToGtp(col)}${currentBoardSize-row}")
        if (resp.startsWith("?")) {
            Log.e("KataGo", "playMove $color ($row,$col) → GTP $color ${colToGtp(col)}${currentBoardSize-row} FAILED: $resp")
            return false
        }
        return true
    }
    fun setKomi(komi: Float) { gtp("komi $komi") }

    /** V5.0: 预创建默认调优文件(9/13/19路)，跳过自动调优 */
    private fun createDefaultTuningFile(engineDir: File) {
        try {
            val tuningDir = File(engineDir, "opencltuning")
            tuningDir.mkdirs()
            val defaultContent = """VERSION=11
1
1
0
0
1
1
0
0
WGD=8 MDIMCD=8 NDIMCD=8 MDIMAD=8 NDIMBD=8 KWID=1 VWMD=1 VWND=1 PADA=1 PADB=1
WGD=8 MDIMCD=8 NDIMCD=8 MDIMAD=8 NDIMBD=8 KWID=1 VWMD=1 VWND=1 PADA=1 PADB=1
WGD=8 MDIMCD=8 NDIMCD=8 MDIMAD=8 NDIMBD=8 KWID=1 VWMD=1 VWND=1 PADA=1 PADB=1
WGD=8 MDIMCD=8 NDIMCD=8 MDIMAD=8 NDIMBD=8 KWID=1 VWMD=1 VWND=1 PADA=1 PADB=1
WGD=8 MDIMCD=8 NDIMCD=8 MDIMAD=8 NDIMBD=8 KWID=1 VWMD=1 VWND=1 PADA=1 PADB=1
WGD=8 MDIMCD=8 NDIMCD=8 MDIMAD=8 NDIMBD=8 KWID=1 VWMD=1 VWND=1 PADA=1 PADB=1
WGD=8 MDIMCD=8 NDIMCD=8 MDIMAD=8 NDIMBD=8 KWID=1 VWMD=1 VWND=1 PADA=1 PADB=1
WGD=8 MDIMCD=8 NDIMCD=8 MDIMAD=8 NDIMBD=8 KWID=1 VWMD=1 VWND=1 PADA=1 PADB=1
""".trimIndent()
            val possibleGPUs = listOf("MaliG52r1p0", "MaliG52", "MaliG51", "Adreno", "Mali")
            val boardSizes = listOf(9, 13, 19)
            for (gpuName in possibleGPUs) {
                for (bs in boardSizes) {
                    val fileName = "tune11_gpu${gpuName}_x${bs}_y${bs}_c384_mv14.txt"
                    val tuneFile = File(tuningDir, fileName)
                    if (!tuneFile.exists()) {
                        tuneFile.writeText(defaultContent)
                        Log.i("KataGo", "Created default tuning: $fileName")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("KataGo", "Failed to create tuning file: ${e.message}")
        }
    }

    fun shutdown() {
        try { gtp("quit", 2000) } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        isReady = false
    }
}
