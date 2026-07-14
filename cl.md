# GoDualScreen & Go3DGlobe — 版本更新日志 (Changelog)

> 围棋/地球双应用完整迭代记录

---

# GoDualScreen 版本更新日志

> 双屏围棋 Android 应用，KataGo AI 引擎，从 GomokuDualScreen V4.4 改造而来。

---

## V10.0 — 双 Activity 架构 (2026-07)

### 架构变更
- 弃用 `Presentation` API，改为独立 `GamePresentation` Activity
- 通过反射 `setLaunchDisplayId` 启动副屏到 Display 2
- `GamePresentation` 使用 `singleInstance` 启动模式

### 核心改动
- `launchWhiteScreen()`: 先检测已有 Activity 是否存活 → 复用刷新或反射创建新实例
- 延迟 600ms 绑定回调（`onPiecePlaced`、`onPassRequest`、`onStartOrRestart`、`onUndoRequest`）
- `GameState` 单例：`initialSyncDone` volatile 标记

### Bug 修复
- **Kotlin 作用域 bug**: `GoView.apply { this.game = game }` 两边都解析为 `GoView.game`（null），改为 `this@GamePresentation.game`
- **singleInstance 竞态**: `finish()+startActivity` 导致 `onNewIntent` 而非 `onCreate`，改为复用已有 Activity

---

## V10.1 — 让子重置 + 退出确认 (2026-07)

### 修复
- `resetHandicapForBoardChange()`: 棋盘大小切换时独立重置让子 UI，不再在 `refreshView` 中重置
- 副屏返回键: `GamePresentation.onBackPressed` → `MainActivity.requestExitFromWhite()`，弹出确认对话框

---

## V10.2 — AI 自杀重试 + 思考动画 (2026-07)

### AI 自杀落子重试
- `GoGame.suggestMove()`: genmove 返回后检测自杀点（无气且无提子），调用 `undoMove()` + 重新 `genmove`，最多重试 3 次
- `KataGoEngine.undoMove()`: 新增公开方法，发送 GTP `undo` 命令
- 让子对局 genmove 前首次同步: 让子 ≥2 时若 `initialSyncDone == false`，全量 replay 棋盘状态到 KataGo

### UI 优化
- 思考中旋转动画: `computeAiAsync` 和自动落子倒计时使用 `◐◓◑◒` 四帧旋转，50ms/帧 = 20fps
- 自动落子: 1s 准备时间 + 旋转动画

---

## V10.3 — 诊断工具 + 防杀对局 + GPU 预调优 + 隐藏最近任务 (2026-07)

### 副屏 Home 键防杀对局
- `GamePresentation.onUserLeaveHint`: 对局进行中不杀主屏，仅 dismiss 自己；只有对局未开始/已结束才退出

### 隐藏最近任务列表
- `AndroidManifest`: `GamePresentation` 添加 `android:excludeFromRecents="true"`
- `MainActivity.launchWhiteScreen`: `Intent` 添加 `FLAG_ACTIVITY_NEW_TASK`

### DebugReceiver — ADB 诊断广播
- 用法: `adb shell am broadcast -n com.go.dualscreen/.DebugReceiver -a com.go.dualscreen.DEBUG_DUMP`
- 保存: 棋盘状态、KataGo 内部棋盘、录像、落子历史

### KataGo GPU 调优预加载
- 启动时从 `assets/katago/opencltuning/` 复制 15 个预生成调优文件（Mali-G52 / b18 c384），跳过首次 20-40s 调优

### 诊断日志
- `suggestMove` 全路径日志: start → genmove → 返回值 → 自杀检测 → 耗时
- `GoView` 防刷屏日志: `loggedBoardSz` 变量，只在棋盘大小变化时输出一次

### AI 模型实验 (失败)
- b20 模型: 引擎卡死，GPU 无法承载
- b28 模型: 291MB 过大，引擎初始化超时
- **回退到 b18** (105MB) + 800 visits + 8s maxTime

---

## Go3DGlobe V3.5.1 — 默认配置优化 (2026-07-01)

### 改动
- 默认瓦片源 → 高德（`"amap"`），启动零 token
- 默认 FPS 浮层 → ON
- 背景音乐默认 → OFF
- 版本号 → v3.5.1

---

## Go3DGlobe V3.5.0 — Token 零消耗架构 (2026-07-01)

### 问题
每次启动大量消耗天地图 token：探测每 15s + z≤5 瓦片在线请求

