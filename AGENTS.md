# AGENTS.md — OverDrive（比亚迪车机哨兵，中文定制版）

> 本文件是给 AI 编程助手 / 新贡献者看的工程入口。**动手改代码前先读完。**
> 仓库：`komes2018/Overdrive-release-cn`（fork 自 `yash-srivastava/Overdrive-release`）
> 本地路径：`C:/Users/Komes/WorkBuddy/byd/Overdrive-release-cn`

---

## 1. 这个项目是什么

**OverDrive** 是一款运行在比亚迪 DiLink 车机上的**高级哨兵 + 四路环视行车记录 + 车机智能助手** Android 应用。

- 包名 `com.overdrive.app`，**只支持 `arm64-v8a`**，跑在比亚迪 DiLink 3.0 / 4.0 / 5.0 车机上（Android 10 / 11 Automotive）。
- 所有数据 **100% 存车机本地**，不依赖比亚迪云端、无订阅费。
- 本仓库是 **中文深度定制 fork**（`-cn-sec` 后缀），主线工作 = 简体中文精校 + 国内网络/车机环境适配 + 企微推送。

规模：约 **787 个 Java/Kotlin 源文件**、261 个单元测试、25 个内置 Web 页面、33 种语言包、1223 次提交。

---

## 2. 技术栈与构建参数（`app/build.gradle.kts`）

| 项 | 值 | 备注 |
|---|---|---|
| AGP / Gradle / Kotlin | `8.13.2` / `8.13` / `2.0.21` | |
| compileSdk / buildTools | `36` / `36.0.0` | |
| **minSdk** | `28` | 需要 `Image.getHardwareBuffer()` 零拷贝相机路径 |
| **targetSdk** | **25（故意压低）** | 提高会让 `app_process` 守护进程受后台限制，**不要改** |
| versionCode / versionName | `102` / `50.0` | 可用 `-PoverdriveVersionCode=… -PoverdriveVersionName=…` 覆盖 |
| NDK / CMake | `26.1.10909125` / `3.22.1` | C++17 |
| ABI | **无 abiFilters**，用 `splits.abi { include("arm64-v8a") }` | 改打包方式前先想清楚 |
| Java | 源码/目标 11；**stubs 用 release 17 编译**；CI 用 JDK 17 | |
| 签名 | debug 与 release **共用同一个 keystore** | 见 §7 |

### ⚠️ 构建前会自动下载的依赖（`app/build.gradle.kts` 自定义 task）

| task | 作用 | 联网失败时 |
|---|---|---|
| `downloadOpenH264` | 下 `libopenh264 2.6.0` + 头文件到 `src/main/cpp/openh264/` | 只打印 ⚠，**不中断** |
| `downloadOpenCV` | 下 opencv-mobile 4.10.0 到 `src/main/cpp/opencv/` | 只打印 ⚠，CMake 走 `HAVE_OPENCV=0` 降级 |
| `downloadFastCam` | 确保 `libfast_cam_client.so` / `assets/dilink5/fast_cam_capture` / `fast_cam_bridge.h` | **这三个已入库，当前是 no-op** |
| `syncHebrewIwResources` | 把 `res/values-he` 镜像成 `values-iw` | 纯本地，不联网 |
| `compileBydautoStubs` | 把 `stubs-bydauto/` 打成 jar，`compileOnly` | 纯本地 |

> `src/main/cpp/opencv`、`src/main/cpp/openh264` 是下载产物且**已被 .gitignore 忽略**，
> 首次构建会联网；国内网络请配代理（见 §9）。

---

## 3. 目录地图

