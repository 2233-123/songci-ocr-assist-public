package com.songci.assist

import org.json.JSONObject

/**
 * 一条词句效果（名称 + 数值）。
 *
 * `value` 为负表示**减少**（例如 `腐化-4` 是降低腐化，是好事）。
 * 展示形式由界面决定（当前是「名称+数值」，如 `民心-10`）。
 */
data class PaiEffect(
    val name: String,
    val value: Double,
    val type: Int,
) {
    /** 界面用的紧凑文本，例如 `民心-10`、`歌板+2`。 */
    fun label(): String =
        if (value >= 0) "%s+%s".format(name, trim(value))
        else "%s-%s".format(name, trim(-value))

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}

/**
 * 词句 → 效果 的索引，用于「认出首句后告诉玩家这词有什么效果」。
 *
 * ### 数据来源
 *
 * 由 `tools/gen_effect_index.py` 从游戏配置生成：
 * 首句取自 `SongCiVerseConfig.FullVerseLineList[0]`，效果名取自
 * `EffectTypeConfig.EffectName`、数值取自 `SongCiVerseConfig.EffectParamList`。
 *
 * 游戏**不提供**按句拆分的效果（效果挂在整条词句上），所以「读出首句」
 * 就等于确定了整首词的效果。
 *
 * ### 为什么 key 可以直接用 `Verse.head`
 *
 * 生成器写入的 key 就是索引里的首句字面量，而索引的 `head` 本身就是规范化形式
 * （无标点/空白，见 `VerseIndex.normalize`）。由 `EffectIndexTest` 断言守护
 * 「效果表 key 与索引 head 逐一相等」。
 *
 * ### 为什么在生成期固化对齐，而不是运行时模糊匹配
 *
 * 游戏侧首句**会被截断到 12 字**，与索引的完整首句不一致。生成器在**生成时**
 * 就用「互为前缀」把关系算好并写进文件，运行时是**纯查表**（无模糊、无歧义、
 * 无额外开销）。这也意味着：效果表与索引不一致时应当**重新生成**，而不是运行时兜底。
 *
 * ### 用户口径
 *
 * **不显示「词元」效果**（加词元的不显示）。这一步在生成期完成 ——
 * 文件里根本不含词元，运行时也就无从显示。
 */
class EffectIndex private constructor(
    private val byHead: Map<String, List<PaiEffect>>,
) {

    /** 该首句的效果；没有数据时返回空列表（界面不显示，不报错）。 */
    fun forHead(head: String): List<PaiEffect> = byHead[head] ?: emptyList()

    val size: Int get() = byHead.size

    companion object {
        const val ASSET_NAME = "effects.json"

        /**
         * 从 `effects.json` 解析。
         *
         * 结构：
         * ```json
         * { "hidden_effects": ["词元"], "count": 99,
         *   "effects": { "甚矣吾衰矣": [ {"name":"民心","value":-6,"type":51} ] } }
         * ```
         */
        fun fromJson(json: String): EffectIndex {
            val root = JSONObject(json)
            val obj = root.optJSONObject("effects")
                ?: throw IllegalArgumentException("effects.json 缺少 effects 对象")
            val map = LinkedHashMap<String, List<PaiEffect>>(obj.length())
            for (head in obj.keys()) {
                val arr = obj.optJSONArray(head) ?: continue
                val list = ArrayList<PaiEffect>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name")
                    if (name.isEmpty()) continue
                    list += PaiEffect(
                        name = name,
                        value = o.optDouble("value", 0.0),
                        type = o.optInt("type", 0),
                    )
                }
                if (list.isNotEmpty()) map[head] = list
            }
            return EffectIndex(map)
        }
    }
}
