# -*- coding: utf-8 -*-
"""用 PP-OCRv5 在回归语料上生成标注（每题的正确词牌），供端侧基准 App 打分。

**复用 `tools/bench_engines.py` 里已验证的匹配逻辑**（同行拼接 + 子串搜索），
不另写一套 —— 之前手写的简陋版本漏了拼接，导致 9/29 标不出来。

标注做法：对每帧识别出的文本，用索引做最近邻，得到词牌。若最高相似度低于阈值，
则该帧标为空（端侧跳过词牌正确性校验，只校验"能不能框住"）。
"""
import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from bench_engines import CORPUS, VERSES, match_head, merge_rows, norm, to_blocks  # noqa: E402

OUT = None


def main():
    from rapidocr import RapidOCR, ModelType, OCRVersion

    engine = RapidOCR(params={
        "Det.ocr_version": OCRVersion.PPOCRV5,
        "Rec.ocr_version": OCRVersion.PPOCRV5,
        "Det.model_type": ModelType.MOBILE,
        "Rec.model_type": ModelType.MOBILE,
    })

    files = sorted(f for f in os.listdir(CORPUS) if f.endswith(".png"))
    truth = {}
    for f in files:
        out = engine(os.path.join(CORPUS, f))
        boxes = list(out.boxes) if out.boxes is not None else []
        texts = list(out.txts) if out.txts is not None else []
        items = to_blocks(boxes, texts)
        best = match_head(merge_rows(items))
        name = f.replace(".png", "")
        truth[name] = best[0]["pai"] if best and best[1] >= 0.72 else ""

    with io.open(OUT, "w", encoding="utf-8") as fh:
        json.dump(truth, fh, ensure_ascii=False, indent=1)

    empty = [k for k, v in truth.items() if not v]
    print("标注 %d 条，空值 %d 条" % (len(truth), len(empty)))
    if empty:
        print("  空值:", ", ".join(empty))
    for k, v in list(truth.items())[:8]:
        print("  %-6s -> %s" % (k, v or "(未定)"))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python tools/gen_bench_truth.py <输出 truth.json>")
        sys.exit(2)
    OUT = sys.argv[1]
    main()