```
Overdrive-release-cn/
├── app/
│   ├── build.gradle.kts        ★ 构建心脏（含 5 个自定义 task）
│   ├── libs/classes.jar        sing-box libbox Java 绑定（当前未被构建引用，遗留）
│   ├── proguard-rules.pro      正式 keep 规则（378 行）
│   ├── proguard-rules-strip-logs.pro  剥文件日志变体（按 DaemonLogConfig 自动决定是否启用）
│   └── src/main/
│       ├── AndroidManifest.xml     大量 BYDAUTO_* 权限 + daemon 入口
│       ├── java/com/overdrive/app/   ← 主代码（见下表）
│       ├── cpp/                      ← native（见 §5.4）
│       ├── aidl/com/ts/avm/          DiLink 5.0 AVM AIDL 接口
│       ├── jniLibs/arm64-v8a/        预编译 so：cloudflared/singbox/tailscale/zrok/fast_cam_client/od
│       ├── assets/
│       │   ├── web/local/*.html      ★ 25 个内置 Web 页面
│       │   ├── web/shared/*.js|css   前端公共模块（ES5，车机 Chrome 58 下限）
│       │   ├── web/i18n/*.json       前端语言包 ×33
│       │   ├── server-i18n/*.json    守护进程推送文案 ×33
│       │   ├── dilink5/              fast_cam_capture + 1cam/4cam.xml
│       │   ├── models/               yolo11n.tflite / yolo26n.tflite
│       │   └── maps/, overlay/, byd/, notifications-categories.json
│       └── res/                      35 个 values-* 语言目录 + 201 drawable + 64 layout
├── stubs-bydauto/        ★ BYD 私有 SDK 桩（compileOnly，不进 APK）
├── scripts/i18n_check.py ★ 简中翻译质量闸
├── docs/I18N_GLOSSARY.md ★ 简中术语表（翻译唯一依据）
├── tools/build-libtailscale.sh + patches/
├── .github/workflows/    build-apk.yml（CI 出包）、i18n-check.yml
├── CHANGELOG.md / Readme.md / README_EN.md / CONTRIBUTING.md
└── DILINK5_CAMERA_CAPTURE_DISCOVERY.md   ★ DiLink 5.0 取流逆向文档，必读
```

### `com.overdrive.app` 各包速查

| 包 | 文件数 | 职责 |
|---|---|---|
| `ui/` | 89 | Activity / Fragment（15 个）/ Adapter / ViewModel；`ui/daemon/` 是各守护进程控制器 |
| `automation/` | 78 | 自动化规则引擎（trigger/condition/action） |
| `byd/` | 70 | ★ 车辆数据接入（私有 HAL SDK + VHAL + 云），含 `byd/cloud` 25 个文件 |
| `server/` | 65 | ★ 内置 HTTP 服务 + 60 余个 `*ApiHandler` |
| `surveillance/` | 47 | 哨兵引擎、GPU 录制管线、YOLO 检测 |
| `roadsense/` | 47 | 路面病害（坑洼/减速带）识别与众包 |
| `daemon/` | 38 | ★ 5 个 `app_process` 守护进程 |
| `camera/` | 35 | 相机后端（AVMCamera / Binder / QCarCam） |
| `telegram/` | 33 | 车机侧 → 机器人的**出站**通知 |
| `monitor/` | 30 | ACC / 挡位 / 电池 / 充电 / 网络 等监视器 |
| `navmap/` | 24 | 车机内导航地图（MapLibre） |
| `notifications/` | 21 | `NotificationBus` + sinks（Push / Telegram / **WeCom** / History / Log） |
| `trips/` | 19 | 行程检测、能耗、驾驶评分 |
| `services/` | 16 | 前台服务：保活、按键映射、座舱音频、车控执行器等 |
| `launcher/` | 14 | ★ 守护进程 / Tailscale / sing-box / zrok / cloudflared 启动器 |
| `mqtt/` | 10 | Home Assistant 自动发现 + 遥测发布 |
| `config/` | 8 | ★ `UnifiedConfigManager`（配置真源）、`DaemonConfig`、`ConfigManager` |
| `storage/` | 7 | 存储位置（内置/SD/USB）与配额回收 |
| `wecom/` | 1 | ★ **本 fork 新增**：企业微信群机器人 |
| 其余 | — | `updater/ genai/ communication/ shell/ power/ auth/ geo/ logging/ onboarding/ overlay/ proximity/ receiver/ recording/ telemetry/ abrp/ bridge/ ...` |

---

## 4. 运行架构：三层进程

```
   手机浏览器 / Home Assistant / Telegram / 企微
                    ▲
        HTTP :8080  │  WS   │ MQTT  │ 出站推送
                    │
   ┌────────────────┴──────────────────────────────┐
   │  守护进程层（app_process 独立进程，UID 2000/1000）│
   │  byd_cam_daemon      CameraDaemon              │ ← HTTP :8080 + :19876 + :19877
   │  acc_sentry_daemon   AccSentryDaemon           │
   │  sentry_daemon       SentryDaemon       :19879 │
   │  telegram_bot_daemon TelegramBotDaemon  :19880 │
   │  sentry_proxy        GlobalProxyDaemon  :8119  │
   │  —                   BydEventDaemon     :19878 │
   └────────────────▲──────────────────────────────┘
        localhost TCP JSON（端口见下表）
   ┌────────────────┴──────────────────────────────┐
   │  App 主进程（UID 10xxx）                        │
   │  MainActivity + 15 Fragment + 前台服务          │
   │  自动化引擎 / 通知 / MQTT / 遥测采集             │
   └───────────────────────────────────────────────┘
```

