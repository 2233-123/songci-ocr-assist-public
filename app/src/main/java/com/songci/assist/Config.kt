package com.songci.assist

/**
 * 全局可调参数。真机调参后在这里固化，**不要**把阈值散落在业务代码里。
 */
object Config {

    // ------------------------------------------------------------------ 取帧
    /** 正常取帧频率 */
    const val FPS_NORMAL = 2.0

    /** 省电取帧频率（长时间未命中后） */
    const val FPS_IDLE = 0.5

    /** 帧最快处理间隔；小于它的帧直接丢弃（保证 CPU 不被 OCR 吃满） */
    val MIN_FRAME_INTERVAL_MS: Long = (1000.0 / FPS_NORMAL).toLong()  // 500ms

    /** 省电模式的帧间隔 */
    val IDLE_FRAME_INTERVAL_MS: Long = (1000.0 / FPS_IDLE).toLong()   // 2000ms

    /** 未命中连续多少帧后进入省电模式 */
    const val FRAMES_BEFORE_IDLE = 30

    /** 取帧缩放到宽度不超过该值（高度按比例） */
    const val MAX_FRAME_WIDTH = 1080

    /** 命中后多久内不重复跑第二段 OCR（同一局结果不变） */
    const val HIT_COOLDOWN_MS = 3_000L

    /**
     * 连续多久无命中后清除高亮（例如已经选完）。
     *
     * **必须大于省电模式的帧间隔** [IDLE_FRAME_INTERVAL_MS]，否则 0.5 fps 下
     * 每两帧之间高亮会闪一下。
     */
    const val HIGHLIGHT_TTL_MS = 2_500L

    // ------------------------------------------------------------------ 裁剪区
    /**
     * OCR 前裁剪到 y ∈ [0, 这个比例]。
     *
     * 要看的两个区都在中上部：首句（y≈0.18）+ 词牌气泡（y≈0.30~0.60）。
     * 裁掉下方无用的立绘/按钮后**按原生分辨率识别**（不再把整帧缩到 41%，
     * 那样游戏的艺术字体会读不出来）。0.85 留足气泡下沿的余量。
     */
    const val OCR_CROP_BOTTOM = 0.85f

    /** 第一段：顶部区域，找词句首句。比例相对当帧高度 */
    const val TOP_CROP_TOP = 0.0f
    const val TOP_CROP_BOTTOM = 0.45f

    /** 第二段：中部区域，找词牌选项气泡 */
    const val MID_CROP_TOP = 0.30f
    const val MID_CROP_BOTTOM = 0.80f

    /**
     * 第二段挑候选块时，块中心 y 必须落在裁剪区内的这个子区间（防止把顶部状态条、
     * 底部按钮上的同名词牌也算成选项气泡）。
     */
    const val OPTION_REGION_TOP = 0.30f
    const val OPTION_REGION_BOTTOM = 0.80f

    // ------------------------------------------------------------------ 匹配
    /** 归一化编辑距离相似度阈值（与预研脚本一致） */
    const val SIMILARITY_THRESHOLD = 0.72

    /**
     * 气泡（词牌名）模糊匹配阈值。
     *
     * 词牌名全部是 3 字或 4 字，且**长度相同、相似度 ≥ 0.6 的词牌对一个都没有**
     * （已用索引校验）。所以：
     * - 阈值的意义是"同长度下最多错 1 个字" → 3 字对应 0.667、4 字对应 0.75；
     * - 取 0.66 让 3 字错 1 字也能救回来（例如「钗头凤」被 OCR 读错一个字）；
     * - 因为不存在同长度近似的词牌对，这个宽松度不会把 A 认成 B。
     */
    const val OPTION_SIMILARITY_THRESHOLD = 0.66

    /**
     * 气泡模糊匹配的「唯一最优」裕度：第一名必须比第二名高这么多才算数，
     * 否则宁可不出框（宁可不提示，也不误报）。
     */
    const val OPTION_SIMILARITY_MARGIN = 0.15

    /** 首句最短长度（索引校验同口径），过短不参与匹配 */
    const val MIN_HEAD_LEN = 5

    /** 首句在 Y 轴相邻多少像素内视为同一行，可拼接（两行折行的情况） */
    const val LINE_MERGE_TOLERANCE_RATIO = 0.6f

    /** 单帧最多拼接的行数，防止把整屏拼成一句 */
    const val MAX_MERGED_LINES = 2

    /**
     * 「同一行」允许的 X 轴交集上限（相对较窄块的宽度）。
     *
     * 真正被 OCR 拆成左右两段的话几乎不相交；交集过大说明是**两个各自独立**的块
     * （小块落在大块里、或同排的 UI 标签），拼起来只会把整句认错。
     */
    const val LINE_X_OVERLAP_MAX = 0.2f

    /**
     * 上下两行拼接时，判定「一方被另一方横向包住」所需的宽度差（相对较窄行宽度）。
     *
     * 整行对整行（宽度相同）是正常的折行，必须能拼；而「一个小标签落在整行里面」
     * 则不能拼进去，否则会把标签粘进首句（轻则状态条文本错，重则相似度跌破阈值整句认不出）。
     */
    const val LINE_CONTAIN_WIDTH_MARGIN = 0.15f

    // ------------------------------------------------------------------ 悬浮窗
    /** 高亮框线宽（dp） */
    const val HIGHLIGHT_STROKE_DP = 6f

    /** 高亮框四角外扩（dp），让框比文字略大、不贴字 */
    const val HIGHLIGHT_PADDING_DP = 6f

    /** 悬浮球直径（dp） */
    const val BALL_SIZE_DP = 48f

    /** 悬浮球吸附边缘的判定阈值（dp） */
    const val BALL_SNAP_DP = 12f
}