### 解决
- `TiandituSatelliteLayer.createTile()` z≤5 拦截 → `ImageSource.fromFilePath()` 本地 Amap 缓存
- `AmapSatelliteLayer.createTile()` z≤5 本地 / z>5 透明 bitmap 占位
- 探测间隔 15s→120s
- 预置瓦片 1364 张 z1-z5 打包进 APK，首次启动拷贝到内部存储

### 踩坑
`file://` URL 不支持、`MercatorImageTile` package-private、空 URL 崩溃、NASA 纹理 `fromResource()` 失败

---

## Go3DGlobe V3.4.0 — NASA 多级纹理 + 预置瓦片 (2026-06)

- NASA Blue Marble 4K+8K 本地底图系统
- 8K 拆分为东西半球防 GPU 限制
- 预下载高德 z1-z5 瓦片
- APK 大小 ~50MB→~100MB

---

## Go3DGlobe V3.3.0 — 内存优化 + 功能完善 (2026-06)

- `RenderResourceCache` 768MB→48MB，解决 Mali-G52 OOM
- `detailControl` 80→30
- 84 首都标记 + 177 国界 + 大洲大洋山川河流
- 首都数据政治修正

---

# GomokuDualScreen 版本更新日志

> 双屏五子棋 Android 应用，从 V1.0 到 V4.4 的完整迭代记录（~50 次构建）。

---

## V1.0 — 单屏五子棋原型

**时间**：项目初始

### 功能
- 15×15 标准五子棋棋盘
- 黑白双方轮流落子
- 五连判胜、平局检测
- 基础 3D 立体棋子渲染（`GomokuView` 自定义 View）
- 落子预览（半透明棋子 + 蓝色确认圈）

### 核心文件
- `GomokuGame.kt` — 棋盘逻辑、落子、判胜
- `GomokuView.kt` — 棋盘渲染、棋子绘制、触摸处理
- `MainActivity.kt` — 单 Activity 承载所有 UI

### 技术栈
- Kotlin + Gradle 8.5
- compileSdk 34, minSdk 31
- JDK 17

---

## V2.0 — 双屏支持（Presentation 方案）

**时间**：约 ~10 次构建迭代

### 新增
- 引入 Android `Presentation` API 将游戏画面投递到副屏
- 主屏（Display 0）显示黑方，副屏（Display 2）显示白方
- 双屏独立棋盘渲染

### 架构
```
MainActivity (Display 0, 黑方)
    └── GamePresentation extends Presentation (Display 2, 白方)
```

### 问题
- `Presentation` 存在 Android 系统跨屏显示限制
- 副屏启动时 Presentation 可能无法正常显示
- 为此后 V3.9.2 迁移到双 Activity 方案埋下伏笔

---

## V2.3 — 稳定双屏游戏

**时间**：约 ~15 次构建迭代

### 功能完善
- 双方交替落子，规则完整
- 悔棋功能（需对方同意）
- 重新开始（需对方同意）
- 基础状态显示（轮到谁、等待中、游戏结束）

### 修复
- 副屏 Presentation 显示异常修复
- 棋子坐标在不同屏上的校准

---

## V3.0 — 语音播报系统

### 新增
- 集成 edge-tts（Microsoft Edge TTS）生成语音 MP3
- `generate_tts.py` 自动生成 24 个语音文件
- 标准音色：`zh-CN-XiaoxiaoNeural`
- 落子提示、轮到你了、催促、胜利、失败等场景语音

### 技术细节
- MP3 文件放入 `app/src/main/res/raw/`
- `SoundFX.playVoice()` 使用 `MediaPlayer` 播放

### 文件
- `generate_tts.py`
- `SoundFX.kt`

---

## V3.1 — 胜利/失败动画

### 新增
- `GomokuView` 粒子动画系统
- 胜利动画：金色彩带 + 烟花爆炸
- 失败动画：蓝灰碎片 + 暗色烟雾
- `Particle` 数据类：位置、速度、生命值、颜色

### 动画参数（初版）
- 胜利：80 爆炸粒子（life=1.2）+ 60 彩带 + 40 闪光，~3 秒
- 失败：80 碎裂粒子（life=1.4）+ 70 雨滴 + 40 烟雾，~5 秒

---

## V3.2 — 臭鸡蛋 + 鲜花动画

### 新增
- **臭鸡蛋动画**（催促/挑衅）：飞入→撞击→炸裂 3 阶段
  - 蛋黄炸裂 40 个、蛋清飞溅 60 滴、蛋壳碎片 70 片、绿色臭气 20 个