### 端口表（改端口前先全仓搜一遍）

| 端口 | 服务 |
|---|---|
| **8080** | CameraDaemon 的 HTTP / Web UI / H.264 流 |
| 19876 | CameraDaemon 控制（TcpCommandServer） |
| **19877** | **主 IPC 通道**：app 进程 → daemon 委托特权操作（`DaemonIpcClient` → `SurveillanceIpcServer`） |
| 19878 | BydEventDaemon 事件推送（车门/充电/雷达） |
| 19879 | SentryDaemon 控制 |
| 19880 | TelegramBotDaemon（出站通知入口） |
| 8119 | sing-box 本地代理 |

### 关键文件/配置路径（车机上）

| 路径 | 说明 |
|---|---|
| `/data/local/tmp/overdrive_config.json` | **配置唯一真源**，sticky 目录，仅 UID 2000 可原子写。app 进程写入**必须**经 19877 IPC 转给 daemon |
| `/data/local/tmp/start_cam_daemon.sh` 等 | 看门狗脚本，`while true` + 崩溃退避重启（`acc_sentry` 版不设上限） |
| `/data/local/tmp/wecom_config.properties` | 企微 `webhook_url`，**每次推送实时读取，改完热生效** |
| `/data/local/tmp/byd_telemetry_snap.json` | 遥测快照（另有 `/storage/emulated/0/Overdrive/` 下同名副本） |
| `/data/local/tmp/overdrive_recordings_h2` | 录像索引（纯 Java H2 文件库） |
| `/storage/emulated/0/Overdrive/{recordings,surveillance,proximity,trips}` | 录像落盘 |

---

## 5. 核心子系统

### 5.1 车辆数据接入（`byd/`）

三条路径，**本地 SDK 优先，云仅填空**：

1. **BYD 私有 HAL SDK**（主）— `BydDataCollector.java`（~18000 行，全仓最大）通过 `BydDeviceHelper` 反射调用 `android.hardware.bydauto.*`（Bodywork/Speed/Engine/Statistic/Charging/Instrument/Gearbox/AC/Tyre/DoorLock/Radar/…）。
   DiLink 5.0 上真类不在 classpath，靠 `dilink5/Dilink5SdkInjector.java` 运行时注入 `/system/app/BydDataCollect/BydDataCollect.apk` 的 dex。
2. **VHAL / CarProperty** — `CarPropertyBridge.java` 绕 AMS 拿 `com.byd.car.property` 的 Binder（**注意不是标准 `android.car`**，全仓零引用）。
3. **比亚迪云** — `byd/cloud/`：`BydCloudClient`（登录/远控/智能充电，CN 域名 `dilinksuperappserver-cn.byd.auto`）、Bangcle/WBSK 加解密、`BydCloudMqttSubscriber`（EMQ MQTT v5 推遥测）。

兜底规则集中在 `BydDataCollector.mergeCloudData()`：只有 `socHalSucceeded==false` 或熄火或 NaN 时才用云 SOC；云数据有 5min(锁)/10min(遥测) 新鲜度阈值。

对外暴露：`GET /api/vehicle/state`（`server/VehicleControlApiHandler.java`）+ 三处磁盘 JSON 快照。

### 5.2 相机与录像（`camera/` `recording/` `streaming/`）

| 平台 | 后端 | 关键类 |
|---|---|---|
| DiLink 3.0/4.0（SD665/690/6125，Android 10） | 反射 `android.hardware.AVMCamera` | `camera/PanoramicCameraGpu.java`（~7000 行） |
| DiLink 3/4 备选 | Binder 相机服务 `bydcameramanager` / `bmmcameraserver` | `camera/BinderCameraBackend.java` |
| **DiLink 5.0（SA8155P `msmnile`，Android 11 over QNX）** | **Qualcomm AIS / QCarCam**：`dlopen("/vendor/lib64/libais_client.so")` | `camera/dilink5/DiLink5QCarCamBackend.java`、`TsAvmCoordinator.java` |

