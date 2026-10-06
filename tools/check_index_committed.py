# -*- coding: utf-8 -*-
"""CI 用：确认「重新生成的索引」与「已提交的索引」内容一致。

`generated_at` 会等于运行当天，因此只比对 verses 与 pai_list 两个字段。

用法:
    python tools/check_index_committed.py            # 与 git show HEAD 比对
    python tools/check_index_committed.py --ref HEAD

返回 0 = 一致；1 = 不一致或文件缺失（会让 CI 失败）。
"""
from __future__ import annotations

import argparse
import io
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INDEX_REL = "app/src/main/assets/verses.json"


def main() -> int:
    ap = argparse.ArgumentParser(description="校验已提交的 verses.json 与重新生成的一致")
    ap.add_argument("--ref", default="HEAD", help="git 引用（缺省 HEAD）")
    args = ap.parse_args()

    path = ROOT / INDEX_REL
    if not path.exists():
        print(f"[失败] 找不到 {INDEX_REL}，先跑 python tools/gen_verse_index.py")
        return 1

    try:
        generated = json.loads(io.open(path, encoding="utf-8").read())
    except ValueError as exc:
        print(f"[失败] {INDEX_REL} 不是合法 JSON: {exc}")
        return 1

    try:
        raw = subprocess.run(
            ["git", "show", f"{args.ref}:{INDEX_REL}"],
            cwd=str(ROOT),
            capture_output=True,
            check=True,
        ).stdout
    except FileNotFoundError:
        print("[跳过] 本机没有 git，无法与提交版本比对")
        return 0
    except subprocess.CalledProcessError:
        print(f"[跳过] {args.ref} 里还没有 {INDEX_REL}（首次提交时正常）")
        return 0

    committed = json.loads(raw.decode("utf-8"))

    problems = []
    if generated["verses"] != committed["verses"]:
        problems.append("verses 与提交版本不一致（改过 data/ 或生成脚本后要重新提交索引）")
    if generated["pai_list"] != committed["pai_list"]:
        problems.append("pai_list 与提交版本不一致")
    if generated.get("count") != len(generated["verses"]):
        problems.append(f"count={generated.get('count')} 与实际条数 {len(generated['verses'])} 不符")

    if problems:
        for p in problems:
            print("[失败] " + p)
        return 1

    print("索引与提交版本一致 ✓  %d 条 / %d 词牌"
          % (len(generated["verses"]), len(generated["pai_list"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
