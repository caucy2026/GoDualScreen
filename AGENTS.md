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

---

# Go3DGlobe — 3D 地球百科

> 基于 NASA WorldWind Android SDK 的 3D 地球，双屏架构（MainActivity + GlobeActivity），版本 V3.5.1。

---

## 构建与安装

```powershell
$env:JAVA_HOME='D:\work\ai_code\tools\jdk-17\jdk-17.0.14+7'
D:\work\ai_code\tools\jdk-17\jdk-17.0.14+7\bin\java.exe `
  -classpath 'D:\work\ai_code\Go3DGlobe\gradle\wrapper\gradle-wrapper.jar' `
  org.gradle.wrapper.GradleWrapperMain assembleRelease --no-daemon `
  -p D:\work\ai_code\Go3DGlobe

adb connect 192.168.3.46:5555
adb install -r D:\work\ai_code\Go3DGlobe\app\build\outputs\apk\release\app-release.apk
```

| 环境 | 路径 / 值 |
|------|------|
| 项目路径 | `D:\work\ai_code\Go3DGlobe` |
| JDK 17 | `D:\work\ai_code\tools\jdk-17\jdk-17.0.14+7` |
| ADB | `D:\work\allwin\platform-tools\adb.exe` |
| 设备 | WiFi ADB `192.168.3.46:5555`, Mali-G52 GPU, 4GB RAM |
| 包名 | `com.globe.dualscreen` |
| Keystore | `D:\qianming\debug.keystore` (alias=`androiddebugkey`, pwd=`880203`) |
| APK 输出 | `D:\work\ai_code\Go3DGlobe\app\build\outputs\apk\release\app-release.apk` |

---

## 图层栈（从上到下）

| 层级 | 图层 | 职责 |
|:---:|------|------|
| 9 | CapitalMarkersLayer | 84 首都⭐标记，ICON_SIZE=24 |
| 8 | CountryBordersLayer | 177 国界(always) + 标签(>500km) |
| 7 | GeographicFeaturesLayer | 大洲大洋(>3000km) / 山川河流(<2000km) |
| 6 | TiandituSatelliteLayer | z≤5 本地 Amap 缓存 / z6+ 在线(tk=token) |
| 5 | AmapSatelliteLayer | z≤5 本地 `fromFilePath` / z>5 透明 bitmap |
| 4 | NasaBlueMarbleLayer | 4K+8K 默认关闭，离网/限流时启用 |
| 3 | BackgroundLayer(NASA 2K) | 底图 2048×1024 |
| 2 | BasicElevationCoverage | 高程 |
| 1 | BasicTessellator | detailControl=30.0 |

---

## 缓存架构（零 token 启动核心）

| 层级 | 机制 | 说明 |
|------|------|------|
| L1 | RenderResourceCache 48MB | GPU 纹理，Mali-G52 上限 |
| L2 | HttpResponseCache 100MB | HTTP 瓦片缓存 |
| L3 | 预置瓦片 z1-z5 | assets→内部存储 1364 张 JPEG |

**Token 策略**：z≤5 零消耗 → z6+ 首次消耗 → HTTP Cache 后续命中。探测每 120s 一次。

---

## API 源

| | 天地图 | 高德 |
|------|------|------|
| URL | `t{s}.tianditu.gov.cn/...&tk={token}` | `webst0{s}.is.autonavi.com/...` |
| API Key | ✅ tk= | ❌ 无需 |
| 预置 | ❌ | ✅ z1-z5 |

---

## ⚠️ 关键约束

1. **禁 `file://` URL** — WorldWind 用 HttpURLConnection，不认本地协议 → `MalformedURLException`
2. **MercatorImageTile package-private** — 必须 `(tile as? ImageTile)?.imageSource = ...`
3. **每个 tile 必须覆盖 ImageSource** — 空 URL `""` 崩溃
4. **`ImageSource.fromFilePath()` 是唯一本地加载方式**
5. **NASA 纹理用 `BitmapFactory.decodeStream`**，不用 `fromResource()`
6. **RenderResourceCache ≤48MB**，detailControl=30

---

## 编辑铁律（Go3DGlobe 补充）

5. 版本号同步修改 `build.gradle.kts` + `activity_main.xml`
6. 绝对路径访问 `D:\work\ai_code\Go3DGlobe\...`
7. 瓦片坐标：`x=column, y=(1<<z)-1-row, z=level.levelNumber`