- DiLink 5.0 上 `android.hardware.AVMCamera` 已被**移除**，Camera2 拒绝非系统应用，360 影像走 SurfaceFlinger 下层 overlay（`screencap` 全黑）。唯一可行解是 QCarCam：**1920×1300 UYVY @ 30.0 FPS**，用户态免 root。详见 `DILINK5_CAMERA_CAPTURE_DISCOVERY.md`。
- 采集→编码链路：`PanoramicCameraGpu` → `surveillance/GpuSurveillancePipeline.java`（总调度）→ `GpuMosaicRecorder`（4 路拼 2×2）/ `HardwareEventRecorderGpu`（MediaCodec + MediaMuxer，`.mp4.tmp` → rename）/ `GpuDownscaler` → `SurveillanceEngineGpu`（YOLO）。
- 平台判定统一走 `DiLink5QCarCamBackend.isSupported()`，再选车型 profile（`sealion7` / `seal`）。

### 5.3 哨兵与近距离（`surveillance/` `proximity/`）

- `SurveillanceEngineGpu.processFrameV2()`：六阶段分象限运动检测 + YOLO → `ActorTracker` + `Severity{NOTICE,ALERT,CRITICAL}` → 触发 `event_` 前缀录像。
- `proximity/ProximityGuardController.java` 状态机 `IDLE → MONITORING → RECORDING → POST_RECORD`，接原车 8 颗倒车雷达，RED(<0.5m)/YELLOW(0.5–0.8m) 分级，500ms 防抖 + 预录环形缓冲。
- 录像文件前缀即分类器：`event_`（哨兵）、`cam_`（行车）、`proximity_`（近距离）、`dvr_`（OEM 镜像）、`manualClip`。

### 5.4 Native（`app/src/main/cpp/`）

| 文件 | 作用 |
|---|---|
| `surveillance/native_motion.cpp` | 8×8 网格运动检测 + NEON |
| `surveillance/motion_pipeline_v2.cpp` | V2 六阶段流水线 |
| `surveillance/texture_tracker.cpp` | NCC 模板跟踪，NCC<0.40 唤醒 YOLO |
| `camera/HardwareBufferTextureBinder.cpp` | AHardwareBuffer → EGLImage → `GL_TEXTURE_EXTERNAL_OES` |
| `camera/qcarcam_bridge.cpp` | DiLink5 桥：连 `fast_cam_capture` IPC，NEON UYVY→RGBA |
| `camera/hook_qcarcam.cpp`、`dilink5_cam_sidecar.cpp` | **未进 CMakeLists** 的游离源码（LD_PRELOAD hook / 独立守护版） |

CMake **只产出 `libsurveillance.so`**；`fast_cam_client` 是 IMPORTED 预编译；`libhook_qcarcam.so` 在本仓库既无编译目标也无预编译产物。

### 5.5 自动化（`automation/`）与通知（`notifications/`）

- `Automation` = `triggers + conditions(AND/OR，可嵌套 8 层) + delay + actions + elseActions + mode(automatic/manual/disabled)`；由 `AutomationQueue`（DelayQueue，上限 1024）调度；存 `/data/local/tmp/.automations/config.json`（带 `.bak`/`.tmp` 原子写与损坏回滚）。
- Action 共 ~110 个：`VehicleControlAction`（车控）、`ApiAction`（~75 个 HTTP 服务/云控）、`NotificationAction`、`MqttPublishAction`、`ShellAction`、变量类、控制流（If/Loop/WaitUntil…）。
- `notifications/NotificationBus.java`：进程内 pub/sub + 单线程 executor 扇出，启动窗口用 `preSubscribeBuffer`(16) 保证不丢不重。
- Sinks：`HistorySink` → `LogSink` → `PushSink`(WebPush/VAPID) → `TelegramSink` → **`WeComSink`**。
- **约定**：`telegram/` 包 = 车→机器人的**出站**通知；`daemon/telegram/` = 机器人→车的**入站**命令。

---

## 6. ★ 本 fork 相对上游的中文定制（**动这些地方要格外小心**）

