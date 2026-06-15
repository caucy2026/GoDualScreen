# 任务：将 GomokuDualScreen 五子棋改造为双屏围棋

> **使用方法**：将此文件内容作为提示词，在新对话中发送给 AI 编程助手（如 GitHub Copilot）。

---

## 项目背景

现有项目 `D:\work\ai_code\GomokuDualScreen` 是一个完整的双屏五子棋 Android 应用。代码已在 GitHub：https://github.com/caucy2026/GomokuDualScreen。

**环境**：
- JDK 17: `D:\work\ai_code\tools\jdk-17\jdk-17.0.14+7`
- Android SDK: `D:\work\ai_code\tools\android-sdk` (api 34)
- Gradle 8.5 / Kotlin 1.9.20 / compileSdk 34 / minSdk 31
- 签名: `D:\qianming\debug.keystore` (alias=androiddebugkey, pwd=880203)
- Python: `D:/Python312/python.exe` (用于 edge-tts 语音生成)

构建命令：
```powershell
$env:JAVA_HOME='D:\work\ai_code\tools\jdk-17\jdk-17.0.14+7'
D:\work\ai_code\tools\jdk-17\jdk-17.0.14+7\bin\java.exe -classpath 'D:\work\ai_code\GoDualScreen\gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain assembleRelease --no-daemon -p D:\work\ai_code\GoDualScreen
```

---

## 要求

### 1. 复制项目

将 `D:\work\ai_code\GomokuDualScreen` 完整复制到 `D:\work\ai_code\GoDualScreen`，然后进行以下改造。

需要修改的地方：
- 包名: `com.gomoku.dualscreen` → `com.go.dualscreen`
- 应用名: "GomokuDualScreen" → "GoDualScreen"
- `settings.gradle.kts` 中 `rootProject.name`
- `app/build.gradle.kts` 中 `namespace` 和 `applicationId`
- `AndroidManifest.xml` 中 `package` 和 Activity 的 `android:name`
- 所有 `.kt` 文件的 `package` 声明
- 所有 `.kt` 文件中的 import 路径
- 资源文件名如有 "gomoku" 也需修改

### 2. 核心游戏逻辑改造 (GomokuGame.kt → GoGame.kt)

围棋与五子棋的**关键差异**：

| 特性 | 五子棋 | 围棋 |
|------|--------|------|
| 棋盘大小 | 15×15 | **19×19**（也支持 9×9, 13×13） |
| 胜利条件 | 五连 | **地盘多者胜** |
| 吃子 | ❌ | ✅ 无气之子被提走 |
| 劫 | ❌ | ✅ 禁止全局同形再现 |
| 让子 | ❌ | ✅ 开局黑方让子 |
| 数目 | ❌ | ✅ 终局计算地盘+提子 |
| 自杀 | ❌ | ❌（禁止自填满） |

需要实现：
- `GoGame.kt`：19×19 棋盘、落子、气计算、提子、劫检测、数目
- 棋盘上显示星位（19×19 标准星位：4-4, 4-10, 4-16, 10-4, 10-10, 10-16, 16-4, 16-10, 16-16）
- 提子计数显示（双方吃子数）
- 终局判定（双方连续 pass 或同意结束）

### 3. 棋盘渲染改造 (GomokuView.kt → GoView.kt)

- 棋盘线延伸到 19×19
- 星位点更新为围棋标准 9 星位
- 棋子半径适当缩小（围棋棋子密度更高，建议 `cellSize * 0.42f`）
- 落子位置改为**交叉点**（最重要！围棋下在交叉点上，不是格子里）

### 4. UI 调整

- 状态栏增加提子数显示：`⚫ 提子: 3 | ⚪ 提子: 2`
- 按钮调整：
  - 保留：悔棋、重新开始、暂停、退出、设置
  - 新增：**Pass（虚手）按钮**
  - 新增：**终局/数目按钮**
  - 移除：挑衅按钮（围棋无此概念）、催促按钮（可选保留）
- Pass 后自动切换回合，双方连续 Pass 则进入数目阶段

### 5. 语音更新

重新运行 `generate_tts.py`（需修改），语音内容改为围棋场景：
- "轮到你了" / "请落子"
- "你被提了3子" / "提子"
- "虚手" / "双方虚手，进入数目"
- 保留幽默东北话版本

### 6. 保留不变的功能

以下功能直接复用，无需改动：
- 双屏防呆机制（MainActivity 强制主屏）
- 双 Activity 架构（MainActivity + GamePresentation）
- 退出同步机制（Home 键 / 返回键 → finishAffinity）
- 粒子动画系统（胜利/失败/蛋/花）
- 倒计时系统
- 游戏设置持久化
- SoundFX 音效播放
- GameState 单例

### 7. 动画调整

- 胜利动画：终局数目后对胜方播放
- 失败动画：终局数目后对负方播放
- 臭鸡蛋动画：保留（超时催促用）
- 鲜花动画：保留（Logo 点击）

---

## 文件改造清单

| 原文件 | 新文件 | 改动程度 |
|--------|--------|:---:|
| `GomokuGame.kt` | `GoGame.kt` | 🔴 重写（19×19, 气/提/劫/数目） |
| `GomokuView.kt` | `GoView.kt` | 🟡 大改（19×19, 交叉点, 星位） |
| `MainActivity.kt` | 同名修改 | 🟡 中改（Pass 按钮, 提子显示, 移除挑衅） |
| `GamePresentation.kt` | 同名修改 | 🟡 中改（同上） |
| `SoundFX.kt` | 同名修改 | 🟢 小改（资源 ID 引用） |
| `GameState.kt` | 同名修改 | 🟢 小改（引用 GoGame） |
| `generate_tts.py` | 同名修改 | 🟡 中改（语音内容） |
| `AGENTS.md` | 同名修改 | 🟡 中改（更新项目描述） |

---

## ⚠️ 注意事项

1. **不要用 PowerShell 管道编辑 .kt 文件**（会损坏 UTF-8 中文编码），用 `create_file` / `replace_string_in_file`
2. **不要用 `python -c` 传多行代码**，用独立 .py 脚本
3. **版本号从 V1.0 开始**，同时修改 MainActivity 和 GamePresentation 两个文件
4. **棋子必须画在交叉点上**，不是格子里——这是围棋和五子棋最大的视觉差异
5. **包名全局替换**时注意不要漏掉 `AndroidManifest.xml` 和 `build.gradle.kts`

---

## 参考

原项目文档：
- `AGENTS.md` — 架构说明、构建命令、常见陷阱
- `cl.md` — 完整版本更新日志
- GitHub: https://github.com/caucy2026/GomokuDualScreen
