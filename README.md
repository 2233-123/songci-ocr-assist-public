# 宋词择律 OCR 提示器

玩宋词择律类玩法时，游戏会给你一句词，让你在几个词牌里选出正确的那个。
这个 App **在屏幕上把正确答案框出来** —— 不用查资料，看一眼框就知道选哪个。

它只做三件事：**看屏幕 → 查词牌 → 画框**。不修改游戏、不注入进程、不代替你操作。

> ⚠️ **请注意**：这是第三方工具，与游戏官方**无任何关系，也未获其授权**。
> 使用辅助工具可能违反游戏用户协议并带来**账号风险**，请自行判断、风险自负。

---

## 怎么用

### 1. 安装

到 [Releases](../../releases) 下载 `songci-assist-v0.6.0-release.apk` 安装。
首次安装需要在系统设置里允许「安装未知来源应用」。

### 2. 授权

打开 App，按引导页授予两项权限：

- **悬浮窗**（显示提示框必须）
- **通知**（保持后台运行必须）

### 3. 开始提示

1. App 里点「**开始录屏**」，系统弹窗里选「**整个屏幕**」并确认
   > Android 要求每次使用录屏都要重新授权，这是系统限制，不是 App 的问题
2. 切回游戏，进入择律页面
3. **点一下屏幕上的悬浮球**（开始工作）
4. 首句出现后约 1~2 秒，正确的词牌上会出现**高亮框**

### 悬浮球怎么操作

| 操作 | 效果 |
|---|---|
| 点一下 | 暂停 / 继续 |
| 拖动 | 移动位置（松手自动吸附到屏幕边缘，位置会记住） |
| 长按 | 退出 |

---

## 使用说明与排查

**框出来的位置不对 / 没有框？**

- 游戏的艺术字体偶尔会被 OCR 认错。认错 1 个字时 App 会自动纠正，
  错得更多时**宁可不画框，也不会画错** —— 画错比不画更糟，所以这是有意的选择
- 首句还没显示完（画面还在渐入）时识别不出来，等一秒再看

**一直不出框？**

- 确认已经**点过悬浮球**（球是实心的才在运行）
- 确认录屏授权选的是「整个屏幕」而不是某个应用窗口
- 有些系统的电池优化会杀掉后台服务，建议在系统设置里给 App 关闭电池优化 / 加入白名单

**会不会很费电 / 发热？**

- 需要持续录制屏幕并识别文字，会比待机耗电。识别并不快（见下方「已知限制」），
  所以不适合长时间挂着，建议需要时再开

**隐私**

- App **不联网、不采集、不上传任何数据**，识别全部在手机本地完成，没有服务器
- 唯一的对外通信来自 Google ML Kit SDK 自身（发送 API 性能/使用指标，
  **不含你的屏幕画面与识别结果**）

---

## 已知限制

- **识别速度**：使用的 Google ML Kit 中文模型本身不是实时速度，
  实测单次全屏识别约 **1.6~2.4 秒**。所以提示会有一点延迟，
  画面停留一两秒即可识别成功
- **准确性**：游戏使用艺术字体，OCR 偶尔会认错个别字。词牌名错 1 个字可以自动纠正，
  错 2 个字以上不会出框（宁可不提示，也不误报）
- **适配**：目前只在 **小米 17 Pro Max / HyperOS 3 / Android 16** 上完整验证过，
  其他机型或分辨率可能表现不同
- **需要 Android 8.0 及以上**
- 包体约 40 MB，其中绝大部分是随包的中文识别模型（好处是**离线可用，不依赖网络**）

---

## 它是怎么工作的

1. **录屏取帧** —— 通过系统的 MediaProjection 接口读取屏幕画面（1:1 原始分辨率）
2. **文字识别** —— Google ML Kit 中文离线模型在本地识别画面文字
3. **两段式匹配**
   - 在画面上部找到**首句**，用编辑距离相似度去索引里查最接近的词
   - 查到的词带来一个**词牌名**，再在画面中部找到写着这个名字的选项气泡
4. **画框** —— 用悬浮窗在那个气泡上画一个高亮框

索引里有 **99 条词句 / 40 个词牌**，随包提供、离线查表。

### 匹配的准确度（可复算）

对索引里每条词句随机注入错字后测试：

| 注入错字数 | 画错词牌（误报） | 能给出提示 |
|---|---|---|
| 0 | 0 | 100% |
| 1 个字 | **0** | 100% |
| 2 个字 | **0** | 98.6% |
| 3 个字 | **0** | 86.9% |

**任何错字量下都没有出现过"画框但画错"** —— 这是本项目的首要原则。
复算：`python tools/tune_matcher.py`

---

## 数据来源

**本仓库不含任何游戏数据。** 游戏导出的原始配置属于游戏素材，需要的话请自行从自己的设备导出
（格式说明见 [data/README.md](data/README.md)）。