| 定制 | 位置 | commit |
|---|---|---|
| **企微机器人推送** | `wecom/WeComNotifier.java` + `notifications/sinks/WeComSink.java` | `9c2bc1b7` |
| 企微消息纯文本化（兼容个人微信） | `WeComNotifier` | `0b335dae` |
| MQTT / Home Assistant 实体名全量汉化 + 固定 `entity_id` | `mqtt/TelemetryFieldCatalog`、`VehicleControlCatalog` | `0eac7795`、`747c478e` |
| 简中译文精校（web 3703 条 / server 1206 条 / 原生 1289 条） | `assets/web/i18n/zh-CN.json`、`assets/server-i18n/zh-CN.json`、`res/values-zh-rCN/` | `2a9ecee9`、`c9f49865`、`441f5d34` |
| **i18n 质量闸**（脚本 + 繁体字表 + CI） | `scripts/i18n_check.py`、`scripts/i18n_trad_chars.txt`、`.github/workflows/i18n-check.yml` | `c9f49865` |
| **简中术语表**（翻译唯一依据） | `docs/I18N_GLOSSARY.md` | — |
| **固定签名证书**（车机可 `pm install -r` 覆盖升级） | `app/build.gradle.kts` + CI 里校验 SHA-256 `adc52cfa…e293` | `24b978ec` 起一串 |
| 中文 README / 零流量远程方案 | `Readme.md` | `817ba25f`、`46efc173` |

---

## 7. 开发硬约束（违反了会挨骂）

### i18n（最高频踩坑区）

1. **翻译前必读 `docs/I18N_GLOSSARY.md`**。固定译法：车机 / 行车记录仪 / 摄像头 / **驻车哨兵**（sentry，与 surveillance→「监控」严格区分）/ 挡位 / 标定 / 守护进程 / 阈值 / 字段 / 应用 / 恢复 / 默认 / 设置。品牌与技术名不译（OverDrive、BYD、DiLink、MQTT、Tailscale、Home Assistant、ABRP、HAL、AVM、SOH、SOC…）。
2. **`zh-CN` 是人工维护的**，已主动退出 NLLB-200 机翻流水线（`zh-CN.json` 的 `_meta.note` 写明「由 OverDrive 中文社区团队深度精校维护」）。**不要用机翻批量覆盖 zh-CN**。
3. 改了英文 key，必须同步 3 套：`assets/web/i18n/zh-CN.json`、`assets/server-i18n/zh-CN.json`、`res/values-zh-rCN/*.xml`。
4. **`res/values-zh/` 必须与 `res/values-zh-rCN/` 逐字节一致**（Android 会在 `zh-Hans`/`zh-SG` 命中裸 `values-zh`）。
5. 提交前跑 `python scripts/i18n_check.py`（`--strict` 把警告也当错误）。当前基线：**errors=0 warnings=3**（`log_entry_default_tag` + 40 个 web 陈旧键，属已知存量，别越改越多）。
6. 占位符（`%1$s`、`${var}`、`{{var}}`）必须与英文**完全一致**，且**不许被中文化**（如 `<名称>`、`${var:变量名}` 直接判错）。
7. 繁体/港台用词残留会被警告，参 `scripts/i18n_trad_chars.txt`。

### 代码

8. **`targetSdk` 保持 25**，不要为了「跟上时代」改高——守护进程会失效。
9. **不要动 `stubs-bydauto/` 的 compileOnly 性质**，也不要把 `android.hardware.bydauto.*` 打进 APK：车机上真类由 boot classloader 优先加载，打进去会变成假的 mock 实现。
10. **不要改 IPC 端口号**（19876–19880），分布在上十几个类里。
11. **`app/libs/classes.jar` 是 sing-box libbox 绑定，当前未被构建引用**，属遗留文件，别顺手删也别顺手加依赖。
12. 根目录 `proguard-rules.pro`（262 行）是**旧版遗留、未被任何 buildType 引用**，真正生效的是 `app/proguard-rules.pro`。
13. **新增文件记得同步进构建**（本仓是单 `:app` 模块，Java/Kotlin 自动纳入；但新增 native 源文件必须同步改 `app/src/main/cpp/CMakeLists.txt`）。
14. Web 前端 **只能用 ES5**（车机 Chrome 58 / Android 7.1 下限），不要写 `const`/箭头函数/`async`。
15. 改 CI 签名相关配置前先想清楚：**目的是让每次构建指纹一致**，否则车机无法 `pm install -r` 覆盖升级。

### Git

16. commit message 用 **Conventional Commits + 中文 subject**，例如 `fix(wecom): 解耦企微直连推送与 Telegram 门控`。
17. 本仓当前配了 `http.proxy=http://192.168.31.1:7890`（见 §9）。**这是网络受限时的临时设置，不是常态**；
    网络恢复后建议 `git config --unset http.proxy && git config --unset https.proxy`，否则直连环境反而会变慢。

---

## 8. 常用命令

