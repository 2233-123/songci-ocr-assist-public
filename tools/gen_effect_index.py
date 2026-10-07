# -*- coding: utf-8 -*-
"""从游戏配置生成「词句 → 效果」索引 `effects.json`。

## 输入
- `data/SongCiVerseConfig.json`   词句表：首句 + EffectTypeList/EffectParamList
- `EffectTypeConfig.json`         效果表：EffectType → EffectName/AddDesc/ReduceDesc
  （属游戏素材，**不入库**，路径可用 `--effects` 指定）

## 输出
- `app/src/main/assets/effects.json`

## 为什么需要「首句截断」容错

游戏侧 `FullVerseLineList[0]` 的首句**会被截断到 12 字**，而 App 索引存的是完整首句：

```
游戏: 一曲新词酒一杯去年天气旧亭台夕阳西下几时回      （12 字，截断）
索引: 一曲新词酒一杯去年天气旧亭台夕阳西下几时回无可奈何花落去似曾相识燕归来小园香径独徘徊
```

所以对齐用「互为前缀」而不是完全相等，并把对齐结果**固化进输出文件**
（运行时是纯查表，不需要任何模糊逻辑）。

## 用户口径
- **不显示「词元」效果**（用户明确要求）
- 其余效果全部保留（含「歌板」—— 用户确认保留）
- 显示形式为「名称+数值」，所以只存 name/param（描述文案不存）

用法：
    python tools/gen_effect_index.py
    python tools/gen_effect_index.py --check     # 只校验，不写文件（CI/自检用）
"""
from __future__ import annotations

import argparse
import io
import json
import os
import re
import sys
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERSE_SRC = os.path.join(ROOT, "data", "SongCiVerseConfig.json")
INDEX = os.path.join(ROOT, "app", "src", "main", "assets", "verses.json")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "effects.json")

# 游戏侧首句的截断长度（实测：恰好 12 字）
GAME_HEAD_CAP = 12

# **不显示**的效果（用户口径：加词元的不显示）
HIDDEN_EFFECTS = {"词元"}


def keep_only_cjk(s: str) -> str:
    """与 VerseIndex.normalize 同一口径：去标点/空白（但保留数字字母）"""
    return re.sub(r"[^\u4e00-\u9fff0-9A-Za-z]", "", s or "")


def strip_rich(s: str) -> str:
    """去掉 <color=..>（首句）</color> 富文本标签与句序标记"""
    t = re.sub(r"<[^>]*>", "", s or "")
    return re.sub(r"^（[^）]*）", "", t.strip())


def game_head(entry: dict) -> str:
    lines = entry.get("FullVerseLineList") or []
    if not lines:
        return ""
    return keep_only_cjk(strip_rich(lines[0]))


def load_effects(path: str) -> dict:
    with io.open(path, encoding="utf-8") as f:
        data = json.load(f)
    table = {}
    for e in data["dataList"]:
        table[e["EffectType"]] = e
    return table


def default_effects_path() -> str:
    """效果表默认路径：优先用 `data/EffectTypeConfig.json`（与 gen_verse_index 一致），
    退化到开发机上的原始位置。

    与 `data/SongCiVerseConfig.json` 一样，它是**游戏素材、不入库** ——
    需要的话请自行从自己的设备导出。"""
    local = os.path.join(ROOT, "data", "EffectTypeConfig.json")
    if os.path.exists(local):
        return local
    return r"E:\Desktop\宋词模拟\EffectTypeConfig.json"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--effects", default=default_effects_path(),
                    help="EffectTypeConfig.json 路径（游戏素材，不入库）")
    ap.add_argument("--check", action="store_true", help="只校验，不写文件")
    args = ap.parse_args()

    if not os.path.exists(args.effects):
        print("找不到效果表：%s" % args.effects, file=sys.stderr)
        print("它是游戏素材、不入库；请从自己的设备导出后放到 data/EffectTypeConfig.json"
              "，或用 --effects 指定路径。", file=sys.stderr)
        return 2

    with io.open(VERSE_SRC, encoding="utf-8") as f:
        verses = json.load(f)["dataList"]
    with io.open(INDEX, encoding="utf-8") as f:
        index = json.load(f)
    effects = load_effects(args.effects)

    app_heads = [v["head"] for v in index["verses"]]
    app_set = set(app_heads)
    assert len(app_set) == len(app_heads), "App 索引里有重复首句，无法一一对齐"

    # ---------------------------------------------------------------- 对齐
    def resolve(gh: str):
        """游戏首句 → App 首句。返回 (app_head, 关系)"""
        if gh in app_set:
            return gh, "exact"
        pre = [h for h in app_heads if h.startswith(gh)]
        if len(pre) == 1:
            return pre[0], "game_is_prefix"
        if len(pre) > 1:
            return None, "ambiguous(%d)" % len(pre)
        return None, "unmatched"

    mapping = {}
    stat = Counter()
    unmatched = []
    for v in verses:
        gh = game_head(v)
        if not gh:
            continue
        ah, rel = resolve(gh)
        stat[rel] += 1
        if ah is None:
            unmatched.append((v["ID"], v["CiPaiName"], gh, rel))
            continue
        if ah in mapping:
            print("  ★App 首句被两条游戏数据命中：%s" % ah, file=sys.stderr)
            return 2
        mapping[ah] = v

    print("=== 对齐统计（游戏 %d 条）===" % len(verses))
    for k, n in stat.most_common():
        print("  %-16s %d" % (k, n))
    if unmatched:
        print("  未对齐 %d 条（游戏里有、索引里没有，属正常）:" % len(unmatched))
        for uid, pai, gh, rel in unmatched:
            print("     ID=%-4s %-6s %r  (%s)" % (uid, pai, gh, rel))

    # ---------------------------------------------------------------- 组装
    out = {}
    no_effect = []
    for ah in app_heads:
        v = mapping.get(ah)
        if v is None:
            no_effect.append(ah)
            continue
        items = []
        for t, p in zip(v.get("EffectTypeList") or [], v.get("EffectParamList") or []):
            e = effects.get(t)
            name = e["EffectName"] if e else "?%d" % t
            if name in HIDDEN_EFFECTS:
                continue
            items.append({"name": name, "value": float(p), "type": int(t)})
        if items:
            out[ah] = items

    print()
    print("=== 输出 ===")
    print("  App 首句 %d 条 → 有效果的 %d 条" % (len(app_heads), len(out)))
    if no_effect:
        print("  ★没有效果数据的 %d 条:" % len(no_effect))
        for h in no_effect[:10]:
            print("     %s" % h)
    if no_effect:
        return 2

    # 效果覆盖统计（确认「词元」被排掉、其余保留）
    c = Counter()
    for items in out.values():
        for it in items:
            c[it["name"]] += 1
    print("  效果分布: %s" % dict(c.most_common()))
    assert "词元" not in c, "「词元」不该出现在输出里（用户要求不显示）"

    payload = {
        "generated_from": "SongCiVerseConfig.json + EffectTypeConfig.json",
        "hidden_effects": sorted(HIDDEN_EFFECTS),
        "count": len(out),
        "effects": {k: out[k] for k in sorted(out)},
    }

    if args.check:
        with io.open(OUT, encoding="utf-8") as f:
            cur = json.load(f)
        if cur != payload:
            print("★effects.json 与游戏数据不一致，请重新生成", file=sys.stderr)
            return 1
        print("  effects.json 与数据一致 ✓")
        return 0

    with io.open(OUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump(payload, f, ensure_ascii=False, indent=1, sort_keys=False)
        f.write("\n")
    print("  已写入 %s" % os.path.relpath(OUT, ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