随包提供的 `app/src/main/assets/verses.json` 是上述数据的**最小投影**：
只有「词句首句 → 词牌名 → 词人」这样的事实性对应（约 20 KB），是 App 离线查表所必需；
词句本身属**公有领域**（作者均为唐宋人）。仓库不含任何游戏素材（图片、音频、字体、原始配置）。

---

## 开发者：构建与修改

<details>
<summary>点开看构建说明、项目结构、测试与设计文档</summary>

### 构建

```bash
# 需要 JDK 17 + Android SDK（platform 34 + build-tools 34.0.0）
./gradlew assembleDebug          # 打 debug 包
./gradlew assembleRelease        # 打 release 包（配了 keystore.properties 才签名）
./gradlew testDebugUnitTest      # JVM 单测，无需设备（75 个用例）

python tools/verify_all.py       # 一键自检：索引校验 + 匹配复算 + CI/版本一致性
```

`app/src/main/assets/verses.json`（词句索引）已随仓库提交，**克隆下来即可直接构建**，
不需要任何数据准备。

只有**重新生成索引**才需要自备游戏数据：把游戏导出的两个 JSON 放进 `data/`
（结构与字段见 [data/README.md](data/README.md)），然后跑 `python tools/gen_verse_index.py`。

> ⚠️ **本工程最初在含中文的目录下开发**（Windows 上的 `E:\Desktop\宋词辅助`）。
> AGP 默认会对非 ASCII 路径直接报错，工程里用 `android.overridePathCheck=true` 关掉了该检查。
> 若遇到离奇失败，优先怀疑这一项；想避开就把仓库克隆到纯 ASCII 路径。

### 目录结构

```
├── data/                        # 索引数据源（游戏导出，不入库；见 data/README.md）
│   ├── README.md                #   格式说明 + 如何自备数据
│   └── *.example.json           #   结构骨架（不含真实词句）
├── tools/                       # 纯 Python 标准库，无第三方依赖
│   ├── gen_verse_index.py       #   生成 + 校验 verses.json（唯一需要 data/ 的脚本）
│   ├── tune_matcher.py          #   匹配复算 / 阈值调参（与 Kotlin 同式）
│   ├── verify_all.py            #   一键自检
│   ├── check_index_committed.py #   CI：索引必须与提交版本一致
│   └── check_apk.py             #   发布包校验（签名/权限/ABI/assets/模型）
├── docs/specs/                  # 设计文档（含设计意图与被实测推翻的结论）
└── app/src/main/
    ├── AndroidManifest.xml
    ├── assets/verses.json       # 词句索引
    └── java/com/songci/assist/
        ├── MainActivity.kt            # 引导页：授权 + 开始/停止 + 状态
        ├── CaptureBridgeActivity.kt   # 透明桥接页，拉起系统录屏授权
        ├── CaptureService.kt          # 前台服务：取帧 → OCR → 匹配 → 通知悬浮层
        ├── ScreenFrameReader.kt       # VirtualDisplay + ImageReader 取帧
        ├── MlKitOcrEngine.kt          # ML Kit 中文识别
        ├── FramePipeline.kt           # 节流 / 缓存 / 结论分发
        ├── Matcher.kt                 # 两段式匹配（纯函数，单测覆盖）
        ├── VerseIndex.kt              # 索引加载与查表
        ├── OverlayService.kt          # 悬浮窗生命周期（高亮层 + 悬浮球）
        ├── OverlayView.kt             # 画高亮框
        ├── FloatingBallView.kt        # 悬浮球
        ├── Config.kt                  # 全部可调参数
        └── ...
```

### 匹配算法

- 匹配键 = **首句**（正文到第一个断句符）去掉全部标点与空白
- 相似度 `sim(a,b) = 1 − Levenshtein(a,b) / max(len(a), len(b))`，阈值 `Config.SIMILARITY_THRESHOLD = 0.72`
  （该式在 Python 侧与 Kotlin 侧**必须一致**，改一处要同步改另一处）
- 两段式：顶部 `y∈[0, 0.45H]` 找首句 → 命中后中部 `y∈[0.30H, 0.80H]` 找文本等于词牌的块
- 首句被 OCR 拆行时按 Y 相邻拼接（最多 2 行）；同一行被拆成左右两段时按 X 拼接。
  两条防回归规则见 `Matcher.sameLine` / `Matcher.mergeAdjacent` 的注释：
  **同排归并要求 X 基本不相交**（避免把同排的 UI 标签并进句子），
  **上下拼接排除"一方被另一方横向包住"**（避免小标签粘进首句把相似度顶到阈值以下）
- 词牌气泡先精确匹配，失败时允许**同长度、错 1 字**的模糊匹配，且要求是**唯一最优**
  （词牌名全部 3/4 字且不存在同长度近似对，因此这个宽松度是安全的）

