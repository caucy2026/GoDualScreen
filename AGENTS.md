# GoDualScreen — AI 编程助手指南

> 双屏围棋 Android 应用。由 GomokuDualScreen (V4.4) 改造而来，版本从 V1.0 开始。

---

## 构建与安装

```powershell
$env:JAVA_HOME='D:\work\ai_code\tools\jdk-17\jdk-17.0.14+7'
D:\work\ai_code\tools\jdk-17\jdk-17.0.14+7\bin\java.exe `
  -classpath 'D:\work\ai_code\GoDualScreen\gradle\wrapper\gradle-wrapper.jar' `
  org.gradle.wrapper.GradleWrapperMain assembleRelease --no-daemon `
  -p D:\work\ai_code\GoDualScreen

adb install -r D:\work\ai_code\GoDualScreen\app\build\outputs\apk\release\app-release.apk
```

| 环境 | 路径 |
|------|------|
| JDK 17 | `D:\work\ai_code\tools\jdk-17\jdk-17.0.14+7` |
| Android SDK | `D:\work\ai_code\tools\android-sdk` (api 34) |
| Gradle | 8.5 / Kotlin 1.9.20 / compileSdk 34 / minSdk 31 |
| Python | `D:/Python312/python.exe` |
| Keystore | `D:\qianming\debug.keystore` (alias=`androiddebugkey`, pwd=`880203`) |
| GitHub | `git@github.com:caucy2026/GoDualScreen.git` |

---

## 架构

### 双 Activity 方案

| Activity | 物理屏 | 视角 | 启动方式 |
|----------|:------:|:----:|----------|
| `MainActivity` | Display 0（强制） | ⚫ 黑方 | 用户点击图标 |
| `GamePresentation` | Display 2 | ⚪ 白方 | `startActivity` + 反射 `setLaunchDisplayId` |

### 核心文件

| 文件 | 职责 |
|------|------|
| `GoGame.kt` | 19×19 棋盘，落子/气计算/提子/劫/数目，Pass，暂停状态机 |
| `GoView.kt` | 自定义 View：3D 棋子（交叉点）+ 粒子动画（胜/负/蛋/花）+ 星位 |
| `MainActivity.kt` | 主屏 Activity，游戏控制中心，倒计时，防呆逻辑 |
| `GamePresentation.kt` | 副屏 Activity，按钮回调转发到 MainActivity |
| `SoundFX.kt` | 落子音效 + TTS 语音播放 |
| `GameState.kt` | 单例：`game: GoGame` + `mainActivity: MainActivity?` |

---

## 围棋 vs 五子棋关键差异

| 特性 | 五子棋旧版 | 围棋新版 |
|------|-----------|---------|
| 棋盘大小 | 15×15 | 19×19 |
| 胜利条件 | 五连 | 地盘+提子多者胜 |
| 吃子 | ❌ | ✅ 无气之子被提走 |
| 劫 | ❌ | ✅ 禁止立即提回 |
| Pass | ❌ | ✅ 虚手 |
| 贴目 | ❌ | ✅ 白方贴 6.5 目 |
| 星位 | 5个 | 9个标准星位 |

---

## 主屏防呆机制

MainActivity 在 `onCreate` 极早期检测 `launchedDisplayId`，非主屏则迁回 Display 0。

---

## ⚠️ 编辑文件铁律

1. 不用 PowerShell 管道编辑 `.kt` 文件
2. 不用 `python -c` 传多行代码
3. 版本号同时修改 MainActivity 和 GamePresentation
4. 棋子画在交叉点上（围棋标准）