- **鲜花动画**（Logo 点击）：花蕾→绽放→飘散 3 阶段
  - 总粒子 240 个，花瓣从中心螺旋扩散
- 动画自驱动循环（`ensureAnimating()` + `selfAnimRunnable`）

### 动画防抖
- 500ms 内不重复触发同一动画（`lastAnimStartMs`）

---

## V3.3 — 暂停系统

### 新增
- 游戏暂停/恢复功能
- 每方有独立暂停次数（初始各 1 次）
- 暂停状态显示：`⏸ 你暂停了游戏` / `⏸ 对方暂停中`
- 暂停时倒计时冻结
- 暂停时无法落子、悔棋

### 数据模型
```kotlin
var isPaused = false
var pausedByPlayer = EMPTY  // 谁发起的暂停
var pauseCountBlack = 1
var pauseCountWhite = 1
```

---

## V3.4 — 游戏设置持久化

### 新增
- 设置对话框（齿轮图标 `≡` 进入）
- 落子音效开关（`SoundFX.stoneSoundEnabled`）
- 语音风格切换：标准 ↔ 幽默（东北话）
- 棋子顺序编号显示开关
- 设置持久化到 `SharedPreferences`

### 幽默语音
- 东北话音色：`zh-CN-liaoning-XiaobeiNeural`
- 文件前缀 `humor_`，`SoundFX.playVoice()` 自动路由

---

## V3.5 — 挑衅系统

### 新增
- 挑衅按钮（😈 图标），消耗挑衅次数
- 挑衅次数：初始每方 1 次
- 挑衅触发：播放挑衅语音 + 臭鸡蛋动画
- 胜利方 +3 挑衅次数

### UI
- 挑衅次数显示（`×N`）在主屏左下角和副屏对应位置

---

## V3.6 — 倒计时 + 催促系统

### 新增
- 60 秒倒计时（`⏱60s` 显示）
- 超时自动触发臭鸡蛋动画 + 语音提示
- 催促按钮（⏰ 图标）：播放催促语音 + 震动对方屏幕

### 倒计时逻辑
- 轮到该玩家时开始倒计时
- 暂停时冻结
- 落子后切换倒计时到对方
- 30 秒时语音提醒

---

## V3.7 — 稳定性修复

### 关键 Bug 修复

**SoundFX 多线程崩溃 (SIGSEGV)**
- **原因**：多线程共享单个 `AudioTrack` 引用
- **修复**：每个音效方法使用局部 `AudioTrack` 变量（`val at = AudioTrack.Builder()...`）

**退出时崩溃**
- **原因**：`lateinit countdownText` 在 `initMainUI()` 之前被访问
- **修复**：添加 `::countdownText.isInitialized` 安全检查

**PowerShell 文件编码损坏**
- **现象**：`Get-Content | -replace | Set-Content` 破坏 UTF-8 中文编码
- **修复**：改用 Python 脚本生成 `.kt` 文件

**副屏启动崩溃**
- **原因**：迁移 hack 中 `finish()` + `startActivity` 导致系统卡顿/崩溃
- **临时方案**：限制副屏启动行为（V3.9.2 彻底解决）

---

## V3.8 — Presentation 方案最终版

### 功能完整度
- ✅ 双屏五子棋（Presentation）
- ✅ 语音播报（标准 + 幽默）
- ✅ 4 种粒子动画（胜/负/蛋/花）
- ✅ 暂停系统
- ✅ 挑衅系统
- ✅ 倒计时 + 催促
- ✅ 游戏设置持久化
- ✅ 悔棋 + 重新开始（需确认）

### 已知限制
- 副屏启动体验差（主屏空白）
- Presentation API 跨屏限制
- 迁移 hack 不稳定

> 📌 V3.8 是 Presentation 方案的最后一个版本，此后 V3.9.2 开始迁移到双 Activity 架构。

---

## V3.9.2 — 双 Activity 架构初版

**目标**：解决 Android `Presentation` API 跨屏限制，无论从哪个屏启动应用，主屏显示黑方、副屏显示白方。

### 架构变更
- 弃用 `Presentation`，改为两个独立 `Activity`：
  - `MainActivity`（启动器 Activity）— 当前玩家视角
  - `GamePresentation`（副屏 Activity）— 对面玩家视角