### 测试

- **75 个 JVM 单元测试**（`./gradlew testDebugUnitTest`），无需设备
  - `MatcherTest` / `MatcherEdgeTest` / `MatcherNoiseTest`：匹配算法与噪声场景
  - `RealFrameMatchTest`：用**真机实收的 OCR 块**构造，锁定整条匹配链路
  - `BandRetryMappingTest`：二次识别的裁带放大坐标映射
  - `CommittedIndexTest`：守护随包索引的结构与关键性质（条数/唯一性/可自查/模糊匹配安全前提）
- `python tools/verify_all.py`：索引校验 + 匹配复算 + CI/版本一致性
- CI（GitHub Actions）：索引与匹配复算、单测、debug/release 打包、签名校验

### OCR 速度的实际约束（勿再走弯路）

实测与调研结论，写下来避免重复踩：

- ML Kit 官方明确说明**中文模型不是实时速度**；本机实测全屏 2608x1200 **单次 1.6~2.4 秒**
- 「等画面停稳再 OCR」这条路**走不通**：游戏顶部有持续飘动的粒子动画，画面永不静止，
  要么一直等（不出框），要么只能靠兜底超时（更慢）—— 曾实现后又撤销
- 「换 OCR 引擎」收益不确定（短板是**游戏艺术字体**而非引擎速度），
  且要重做坐标映射与准确率校准，风险高于收益
- 目前有效的优化只有两个：**首遍裁掉底部 15% 像素**、**二次识别限流 3 秒**
  （仅在首句/气泡漏读时追加一次区域放大识别）

### 取帧侧的三个关键修复

曾长期卡在"投屏建立后立刻被系统回收"，最终定位如下（细节见 `ScreenFrameReader` 类注释）：

| 现象 | 根因 | 修法 |
|---|---|---|
| 投屏 21ms 就被回收 | 尺寸变化时 release 了 VirtualDisplay（官方文档明确警告过） | 只换消费者，绝不 release |
| 换消费者后只续 2 帧 | `setSurface(新)` 后立刻 close 旧 reader → BufferQueue 被弃用 | 先 detach → resize → attach → 延后回收 |
| 画面黑底 + 角落一块灰 | 用了 `maximumWindowMetrics`，与真实显示尺寸不一致 | 改用 `Display.getRealMetrics()` |

### 分发

GitHub Actions 产出 `app-debug.apk` 与 **`app-release.apk`（已签名，分发用）**。
CI 里 4 个 Secrets 配好后自动签名（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）。

发布包自检：

```bash
python tools/check_apk.py verify app-release.apk   # 签名 + 包名/版本/权限/ABI/assets/模型
python tools/check_apk.py certs  app-release.apk   # 只看证书指纹
```

> 本工程**不做第三方加固**（如 360 加固保）。加固会改写 DEX、hook 反射与 Binder，
> 而本 App 的关键能力（MediaProjection 录屏、`TYPE_APPLICATION_OVERLAY` 悬浮窗、
> ML Kit 的 native OCR 管道）恰好都依赖这些机制，加固后需逐项真机复测，
> 收益（提高逆向门槛）与风险（录屏/悬浮窗/OCR 失效）不成正比。

### 尚未逐项验证

- 耗电与长时间后台存活（连续 30 min 不发热、不被系统杀）
- 息屏 / 切后台 / 旋转后恢复正常，以及停止投屏后再启动能恢复
- 其他机型/分辨率的表现（目前只在小米 17 Pro Max 上完整验证过）

</details>

---

## 开源许可与第三方依赖

代码以 **Apache License 2.0** 发布，见 [LICENSE](LICENSE)。

| 依赖 | 许可 | 说明 |
|---|---|---|
| AndroidX（core-ktx / appcompat / activity-ktx / lifecycle-service） | Apache 2.0 | 可自由分发 |
| Material Components | Apache 2.0 | 可自由分发 |
| kotlinx-coroutines-android | Apache 2.0 | 可自由分发 |
| JUnit 4 / org.json | EPL 1.0 / Public Domain | 仅测试用 |
| **`com.google.mlkit:text-recognition-chinese`** | **Google 专有** | 闭源 SDK，见下 |

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

### 免责声明

* 这是**第三方辅助工具，与游戏官方无关**，未获得游戏厂商授权或背书；
* 它只做「读屏 → 查表 → 画框」，不修改游戏、不注入进程、不模拟操作；
* 在游戏中使用读屏/辅助工具**是否违反游戏用户协议，由使用者自行判断与承担**；
* 软件按「现状」提供，不附带任何担保（详见 Apache 2.0 第 7、8 条）。

### 文档

设计文档：[`docs/specs/2026-10-05-songci-ocr-assist-design.md`](docs/specs/2026-10-05-songci-ocr-assist-design.md)
（含设计意图、取舍理由，以及被实测推翻的结论标注）