```bash
# 构建（首次会联网下 OpenH264 / opencv-mobile）
./gradlew assembleDebug            # 或 gradlew.bat assembleDebug（Windows）

# 测试
./gradlew test                     # 261 个单元测试

# i18n 质量闸（改翻译必跑）
python scripts/i18n_check.py       # 只卡结构性错误
python scripts/i18n_check.py --strict

# 其他可用 task
./gradlew :app:checkSurveillanceDeps
./gradlew :app:downloadOpenCV
./gradlew :app:extractWebAssets    # 把 assets/web 推到车机 /data/local/tmp/web
```

- release / braveheart 变体的 `UPDATE_CHANNEL` 分别为 `alpha` / `braveheart`；braveheart = release 但**保留日志**并开 `LOG_CAPTURE`。
- CI：`.github/workflows/build-apk.yml`（push main 触发，产出 `Overdrive-v48.19-cn-sec.apk` 并发 Release）；`i18n-check.yml` 独立且**永不阻塞 APK 构建**。

---

## 9. 本地环境注意事项（仅在网络受限时需要）

- 出网正常时**无需任何代理**。若出现访问 GitHub / Gradle / ciscobinary 极慢或卡死（网络受限），
  才走代理 `http://192.168.31.1:7890`：
  - git：`git config http.proxy http://192.168.31.1:7890`（本仓已设，不需要可 `git config --unset`）；
    临时用法 `git -c http.proxy=... -c https.proxy=... clone ...`
  - shell：`export http_proxy=http://192.168.31.1:7890 https_proxy=http://192.168.31.1:7890`
  - Gradle 联网下载还需 `~/.gradle/gradle.properties`：
    `systemProp.http.proxyHost=192.168.31.1` / `systemProp.http.proxyPort=7890`（https 同理）
  - 经验值：直连卡在几 MB 不动时，走代理 100MB 仓库约 3 分钟克隆完。
- Windows + Git Bash；路径含中文/空格时加双引号。
- 无 `local.properties`、无本地 keystore → 本地构建走默认 debug 签名。

---

## 10. 排障速查

| 现象 | 先查 |
|---|---|
| 车机 Web 打不开 / 8080 不通 | `byd_cam_daemon` 是否活着：`ps -A \| grep byd_cam_daemon`；看 `/data/local/tmp/cam_daemon.log` |
| 守护进程反复重启 | 看门狗脚本 `/data/local/tmp/start_*.sh`；`exit 137/134` 时脚本会清 `*.lock` 残留 |
| 遥测全是 0 / SoC 卡住不动 | `BydDataCollector` 的 `socHalSucceeded` 分支 + `mergeCloudData()` 兜底；看 `/data/local/tmp/byd_telemetry_snap.json` 时间戳（>60s 视为过期） |
| DiLink 5.0 黑屏 | `libhook_qcarcam.so` / `fast_cam_capture` 是否解到 `/data/local/tmp/` 并 `chmod 755`；`DiLink5QCarCamBackend.ensureHookLibraryExtracted()` |
| 企微收不到消息 | `/data/local/tmp/wecom_config.properties` 里 `webhook_url` 对不对（改完热生效）；`surveillance.*` 走 `WeComNotifier` 直投而非 `WeComSink` |
| 车控提示「车辆行驶中」 | `DrivingSafetyGuard.resolveGear()` 兜底读 `BydVehicleData.gearMode` |
| 门锁状态反了 | BYD SDK `1=UNLOCKED, 2=LOCKED`，Web API `1=LOCKED`，转换在 `cloudLockToApi()` |
| BYD 云登录报 `String index out of range: -1`，或想用手机号登录 | 标识符打码见 `BydCloudConfig.maskIdentifier()`；CN 登录的 `loginType` 由 `bydCloud.cnLoginType` 控制（`auto`=含 `@` 走 0 邮箱、否则 1 手机号），改 `/data/local/tmp/overdrive_config.json` 即可实测其它值 |

---

## 11. 延伸阅读（按优先级）

1. `Readme.md` — 功能全景与车机安装步骤（**首次装完必须长按音量减 5 秒硬重启**）
2. `DILINK5_CAMERA_CAPTURE_DISCOVERY.md` — DiLink 5.0 取流原理，动相机必读
3. `docs/I18N_GLOSSARY.md` — 翻译必读
4. `CONTRIBUTING.md` — 上游贡献流程
5. `CHANGELOG.md` — 33KB，近 300 条改动的详细记录（意大利语/英语混排）
6. `app/src/main/java/com/overdrive/app/monitor/CHARGING-POWER-INVARIANTS.md` — 充电功率不变量