- GP 通过 `startActivity` + 反射 `setLaunchDisplayId()` 投递到副屏
- 新增 `GameState` 单例，共享 `GomokuGame` 和 `MainActivity` 引用

### 新增文件
- `GameState.kt` — 全局状态单例

### 核心代码
```kotlin
// MainActivity 根据启动屏决定视角
myPlayer = if (myDisplayId == 2) PLAYER_WHITE else PLAYER_BLACK
// GP 视角由 MainActivity 显式指定
GamePresentation.sharedPerspective = otherPerspective
```

### 构建问题
- `GamePresentation.kt` 初始生成时缺少多个方法（`showSettingsDialog`、`updateButtonState`、`showRestartRequestDialog` 等），导致 10+ 编译错误
- 尝试用 `python -c` 追加方法失败（PowerShell 截断多行字符串）
- 最终用脚本文件 `fix_gp.py` + `replace_string_in_file` 补齐所有方法

### 经验教训
- PowerShell 下 `python -c` 不适合传递含 Kotlin 代码的多行字符串
- 文件编辑工具链：`create_file` / `replace_string_in_file` 优先于终端脚本

---

## V3.9.3 — GP 自检视角（失败尝试）

**目标**：让每个 Activity 根据自身所在物理屏独立决定视角。

### 变更
- GP 在 `onCreate` 中自检 `display?.displayId` 决定 `sharedPerspective`
- MainActivity 不再设置 `sharedPerspective`，只设 `sharedGame`

```kotlin
// GamePresentation.onCreate()
val myDisplayId = display?.displayId ?: 2
sharedPerspective = if (myDisplayId == 0) PLAYER_BLACK else PLAYER_WHITE
```

### 问题
- **失败**：`display?.displayId` 在 `onCreate` 阶段不可靠——Activity 尚未完全挂载到目标屏
- 导致无论 GP 被投递到哪个屏，视角检测都可能出错
- 后续版本回退为显式指派方案

### 经验教训
- Android Activity 的 `display?.displayId` 在 `onCreate` 中不可用于判断最终所在屏
- 视角应由启动方（MainActivity）根据**目标显示屏**显式指派

---

## V4.0 — 主屏防呆 + Home 键双屏退出

**目标**：从根本上解决"副屏启动导致主屏空白"问题。

