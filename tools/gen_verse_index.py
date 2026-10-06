# -*- coding: utf-8 -*-
"""生成安卓端词句索引 assets/verses.json。

数据源（都在本仓库 data/ 下，**不依赖 PostgreSQL，也不依赖 songci-simulation 工程**）:
    data/SongCiVerseConfig.json    词句库 (SongCiVerseConfig)
    data/SongCiPoetConfig.json     词人表   (SongCiPoetConfig, 仅用于补词人姓名)

输出:
    app/src/main/assets/verses.json

用法:
    python tools/gen_verse_index.py                 # 生成 + 校验
    python tools/gen_verse_index.py --check         # 只校验已生成的文件

匹配键 `head` = 词句正文的**首句**去掉全部标点与空白后的字符串。
首句切分: 取正文中第一个「。，、；：」之前的片段。
"""
from __future__ import annotations

import argparse
import io
import json
import re
import sys
from datetime import date
from pathlib import Path

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
VERSE_SRC = ROOT / "data" / "SongCiVerseConfig.json"
POET_SRC = ROOT / "data" / "SongCiPoetConfig.json"
OUT = ROOT / "app" / "src" / "main" / "assets" / "verses.json"

#: 导入时剔除的解锁条件: 需「特殊名臣」解锁的词句没有可实现内容
#: (实测 999 -> 词句 9「水天空阔」/ 87「秋色正萧洒」, 归属词人 19 未收录)
SPECIAL_UNLOCK_MINISTER_IDS = {999}

PUNCT_RE = re.compile(r"[，。、；：？！“”‘’（）《》〈〉【】…—·,.!?;:\"'()\[\]\s\u3000]")
FIRST_CLAUSE_SEPS = "。，、；："


def load_list(path: Path, key: str = "dataList") -> list[dict]:
    with io.open(path, encoding="utf-8-sig") as fh:
        data = json.load(fh)
    return data[key]


def first_clause(text: str) -> str:
    """取首句 (第一个断句符之前) 并去掉标点/空白。"""
    text = str(text or "").strip()
    for sep in FIRST_CLAUSE_SEPS:
        i = text.find(sep)
        if i > 0:
            text = text[:i]
            break
    return PUNCT_RE.sub("", text)


def as_ints(value) -> list[int]:
    if value is None:
        return []
    if isinstance(value, (int, float)):
        return [int(value)]
    out = []
    for x in value:
        if x is None:
            continue
        out.append(int(x))
    return out


def build() -> dict:
    raw_verses = load_list(VERSE_SRC)
    poet_names = {int(p["ID"]): p.get("Name") or "" for p in load_list(POET_SRC)}

    verses, skipped_special, no_head = [], [], []
    poets_seen: set[int] = set()
    for v in raw_verses:
        if SPECIAL_UNLOCK_MINISTER_IDS & set(as_ints(v.get("UnlockNeedMinister"))):
            skipped_special.append(int(v["ID"]))
            continue
        head = first_clause(v.get("Content"))
        if not head:
            no_head.append(int(v["ID"]))
            continue
        poet_ids = as_ints(v.get("BelongPoetIds"))
        poets_seen.update(poet_ids)
        verses.append({
            "id": int(v["ID"]),
            "name": v.get("CiName") or "",
            "head": head,
            "pai": v.get("CiPaiName") or "",
            "style": int(v.get("Style") or 0),
            "poet": "、".join(poet_names.get(p, f"#{p}") for p in poet_ids),
            "poet_ids": poet_ids,
        })

    verses.sort(key=lambda d: d["id"])
    return {
        "generated_at": date.today().isoformat(),
        "source": "SongCiVerseConfig.json (剔除需特殊名臣解锁的词句)",
        "count": len(verses),
        "pai_list": sorted({d["pai"] for d in verses if d["pai"]}),
        "verses": verses,
        "_debug": {
            "skipped_special_unlock": skipped_special,
            "skipped_empty_head": no_head,
            "poets": len(poets_seen),
        },
    }


