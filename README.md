# 宋词择律 OCR 提示器

> **这是公开的「纯代码」仓库**：只包含源码、测试与工具链，**不含任何游戏数据**。
> `data/` 目录刻意只有格式说明与示例，因为游戏导出的原始配置属于游戏素材。
> 详见「[数据来源与许可](#数据来源与许可重要)」。

宋词择律类玩法的**读屏辅助悬浮窗**：识别屏幕上给出的词句首句，查表得到对应词牌，
在正确选项上画框。

> **免责声明**：本项目是第三方的**屏幕读取**辅助工具，不修改游戏数据、不注入游戏进程、
> 与游戏开发/发行方**无任何隶属关系，也未获其授权或背书**。使用第三方辅助工具可能
> 违反游戏用户协议并带来**账号风险，请自行判断、风险自负**。
> 本项目**不包含任何游戏素材**（原始配置 JSON、图片、音频、字体、代码均不入库），
> 只包含自行绘制的界面、生成索引的代码，以及一份**公有领域**宋词文本的最小索引
> （详见「[数据来源与许可](#数据来源与许可重要)」）。
> 「无悔华夏」等名称归其权利人所有，此处仅用于说明兼容性。
>
> **隐私**：本应用**不联网、不采集、不上传任何数据**，识别完全在设备本地完成。
> 唯一的对外通信来自 Google ML Kit SDK 自身（发送 API 性能/使用指标，**不含图像与识别结果**），
> 详见「[开源许可与第三方依赖](#开源许可与第三方依赖)」。

## 目录结构

```
├── data/                        # 索引数据源（游戏导出，**不入库**；见 data/README.md）
│   ├── README.md                #   格式说明 + 如何自备数据
│   ├── songci-verse-config.example.json   # 结构骨架（不含真实词句）
│   ├── songci-poet-config.example.json
│   ├── SongCiVerseConfig.json   #   ⚠️ 自备（已 gitignore）
│   └── SongCiPoetConfig.json    #   ⚠️ 自备（已 gitignore）
├── tools/gen_verse_index.py     # 生成 + 校验 app/src/main/assets/verses.json（**需要 data/**）
├── tools/tune_matcher.py        # 匹配算法复算 / 阈值调参（与 Kotlin 同式）
├── tools/verify_all.py          # 一键自检（索引 + 匹配 + Gradle 版本一致性）
├── tools/check_index_committed.py # CI 用：重新生成的索引必须与提交版本一致
├── tools/check_apk.py           # 发布包校验（签名 / 权限 / ABI / assets / 模型）
├── debug.jks                    # 本地调试签名（口令固定 android，仅调试，入库）
├── docs/specs/                  # 设计文档
├── .github/workflows/android.yml# CI：复算 → 单测 → 签名打包
└── app/                         # Android 工程（Kotlin）
    └── src/main/java/com/songci/assist/
        ├── MainActivity.kt      # 引导页：授权 + 开始/停止 + 状态
        ├── CaptureService.kt    # 前台服务：MediaProjection → ImageReader 取帧
        ├── FramePipeline.kt     # 节流(2fps) + 两段式调度（纯逻辑，可单测）
        ├── MlKitOcrEngine.kt    # ML Kit 中文识别（内置模型，离线）→ 文本块+包围盒
        ├── VerseIndex.kt        # 索引载入 + 首句规范化 + 精确查表 + 最近邻
        ├── Matcher.kt           # 挑「首句行 / 选项气泡」（纯函数，可单测）
        ├── OverlayView.kt       # 画高亮框 + 箭头 + 顶部状态条
        ├── OverlayService.kt    # 悬浮窗生命周期（两个独立 window）
        └── Config.kt            # 全部可调参数（阈值、裁剪区、帧率…）
```

## 快速开始

### 只想构建 App（无需任何数据准备）

`app/src/main/assets/verses.json`（词句索引）**已随仓库提交**，所以克隆下来就能直接构建、跑检查：

```bash
# 需要 JDK 17 + Android SDK（platform 34 + build-tools 34.0.0）
./gradlew testDebugUnitTest      # JVM 单测，无需设备
./gradlew assembleDebug          # 打 debug 包
./gradlew assembleRelease        # 打 release 包（配置了 keystore.properties 才签名）

python tools/verify_all.py       # 一键自检：索引校验 + 匹配复算 + CI/版本一致性
```

### 只有"重新生成索引"才需要自备数据

本仓库**不分发游戏数据**（`data/` 下是游戏导出的配置，属游戏素材），
所以下面这一步**不能跳过**，其余命令都不需要数据：

```bash
# 0. 自备数据：把游戏导出的两个 JSON 放进 data/（结构与字段见 data/README.md）

python tools/gen_verse_index.py   # 生成 + 校验词句索引 → app/src/main/assets/verses.json
python tools/tune_matcher.py      # 匹配调参（阈值扫描表，决定是否改 Config.SIMILARITY_THRESHOLD）
```

索引校验：99 条词句 / 40 个词牌 / 首句唯一 / 不含"需特殊名臣解锁"的 2 条。

### 哪些命令需要数据（已逐条实测）

| 命令 | 需要 `data/` | 说明 |
|---|---|---|
| `./gradlew assemble*` / `testDebugUnitTest` | ❌ | 索引已提交，构建不碰 `data/` |
| `python tools/verify_all.py` | ❌ | 走 `--check`，只校验已提交的索引 |
| `python tools/gen_verse_index.py --check` | ❌ | 只校验已生成的索引 |
| `python tools/tune_matcher.py` | ❌ | 只读 `verses.json` 复算 |
| `python tools/check_index_committed.py` | ❌ | 只比对已提交的索引 |
| **`python tools/gen_verse_index.py`** | ✅ | **唯一需要数据**的命令（从原始配置生成索引） |

> 数据缺失时 `gen_verse_index.py` 不会抛 traceback，而是提示如何自备。
> CI 也是同样策略：有 `data/` 才重新生成，否则只 `--check`。
> 索引**内容**的合法性另由 `CommittedIndexTest`（6 个 JVM 用例）守护，不依赖数据。

### 本地构建的两个前提

* `local.properties` 里的 `sdk.dir` 要指向本机 Android SDK（该文件已 gitignore，可自行修改）。
* 需要 JDK 17（AGP 8.5.2 要求）与 Android SDK（platform 34 + build-tools 34.0.0）。
  `gradle/wrapper/gradle-wrapper.jar` 已随仓库提交，直接 `./gradlew` 即可，
  首次运行会自动下载 Gradle 8.7（约 134 MB）。

### 本工程路径含中文（重要）

本工程最初在**含中文的目录**（Windows 上的 `E:\Desktop\宋词辅助`）下开发，而 AGP 默认会直接报错
（`Your project path contains non-ASCII characters`）。工程里已经用
`android.overridePathCheck=true` 关掉该检查，实测 Windows + JDK 17 + Gradle 8.7 +
AGP 8.5.2 全流程可构建（单测全过、release 打包成功）。
若在别的中文路径下遇到离奇失败，优先怀疑这一项；想避开的话，把仓库克隆到纯 ASCII 路径即可。

## 目标设备

Android 16（API 36）；`minSdk 26` / `targetSdk 34`。`targetSdk 34` 的包在 Android 16 上
以**兼容模式**运行，API 36 针对 `targetSdk≥36` 的新限制不适用 —— 此点待真机验证。

## 匹配算法（两端同式，改一处必须同步改另一处）

* 匹配键 = 首句（正文到第一个断句符）去掉**全部标点与空白**后的字符串
  （`VerseIndex.normalize` / `gen_verse_index.py:first_clause`）
* 相似度 `sim(a,b) = 1 − Levenshtein(a,b) / max(len(a), len(b))`，阈值
  `Config.SIMILARITY_THRESHOLD = 0.72`
* 两段式：顶部 `y∈[0,0.45H]` 找首句 → 命中后中部 `y∈[0.30H,0.80H]` 找文本等于词牌的块
* 首句被 OCR 拆行时按 Y 相邻拼接（最多 2 行）；同一行被拆成左右两段时按 X 拼接。
  两条防回归规则见 `Matcher.sameLine` / `Matcher.mergeAdjacent` 的注释：
  **同排归并要求 X 基本不相交**（避免把同排的 UI 标签并进句子），
  **上下拼接排除"一方被另一方横向包住"**（避免小标签粘进首句把相似度顶到阈值以下）

### 实测结论（`python tools/tune_matcher.py` 可复算）

| 注入错字数 | 提示但判错词牌（误报） | 提示覆盖率 | 不提示 |
|---|---|---|---|
| 0（精确） | 0 | 100%（99/99 精确查表） | 0 |
| 1（1980 例） | **0** | 100% | 0 |
| 2（495 例） | **0** | 98.6% | 1.4%（3 条 5~6 字短句） |
| 3（198 例） | **0** | 86.9% | 13.1%（16 条短句） |

（数值随样本量略有浮动，跑 `tune_matcher.py` 的阈值扫描表可看 2000 例规模的结果。）

* **任何错字量下都没有出现过"画框但画错"**：最近邻给出的词牌始终正确，低相似度的结果是
  **不提示**（符合"宁可不提示，也不误报"）。阈值扫描 0.55~0.85 实测误报恒为 0.00%。
* ⚠️ **与设计文档 §2 的预研结论不一致**：预研写的是"1/2/3 个错字最近邻 100% 命中正确词牌"，
  但那是 `difflib.SequenceMatcher` + 不同的错字模型。按设计文档 §2 约定的**归一化编辑距离**
  复测，短首句 + 多个随机整字替换时会掉到阈值以下（**覆盖率**下降，不是误报）。
* 3 个随机错字已接近把短句随机化，覆盖率靠**下调阈值**换：阈值 0.65 时 3 错字覆盖率 97.0%，
  0.60 时 98.7%，且实测仍为 0 误报。真机若发现"该提示却没提示"，优先调小
  `Config.SIMILARITY_THRESHOLD`，并同步改 `tools/tune_matcher.py` 里的同名常量。

## 真机手测清单

- [ ] 1. 安装 → 三项授权（悬浮窗 / 通知 / 录屏）→ 悬浮球出现
- [ ] 2. 进游戏择律页：首句出现后 **≤1 s** 出框，框在正确气泡上
- [ ] 3. 连续 **20 局**统计命中率 / 误报率（目标：误报 0，命中 ≥ 95%）
- [ ] 4. 选完后框自动消失，不残留
- [ ] 5. 连续 30 min：耗电、温度、是否被系统杀
- [ ] 6. 息屏 / 切后台 / 旋转后恢复正常
- [ ] 7. 停止投屏后再启动，能恢复工作
- [ ] 8. 悬浮球：单击暂停/继续、拖动移动（松手吸附边缘、位置持久化）、长按退出
- [ ] 9. 顶部状态条开关生效（关掉只留高亮框）

## 开源许可与第三方依赖

本项目以 **Apache License 2.0** 发布，见 [LICENSE](LICENSE)。

| 依赖 | 许可 | 说明 |
|---|---|---|
| AndroidX（core-ktx / appcompat / activity-ktx / lifecycle-service） | Apache 2.0 | 可自由分发 |
| Material Components | Apache 2.0 | 可自由分发 |
| kotlinx-coroutines-android | Apache 2.0 | 可自由分发 |
| JUnit 4 / org.json | EPL 1.0 / Public Domain | 仅测试用 |
| **`com.google.mlkit:text-recognition-chinese`** | **Google 专有** | **闭源 SDK**，见下方说明 |

> ⚠️ **关于 ML Kit（使用前请读）**：OCR 使用 Google ML Kit 的**专有 SDK**
> （随包分发的二进制 + 模型文件）。它**不是**开源组件，使用受
> [ML Kit 服务条款](https://developers.google.cn/ml-kit/terms) 约束。
> 条款全文只有三条，与本项目相关的只有一条：**不得对 SDK 做反向工程或试图提取其源代码**。
> 本项目只**调用** SDK，不含其源码或反编译产物，符合该条款。
> 条款**不禁止**把 ML Kit 随应用一起分发（这本就是官方推荐的 bundled 用法）。
>
> **隐私披露（条款要求分发者有义务告知）**：ML Kit 会向 Google 发送
> **API 的性能与使用指标**；**输入的图像与识别结果完全在设备上处理，不会上传**。
> 本项目本身**不联网、不采集、不上传任何数据**，无任何服务器。
>
> 本项目**不做任何加固/混淆之外的保护**，也不声称对复用者承担合规责任 ——
> 若你要在别的产品里复用本项目的 OCR 部分，请自行确认符合上述条款。

### 数据来源与许可（重要）

本仓库**不分发游戏数据**。

| 文件 | 是否入库 | 说明 |
|---|---|---|
| `data/SongCiVerseConfig.json`<br>`data/SongCiPoetConfig.json` | ❌ **已 gitignore** | **游戏导出的配置，属于游戏素材**。要重新生成索引请自行从自己的设备导出，格式见 [data/README.md](data/README.md) |
| `data/*.example.json` | ✅ | 只含**结构骨架与字段说明**，不含真实词句 |
| `app/src/main/assets/verses.json` | ✅ | 上述数据的**最小投影**（首句 → 词牌 → 词人姓名，约 20 KB）。App 运行必需，离线可用 |

**为什么保留 `verses.json`**：App 要靠它查表，没有它就无法运行；而它只是
「词句首句 → 词牌名 → 词人」这样的事实性对应关系，且词句本体本身属**公有领域**
（作者均为唐宋人）。游戏素材（原始配置、图片、音频、字体）一概不入库。

**重新生成索引**（仅在你需要时）：

```bash
# 1) 把游戏导出的两个 JSON 放进 data/（结构见 data/README.md）
# 2) 生成 → app/src/main/assets/verses.json
python tools/gen_verse_index.py
# 只校验已生成的索引（不需要 data/）
python tools/gen_verse_index.py --check
```

`verses.json` 的**结构与关键性质**由 JVM 测试 `CommittedIndexTest` 守护
（条数、id/首句唯一、字段合法、每条首句能精确查回自己、以及词牌名集合满足
模糊匹配的安全前提）—— 因为 CI 没有游戏数据，跑不了 `gen_verse_index.py`。

### 免责声明

* 这是**第三方辅助工具，与游戏官方无关**，未获得游戏厂商授权或背书；
* 它只做「读屏 → 查表 → 画框」，不修改游戏、不注入进程、不模拟操作；
* 在游戏中使用读屏/辅助工具**是否违反游戏用户协议，由使用者自行判断与承担**；
* 软件按「现状」提供，不附带任何担保（详见 Apache 2.0 第 7、8 条）。

## 分发

GitHub Actions 产出 `app-debug.apk`（自测）与 **`app-release.apk`（已签名，分发用）**。
CI 里 4 个 Secrets 配好后自动签名；没配则出未签名包。

### 签名密钥（已生成好）

| 用途 | 文件 | 口令 | 是否入库 |
|---|---|---|---|
| **分发签名** | `app-release.jks`（工程根） | 见下方说明 | ❌ 绝不入库 |
| 本地调试安装 | `debug.jks`（工程根） | `android` | ✅ 入库（仅调试用） |

`app-release.jks`：alias `songci`，RSA 4096 / SHA256withRSA / 有效期 10000 天。

证书指纹（SHA-256，发布包核对用）：

```
7A:7B:9D:FF:15:16:43:6F:C2:D4:2F:60:08:EB:7D:CC:BC:6E:AB:EB:4F:68:D0:DF:DB:24:8A:16:E6:92:2A:B4
```

> ⚠️ **`app-release.jks` 必须离线备份**（连同口令）。丢了就无法覆盖升级，用户只能卸载重装。
>
> Secrets 已配置：`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。

### 发布包自检

```bash
# 校验签名与关键内容（包名/版本/minSdk/targetSdk/权限/ABI/assets/模型/dex）
python tools/check_apk.py verify app-release.apk

# 只看证书指纹
python tools/check_apk.py certs app-release.apk
```

`check_apk.py` 会核对：包名/版本/`minSdk`/`targetSdk`/权限/`native-code`（四个 ABI）、
`assets/verses.json`、`assets/mlkit-google-ocr-models/**`、
`lib/*/libmlkit_google_ocr_pipeline.so`、`classes*.dex`，以及签名是否有效。

> 本工程**不做第三方加固**（如 360 加固保）。加固会改写 DEX、hook 反射与 Binder，
> 而本 App 的关键能力（MediaProjection 录屏、`TYPE_APPLICATION_OVERLAY` 悬浮窗、
> ML Kit 的 native OCR 管道）恰好都依赖这些机制，加固后需要逐项真机复测，
> 收益（提高逆向门槛）与风险（录屏/悬浮窗/OCR 失效）不成正比。
> `tools/check_apk.py` 曾用于加固后重签名，该功能保留但**默认流程不再使用**。

## 文档

* 设计文档：[`docs/specs/2026-10-05-songci-ocr-assist-design.md`](docs/specs/2026-10-05-songci-ocr-assist-design.md)

## 当前状态

M1~M3 代码已完成（骨架 + 引导页 + 悬浮窗 + 取帧 + OCR + 两段式匹配 + 高亮）：
共 14 个主源码文件 + 6 个 JVM 单测文件（52 个用例），另有 5 个 Python 工具。

### CI 状态（GitHub Actions，2026-10-05 实跑）

两个 job 全绿：

* `索引与匹配复算` ✅ 6m17s —— 重新生成索引并要求与提交版本一致、跑匹配复算 + 阈值扫描
* `单测与打包` ✅ 8m19s —— JVM 单测、`assembleDebug`、`assembleRelease` 全部成功，
  产物 `app-release.apk` / `app-debug.apk` 已作为 artifact 上传

### 本地验证记录（2026-10-05，真的跑过）

本机临时装了 JDK 17（Temurin 17.0.20.1）、Gradle 8.7、Android SDK
（platform-tools / platforms;android-34 / build-tools;34.0.0），实测：

| 项目 | 命令 | 结果 |
|---|---|---|
| 索引生成 + 校验 | `python tools/gen_verse_index.py` | 通过（99 条 / 40 词牌） |
| 匹配算法复算 | `python tools/verify_all.py` | 全部通过 |
| Kotlin/Java/资源编译 | `gradle compileDebugKotlin` | **通过**（60 个 class） |
| **JVM 单元测试** | JUnitCore 跑编译产物 + CI 的 `testDebugUnitTest` | **OK (75 tests)**（两处都过） |
| **debug 打包（已签名）** | `gradle assembleDebug` | **成功**，44.4 MB，签名 DN `CN=Android Debug` |
| **release 打包（已签名）** | `gradle assembleRelease` | **成功**，40.17 MB，含 R8 混淆 + 资源压缩 |
| release 签名校验 | `apksigner verify --print-certs` | **通过**，v2 方案，指纹与 keystore 一致 |
| **真机端到端** | 小米 17 Pro Max / HyperOS 3 / Android 16 | **成功出框**（见下方「真机实测」） |

### 真机实测（小米 17 Pro Max，HyperOS 3.0.319.0.WPBCNXM.C11，Android 16，1200x2608）

这一节记录的是**端到端跑通**过程中踩到的真实坑，都是靠 `adb logcat` + 抓帧 PNG 定位的：

| 现象 | 根因 | 修法 |
|---|---|---|
| 投屏建立后 **21ms 就被系统回收** | `onCapturedContentResize` 里 release 了 VirtualDisplay —— 官方文档明确警告过 | 改为只换消费者、绝不 release |
| 换消费者后**只续上 2 帧** | `setSurface(新)` 后立刻 close 旧 reader → BufferQueue 被弃用 | 「先 detach → resize → attach → 延后回收」 |
| 画面**一大片黑 + 角落一块灰** | VirtualDisplay 用了 `maximumWindowMetrics`，与真实显示尺寸不一致 → 系统缩放塞入 | 改用 `Display.getRealMetrics()` |
| OCR **0 个文本块** | 整帧缩到 41% 后艺术字体读不出 | 不缩放，原生分辨率识别 |
| 能看到字但**没有框** | 气泡只认精确等值，艺术字体错 1 字就失配 | 同长度模糊匹配 + 唯一最优约束 |
| 提示**延迟好几秒** | OCR ~300ms/帧 vs 画面 30fps，任务在线程池排队 | 只处理最新帧 |


`app-release.apk` 实测 **40.15 MB**，抽查内容正确：

* `package: com.songci.assist`、`versionCode 1` / `versionName 0.1.0`
* `minSdk 26` / `targetSdk 34` / `compileSdk 34`
* 四个权限齐全（悬浮窗 / 前台服务 / mediaProjection / 通知）
* `application-label: 宋词择律提示器`、`native-code` 四个 ABI
* `assets/verses.json`（20407 字节）**已打进包**
* `assets/mlkit-google-ocr-models/**`（25 个模型文件）与
  `lib/*/libmlkit_google_ocr_pipeline.so` 四个 ABI 齐全
  → **证明离线 OCR 模型真的随包分发**（不需要联网/Play 服务）

`app-release-unsigned.apk` 是早先未签名版本；现在 `keystore.properties` 存在即自动签名。

### 尚未逐项验证

* 耗电与长时间后台存活（连续 30 min 不发热、不被系统杀）—— 见「真机手测清单」；
* 息屏 / 切后台 / 旋转后恢复正常，以及停止投屏后再启动能恢复；
* 其他机型/分辨率的表现（目前只在小米 17 Pro Max 上完整验证过）；
* 真机手测清单见上文，逐项打勾后再做后续调参。

**已知取舍**：目前不对整帧做缩放（早期缩到宽 ≤1080 会让游戏艺术字体完全读不出来）。
取帧侧的关键修复见 `ScreenFrameReader` 的类注释：用 `Display.getRealMetrics()` 建
VirtualDisplay（1:1 不缩放），尺寸变化时按「先 detach 旧 surface → resize → attach
新 surface」换消费者（社区验证过的顺序）；匹配侧对词牌气泡允许「同长度、错 1 字」的
模糊匹配（词牌名全部 3/4 字且不存在同长度近似对，因此安全）。

**OCR 速度的实际约束**（实测，勿再走弯路）：

* ML Kit 官方明确说明**中文模型不是实时速度**；本机实测全屏 2608x1200 **单次 1.6~2.4 秒**；
* 因此「停止处理动画」这条路**走不通**：游戏顶部有持续飘动的粒子动画，画面永不静止，
  「等画面停稳再 OCR」要么一直等（框不出现），要么只能靠兜底超时（更慢）—— 已撤销；
* 同样，「换 OCR 引擎」收益不确定（当前短板是**游戏艺术字体**，不是引擎速度），
  且要重做坐标映射与准确率校准，风险高于收益；
* 目前有效的优化只有两个：**首遍裁掉底部 15% 像素**、以及**二次识别限流 3 秒**
  （仅在首句/气泡漏读时才追加一次区域放大识别）。