### 防呆机制（核心设计）
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    @Suppress("DEPRECATION")
    val launchedDisplayId = windowManager.defaultDisplay.displayId
    if (launchedDisplayId != Display.DEFAULT_DISPLAY) {
        // 重建到主屏
        val options = ActivityOptions.makeBasic()
        options.launchDisplayId = Display.DEFAULT_DISPLAY
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            options.toBundle()
        )
        finish()
        return  // 跳过后续初始化
    }
    // 以下仅在主屏执行
    myPlayer = GomokuGame.PLAYER_BLACK  // 始终黑方
    ...
}
```

### 其他变更
- **简化视角**：MainActivity 始终黑方（`myPlayer = PLAYER_BLACK`），GP 始终白方
- **Home 键退出**：两个 Activity 的 `onUserLeaveHint()` 都调用 `finishAffinity()`，一键关闭双屏
- `launchWhiteScreen()` 中根据目标屏 ID 显式设置 `sharedPerspective`
- 新增 import：`ActivityOptions`、`Display`

### 经验教训
- 在 `onCreate` 最早期拦截是最可靠的屏幕重定向方案
- `finish()` 后必须 `return`，否则 `lateinit` 变量未初始化崩溃
- 必须用 `FLAG_ACTIVITY_CLEAR_TASK` 清空错误任务栈

---

## V4.1 — 返回键同步 + 动画增强

**目标**：任意屏按返回键都能退出双屏；胜利/失败动画延长到 5 秒并增强视觉冲击。

### 返回键同步
- `MainActivity.onBackPressed()` → `finishAffinity()`
- `GamePresentation.onBackPressed()` → `finishAffinity()`

### 动画增强

#### 胜利动画（`startWinAnimation`）
| 参数 | 旧值 | 新值 |
|------|:---:|:---:|
| 中心爆炸粒子 | 80 个 (life=1.2) | **120 个** (life=**3.0**) |
| 彩带粒子 | 60 个 (life=1.0) | **100 个** (life=**2.8**) |
| 闪光粒子 | 40 个 (life=0.6) | **80 个** (life=**2.0**) |
| 阶段 2（1.5s） | ❌ | ✅ 80 烟花 + 50 闪光 |
| 阶段 3（2.8s） | ❌ | ✅ 60 上升烟花柱 |
| 总粒子数 | 180 | **490** |

#### 失败动画（`startLoseAnimation`）
| 参数 | 旧值 | 新值 |
|------|:---:|:---:|
| 碎裂粒子 | 80 个 (life=1.4) | **120 个** (life=**3.0**) |
| 雨滴粒子 | 70 个 (life=1.2) | **100 个** (life=**2.5**) |
| 烟雾粒子 | 40 个 (life=1.0) | **60 个** (life=**2.2**) |
| 红色裂纹 | ❌ | ✅ 50 个 |
| 阶段 2（1.5s） | ❌ | ✅ 80 碎片涌入 |
| 阶段 3（2.8s） | ❌ | ✅ 60 暗色雨滴 |
| 总粒子数 | 190 | **470** |

### 动画系统修复
- `updateAnimation()` 中 `animProgress > 1.5f` 自动停止条件排除 `animState == 1` 和 `animState == 2`
- 胜利/失败动画由 `postDelayed` 在 5s 后清理，不受自动停止影响

### 经验教训
- 多阶段动画需要增加 `postDelayed` 波次，而非仅增加粒子数
- 粒子 `life` 需匹配目标时长：5 秒 ≈ life 3.0（衰减率 0.018/帧 @30fps）

---

## V4.2 — 挑衅次数逻辑修正 + 副屏退出通知

**目标**：挑衅次数不被重启清空；副屏退出时确保主屏也被关闭。

### 挑衅次数修正
| 场景 | 旧行为 | 新行为 |
|------|--------|--------|
| 程序启动 | `tauntCount = 1` | 不变 |
| 开始新游戏 | `tauntCount = 1` ❌ | **不再重置** |
| 重新开始 | `tauntCount = 1` ❌ | **不再重置** |
| 胜利方 | `tauntCount += 3` | 不变 |

修改点：
- `onGameStarted()` 中删除 `tauntCountBlack = 1; tauntCountWhite = 1`
- `doRestart()` 中删除 `tauntCountBlack = 1; tauntCountWhite = 1`

### 副屏退出通知
**根因**：`setLaunchDisplayId` 可能将 GP 放到独立任务栈，GP 的 `finishAffinity()` 无法跨栈关闭 MainActivity。

**修复**：GP 退出时先显式通知 MainActivity：
```kotlin
// GamePresentation.kt
override fun onBackPressed() {
    getMainActivity?.invoke()?.finishAffinity()  // 先关主屏
    finishAffinity()                              // 再关副屏
}
override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    getMainActivity?.invoke()?.finishAffinity()
    instance = null
    finishAffinity()
}
```

### 经验教训
- 跨 Display 启动的 Activity 可能在独立任务栈中
- `finishAffinity()` 只影响当前任务栈，跨栈需要显式回调通知

---

## V4.3 — 挑衅次数初始显示 + 棋子美化

**目标**：打开 App 即看到挑衅次数为 ×1；棋子缩小更美观。

### 挑衅次数初始化
在 `onCreate` 中 `initMainUI()` 之后立即调用 `updateTauntDisplay()`：
```kotlin
initMainUI()
updateTauntDisplay()  // V4.3 新增
gomokuView.showPieceOrder = ...
```

### 棋子缩小 10%
| 文件:行 | 说明 | 旧值 | 新值 |
|---------|------|:---:|:---:|
| `GomokuView.kt:221` | `drawPiece()` 棋子半径 | `0.44f` | `0.40f` |
| `GomokuView.kt:140` | 预览棋子半径 | `0.44f` | `0.40f` |

---

## V4.4 — 白方挑衅次数同步修复

**目标**：打开 App 时白方（副屏）挑衅次数也显示 ×1。

### 问题分析
`updateTauntDisplay()` 在 `onCreate` 中被调用时，`gamePresentation` 仍为 `null`（GP 需 600ms 延迟连接），导致白方挑衅次数无法同步到副屏。

### 修复
在 `launchWhiteScreen()` 的 GP 连接回调中追加调用：
```kotlin
handler.postDelayed({
    GamePresentation.instance?.let { pres ->
        // ... 回调设置 ...
        gamePresentation = pres
        updateTauntDisplay()    // V4.4：GP 已就绪，同步挑衅次数
        updateStatusDisplay()
    }
}, 600)
```

### 经验教训
- 跨 Activity 的 UI 同步必须等待对方 `instance` 就绪
- `updateTauntDisplay()` 内部通过 `gamePresentation?.setTauntCount()` 同步，`?.` 安全调用在 `null` 时静默跳过

---

## 技术栈总结

| 组件 | 版本/路径 |
|------|----------|
| JDK | 17 (`D:\work\ai_code\tools\jdk-17\jdk-17.0.14+7`) |
| Android SDK | api 34 (`D:\work\ai_code\tools\android-sdk`) |
| Gradle | 8.5 |
| Kotlin | 1.9.20 |
| compileSdk / minSdk | 34 / 31 |
| Python | 3.12 (`D:/Python312/python.exe`) |
| TTS | edge-tts (XiaoxiaoNeural + liaoning-XiaobeiNeural) |
| 签名 | `D:\qianming\debug.keystore` |

## 代码规模

| 文件 | 职责 |
|------|------|
| `MainActivity.kt` | ~310 行，主屏 Activity + 防呆 + 游戏控制 |
| `GamePresentation.kt` | ~330 行，副屏 Activity + 对话框 |
| `GomokuView.kt` | ~620 行，棋盘渲染 + 粒子动画 |
| `GomokuGame.kt` | 15×15 棋盘核心逻辑 |
| `SoundFX.kt` | 音效播放（线程安全） |
| `GameState.kt` | 全局状态单例 |

## Git 提交记录

| 日期 | 提交 | 说明 |
|------|------|------|
| 2026-06-11 | `fdf31a8` | **Initial commit: GomokuDualScreen V4.4** — 57 文件, 2973 行 |

### 首次推送过程

```powershell
# 1. 生成 ED25519 SSH 密钥（GitHub 已禁用密码登录）
ssh-keygen -t ed25519 -C "caucy.niu@newlink-sz.com" -f "$HOME\.ssh\id_ed25519" -N """"

# 2. 复制公钥 → https://github.com/settings/ssh/new → Add SSH Key

# 3. 初始化并推送
git init
git remote add origin git@github.com:caucy2026/GomokuDualScreen.git
git add -A
git commit -m "Initial commit: GomokuDualScreen V4.4 - 双屏五子棋 Android 应用"
git push -u origin master
```

**仓库地址**：https://github.com/caucy2026/GomokuDualScreen

---

> 📅 最后更新：2026-06-11 | 当前版本：**V4.4** | 总构建次数：~50

## V3.9.2 — 双 Activity 架构初版

**目标**：解决 Android `Presentation` API 跨屏限制，无论从哪个屏启动应用，主屏显示黑方、副屏显示白方。

### 架构变更
- 弃用 `Presentation`，改为两个独立 `Activity`：
  - `MainActivity`（启动器 Activity）— 当前玩家视角
  - `GamePresentation`（副屏 Activity）— 对面玩家视角
- GP 通过 `startActivity` + 反射 `setLaunchDisplayId()` 投递到副屏
- 新增 `GameState` 单例，共享 `GomokuGame` 和 `MainActivity` 引用

### 新增文件
- `GameState.kt` — 全局状态单例

### 核心代码
```kotlin
// MainActivity 根据启动屏决定视角
myPlayer = if (myDisplayId == 2) PLAYER_WHITE else PLAYER_BLACK
// GP 视角由 MainActivity 显式指定
GamePresentation.sharedPerspective = otherPerspective
```

### 构建问题
- `GamePresentation.kt` 初始生成时缺少多个方法（`showSettingsDialog`、`updateButtonState`、`showRestartRequestDialog` 等），导致 10+ 编译错误
- 尝试用 `python -c` 追加方法失败（PowerShell 截断多行字符串）
- 最终用脚本文件 `fix_gp.py` + `replace_string_in_file` 补齐所有方法

### 经验教训
- PowerShell 下 `python -c` 不适合传递含 Kotlin 代码的多行字符串
- 文件编辑工具链：`create_file` / `replace_string_in_file` 优先于终端脚本

---

## V3.9.3 — GP 自检视角（失败尝试）

**目标**：让每个 Activity 根据自身所在物理屏独立决定视角。

### 变更
- GP 在 `onCreate` 中自检 `display?.displayId` 决定 `sharedPerspective`
- MainActivity 不再设置 `sharedPerspective`，只设 `sharedGame`

```kotlin
// GamePresentation.onCreate()
val myDisplayId = display?.displayId ?: 2
sharedPerspective = if (myDisplayId == 0) PLAYER_BLACK else PLAYER_WHITE
```

### 问题
- **失败**：`display?.displayId` 在 `onCreate` 阶段不可靠——Activity 尚未完全挂载到目标屏
- 导致无论 GP 被投递到哪个屏，视角检测都可能出错
- 后续版本回退为显式指派方案

### 经验教训
- Android Activity 的 `display?.displayId` 在 `onCreate` 中不可用于判断最终所在屏
- 视角应由启动方（MainActivity）根据**目标显示屏**显式指派

---

## V4.0 — 主屏防呆 + Home 键双屏退出

**目标**：从根本上解决"副屏启动导致主屏空白"问题。

### 防呆机制（核心设计）
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    @Suppress("DEPRECATION")
    val launchedDisplayId = windowManager.defaultDisplay.displayId
    if (launchedDisplayId != Display.DEFAULT_DISPLAY) {
        // 重建到主屏
        val options = ActivityOptions.makeBasic()
        options.launchDisplayId = Display.DEFAULT_DISPLAY
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            options.toBundle()
        )
        finish()
        return  // 跳过后续初始化
    }
    // 以下仅在主屏执行
    myPlayer = GomokuGame.PLAYER_BLACK  // 始终黑方
    ...
}
```

### 其他变更
- **简化视角**：MainActivity 始终黑方（`myPlayer = PLAYER_BLACK`），GP 始终白方
- **Home 键退出**：两个 Activity 的 `onUserLeaveHint()` 都调用 `finishAffinity()`，一键关闭双屏
- `launchWhiteScreen()` 中根据目标屏 ID 显式设置 `sharedPerspective`
- 新增 import：`ActivityOptions`、`Display`

### 经验教训
- 在 `onCreate` 最早期拦截是最可靠的屏幕重定向方案
- `finish()` 后必须 `return`，否则 `lateinit` 变量未初始化崩溃
- 必须用 `FLAG_ACTIVITY_CLEAR_TASK` 清空错误任务栈

---

## V4.1 — 返回键同步 + 动画增强

**目标**：任意屏按返回键都能退出双屏；胜利/失败动画延长到 5 秒并增强视觉冲击。

### 返回键同步
- `MainActivity.onBackPressed()` → `finishAffinity()`
- `GamePresentation.onBackPressed()` → `finishAffinity()`

### 动画增强

#### 胜利动画（`startWinAnimation`）
| 参数 | 旧值 | 新值 |
|------|:---:|:---:|
| 中心爆炸粒子 | 80 个 (life=1.2) | **120 个** (life=**3.0**) |
| 彩带粒子 | 60 个 (life=1.0) | **100 个** (life=**2.8**) |
| 闪光粒子 | 40 个 (life=0.6) | **80 个** (life=**2.0**) |
| 阶段 2（1.5s） | ❌ | ✅ 80 烟花 + 50 闪光 |
| 阶段 3（2.8s） | ❌ | ✅ 60 上升烟花柱 |
| 总粒子数 | 180 | **490** |

#### 失败动画（`startLoseAnimation`）
| 参数 | 旧值 | 新值 |
|------|:---:|:---:|
| 碎裂粒子 | 80 个 (life=1.4) | **120 个** (life=**3.0**) |
| 雨滴粒子 | 70 个 (life=1.2) | **100 个** (life=**2.5**) |
| 烟雾粒子 | 40 个 (life=1.0) | **60 个** (life=**2.2**) |
| 红色裂纹 | ❌ | ✅ 50 个 |
| 阶段 2（1.5s） | ❌ | ✅ 80 碎片涌入 |
| 阶段 3（2.8s） | ❌ | ✅ 60 暗色雨滴 |
| 总粒子数 | 190 | **470** |

### 动画系统修复
- `updateAnimation()` 中 `animProgress > 1.5f` 自动停止条件排除 `animState == 1` 和 `animState == 2`
- 胜利/失败动画由 `postDelayed` 在 5s 后清理，不受自动停止影响

### 经验教训
- 多阶段动画需要增加 `postDelayed` 波次，而非仅增加粒子数
- 粒子 `life` 需匹配目标时长：5 秒 ≈ life 3.0（衰减率 0.018/帧 @30fps）

---

## V4.2 — 挑衅次数逻辑修正 + 副屏退出通知

**目标**：挑衅次数不被重启清空；副屏退出时确保主屏也被关闭。

### 挑衅次数修正
| 场景 | 旧行为 | 新行为 |
|------|--------|--------|
| 程序启动 | `tauntCount = 1` | 不变 |
| 开始新游戏 | `tauntCount = 1` ❌ | **不再重置** |
| 重新开始 | `tauntCount = 1` ❌ | **不再重置** |
| 胜利方 | `tauntCount += 3` | 不变 |

修改点：
- `onGameStarted()` 中删除 `tauntCountBlack = 1; tauntCountWhite = 1`
- `doRestart()` 中删除 `tauntCountBlack = 1; tauntCountWhite = 1`

### 副屏退出通知
**根因**：`setLaunchDisplayId` 可能将 GP 放到独立任务栈，GP 的 `finishAffinity()` 无法跨栈关闭 MainActivity。

**修复**：GP 退出时先显式通知 MainActivity：
```kotlin
// GamePresentation.kt
override fun onBackPressed() {
    getMainActivity?.invoke()?.finishAffinity()  // 先关主屏
    finishAffinity()                              // 再关副屏
}
override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    getMainActivity?.invoke()?.finishAffinity()
    instance = null
    finishAffinity()
}
```

### 经验教训
- 跨 Display 启动的 Activity 可能在独立任务栈中
- `finishAffinity()` 只影响当前任务栈，跨栈需要显式回调通知

---

## V4.3 — 挑衅次数初始显示 + 棋子美化

**目标**：打开 App 即看到挑衅次数为 ×1；棋子缩小更美观。

### 挑衅次数初始化
在 `onCreate` 中 `initMainUI()` 之后立即调用 `updateTauntDisplay()`：
```kotlin
initMainUI()
updateTauntDisplay()  // V4.3 新增
gomokuView.showPieceOrder = ...
```

### 棋子缩小 10%
| 文件:行 | 说明 | 旧值 | 新值 |
|---------|------|:---:|:---:|
| `GomokuView.kt:221` | `drawPiece()` 棋子半径 | `0.44f` | `0.40f` |
| `GomokuView.kt:140` | 预览棋子半径 | `0.44f` | `0.40f` |

---

## V4.4 — 白方挑衅次数同步修复

**目标**：打开 App 时白方（副屏）挑衅次数也显示 ×1。

### 问题分析
`updateTauntDisplay()` 在 `onCreate` 中被调用时，`gamePresentation` 仍为 `null`（GP 需 600ms 延迟连接），导致白方挑衅次数无法同步到副屏。

### 修复
在 `launchWhiteScreen()` 的 GP 连接回调中追加调用：
```kotlin
handler.postDelayed({
    GamePresentation.instance?.let { pres ->
        pres.onPiecePlaced = { ... }
        pres.onStartOrRestart = { ... }
        pres.onUndoRequest = { ... }
        pres.getMainActivity = { this@MainActivity }
        gamePresentation = pres
        updateTauntDisplay()    // V4.4：GP 已就绪，同步挑衅次数
        updateStatusDisplay()
    }
}, 600)
```

### 经验教训
- 跨 Activity 的 UI 同步必须等待对方 `instance` 就绪
- `updateTauntDisplay()` 内部通过 `gamePresentation?.setTauntCount()` 同步，`?.` 安全调用在 `null` 时静默跳过

---

## 技术栈总结

| 组件 | 版本/路径 |
|------|----------|
| JDK | 17 (`D:\work\ai_code\tools\jdk-17\jdk-17.0.14+7`) |
| Android SDK | api 34 (`D:\work\ai_code\tools\android-sdk`) |
| Gradle | 8.5 |
| Kotlin | 1.9.20 |
| compileSdk / minSdk | 34 / 31 |
| Python | 3.12 (`D:/Python312/python.exe`) |
| TTS | edge-tts (XiaoxiaoNeural + liaoning-XiaobeiNeural) |
| 签名 | `D:\qianming\debug.keystore` |

## 代码规模

| 文件 | 职责 |
|------|------|
| `MainActivity.kt` | ~310 行，主屏 Activity + 防呆 + 游戏控制 |
| `GamePresentation.kt` | ~330 行，副屏 Activity + 对话框 |
| `GomokuView.kt` | ~620 行，棋盘渲染 + 粒子动画 |
| `GomokuGame.kt` | 15×15 棋盘核心逻辑 |
| `SoundFX.kt` | 音效播放（线程安全） |
| `GameState.kt` | 全局状态单例 |

---

> 📅 最后更新：2026-06-11 | 当前版本：**V4.4**