def validate(payload: dict) -> list[str]:
    """返回问题列表 (空 = 全部通过)。"""
    problems: list[str] = []
    verses = payload["verses"]

    if not verses:
        return ["索引为空"]

    # 1) 条数
    if payload["count"] != len(verses):
        problems.append(f"count={payload['count']} 与 verses={len(verses)} 不一致")

    # 2) 键唯一 + 非空
    heads = [d["head"] for d in verses]
    dup = {h for h in heads if heads.count(h) > 1}
    if dup:
        problems.append(f"首句重复: {sorted(dup)}")
    if any(not h for h in heads):
        problems.append("存在空首句")
    if any(len(h) < 5 for h in heads):
        problems.append(f"存在过短首句: {[h for h in heads if len(h) < 5]}")

    # 3) 词牌与风格
    bad_pai = [d["id"] for d in verses if not d["pai"]]
    if bad_pai:
        problems.append(f"缺词牌: {bad_pai}")
    bad_style = [d["id"] for d in verses if d["style"] not in (1, 2)]
    if bad_style:
        problems.append(f"风格非 1/2: {bad_style}")

    # 4) 不得含需特殊名臣解锁的条目
    if 9 in {d["id"] for d in verses} or 87 in {d["id"] for d in verses}:
        problems.append("仍含需特殊名臣解锁的词句 (9/87)")

    # 5) id 唯一
    ids = [d["id"] for d in verses]
    if len(set(ids)) != len(ids):
        problems.append("词句 id 重复")

    return problems


def require_source(path: Path, what: str) -> None:
    """数据自备：缺失时给出明确指引，而不是抛出难懂的 traceback。"""
    if path.exists():
        return
    print(f"[错误] 找不到{what}：{path}")
    print()
    print("  本仓库**不分发游戏数据**（属于游戏素材）。要重新生成索引，请：")
    print("    1) 从你自己的设备游戏中导出配置 JSON；")
    print("    2) 按原名放到 data/ 目录下（结构与字段说明见 data/README.md）；")
    print("    3) 再运行本脚本。")
    print()
    print("  只校验已生成的索引时不需要数据：python tools/gen_verse_index.py --check")
    sys.exit(1)


def main() -> int:
    ap = argparse.ArgumentParser(description="生成/校验安卓端词句索引")
    ap.add_argument("--check", action="store_true", help="只校验已生成的文件（不需要 data/）")
    ap.add_argument("--out", default=str(OUT), help=f"输出路径 (缺省 {OUT})")
    args = ap.parse_args()

    out_path = Path(args.out)
    if args.check:
        if not out_path.exists():
            print(f"[错误] 未找到 {out_path}")
            return 1
        payload = json.loads(io.open(out_path, encoding="utf-8").read())
    else:
        require_source(VERSE_SRC, "词句库 data/SongCiVerseConfig.json")
        require_source(POET_SRC, "词人表 data/SongCiPoetConfig.json")
        payload = build()
        problems = validate(payload)
        if problems:
            print("[错误] 校验未通过:")
            for p in problems:
                print("   -", p)
            return 1
        out_path.parent.mkdir(parents=True, exist_ok=True)
        with io.open(out_path, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(payload, fh, ensure_ascii=False, indent=1)
            fh.write("\n")

    problems = validate(payload)
    dbg = payload.get("_debug") or {}
    print(f"词句 {payload['count']} 条 / 词牌 {len(payload['pai_list'])} 个"
          f" / 已剔除需特殊名臣解锁 {dbg.get('skipped_special_unlock')}")
    print(f"输出: {out_path}")
    if problems:
        print("[错误] 校验未通过:")
        for p in problems:
            print("   -", p)
        return 1
    print("校验通过 ✓ (首句唯一、id 唯一、词牌与风格非空、无 999 条目)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
