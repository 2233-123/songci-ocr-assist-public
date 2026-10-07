# -*- coding: utf-8 -*-
"""在 29 张真实游戏帧上横向对比 PP-OCR 各版本，用应用真实的匹配算法打分。

不依赖手机、不依赖模拟器 —— RapidOCR 的 pip 包在桌面就能跑，所以这一步是纯本地的。
"""
import io
import json
import os
import re
import statistics
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORPUS = os.path.join(ROOT, ".jtmp", "corpus")
INDEX = os.path.join(ROOT, "app", "src", "main", "assets", "verses.json")

idx = json.load(io.open(INDEX, encoding="utf-8"))
VERSES = idx["verses"]


def norm(s):
    return re.sub(r"[^\u4e00-\u9fff0-9]", "", s or "")


def lev(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, cb in enumerate(b, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb))
        prev = cur
    return prev[-1]


def sim(a, b):
    if not a or not b:
        return 0.0
    return 1 - lev(a, b) / max(len(a), len(b))


def match_head(blocks, threshold=0.65):
    """复刻 Matcher.scanHead + mergeAdjacent：整体 + 所有长度 ≥5 的子串，取最高分。

    注意 blocks 必须已经过 [merge_lines] 处理 —— 不同版本引擎对同一行的切分粒度不同
    （PP-OCRv5 会按标点把一行拆成 3 段，v4 通常合成 1 段），不拼接就会误判为"读不到"。
    """
    best = None
    for txt in blocks:
        cands = [txt] + [
            txt[i:j] for i in range(len(txt)) for j in range(i + 5, len(txt) + 1)
        ]
        for cand in cands:
            for v in VERSES:
                s = sim(cand, v["head"])
                if s >= threshold and (best is None or s > best[1]):
                    best = (v, s)
    return best


def merge_rows(items, y_tol=0.012, x_gap_max=0.06):
    """把「同一行的多个文本块」按 X 顺序拼接。

    items: [(cx, cy, x0, x1, text)]，归一化坐标。

    约束：Y 中心接近（同一行）+ X 间隙不超过 x_gap_max（相邻）。这样既能拼回
    PP-OCRv5 按标点切开的首句（`（首句）` / `对潇潇暮雨洒江天，` / `一番洗清秋`），
    又不会把隔着很远的右侧标签（「豪放词情」）并进来。
    """
    if not items:
        return []
    rows = []
    for it in sorted(items, key=lambda t: (t[1], t[2])):
        cy = it[1]
        for row in rows:
            if abs(row["cy"] - cy) <= y_tol:
                row["items"].append(it)
                row["cy"] = sum(i[1] for i in row["items"]) / len(row["items"])
                break
        else:
            rows.append({"cy": cy, "items": [it]})

    out = []
    for row in rows:
        merged = []
        for part in sorted(row["items"], key=lambda t: t[2]):
            if merged and part[2] - merged[-1][3] <= x_gap_max:
                prev = merged[-1]
                merged[-1] = (prev[0], prev[1], prev[2], max(prev[3], part[3]), prev[4] + part[4])
            else:
                merged.append(part)
        out.extend(m[4] for m in merged)
    return [norm(t) for t in out]


def to_blocks(boxes, texts, w=2608, h=1200):
    """把引擎输出的 box+text 归一化成 (cx, cy, x0, x1, text)。"""
    items = []
    for box, text in zip(boxes, texts):
        xs = [p[0] for p in box]
        ys = [p[1] for p in box]
        items.append((sum(xs) / 4 / w, sum(ys) / 4 / h, min(xs) / w, max(xs) / w, text))
    return items


def evaluate(label, ocr, files, w=2608, h=1200, quiet=False):
    ok = 0
    times = []
    fails = []
    for f in files:
        t0 = time.time()
        out = ocr(os.path.join(CORPUS, f))
        times.append((time.time() - t0) * 1000)

        # rapidocr v3 返回对象带 .boxes/.txts；v1 返回 (list, elapse)
        if hasattr(out, "txts"):
            boxes = out.boxes if out.boxes is not None else []
            texts = out.txts if out.txts is not None else []
            boxes, texts = list(boxes), list(texts)
        elif isinstance(out, tuple):
            res = out[0]
            res = [] if res is None else list(res)
            boxes, texts = [r[0] for r in res], [r[1] for r in res]
        else:
            boxes, texts = [], []

        # 归一化 + 同行拼接后再匹配（引擎间切分粒度不同，必须先对齐）
        items = to_blocks(boxes, texts, w, h)
        blocks = merge_rows(items)
        # 选项气泡：中部区域里的块按原样（不拼接）
        bubbles = [norm(it[4]) for it in items if 0.30 <= it[1] <= 0.62]
        best = match_head(blocks)
        if best is None:
            fails.append((f, "首句未匹配"))
            continue
        v, s = best
        hit = any(b == v["pai"] for b in bubbles)
        if hit:
            ok += 1
        else:
            fails.append((f, "%s 气泡未找到" % v["pai"]))

    print("%-22s 命中 %2d/%d  耗时中位 %6.0f ms (%.0f~%.0f)"
          % (label, ok, len(files), statistics.median(times), min(times), max(times)))
    for f, why in fails:
        print("      ✗ %s  %s" % (f, why))
    return ok, statistics.median(times), fails


def main():
    files = sorted(f for f in os.listdir(CORPUS) if f.endswith(".png"))
    print("语料 %d 张 / 索引 %d 条词" % (len(files), len(VERSES)))
    print()

    from rapidocr import RapidOCR, ModelType, OCRVersion

    combos = []
    for ver in ("PPOCRV4", "PPOCRV5", "PPOCRV6"):
        combos.append((ver, getattr(OCRVersion, ver)))

    results = []
    for name, ver in combos:
        try:
            params = {
                "Global.text_score": 0.0,
                "Det.ocr_version": ver,
                "Rec.ocr_version": ver,
                "Det.model_type": ModelType.MOBILE,
                "Rec.model_type": ModelType.MOBILE,
            }
            engine = RapidOCR(params=params)
            ok, med, fails = evaluate(name, engine, files)
            results.append((name, ok, med, fails))
        except Exception as e:
            print("%-22s 不可用: %s: %s" % (name, type(e).__name__, e))
    print()
    print("=== 汇总 ===")
    for name, ok, med, _ in results:
        print("  %-10s 命中 %2d/%d   %.0f%%   中位 %6.0f ms"
              % (name, ok, len(files), ok * 100.0 / len(files), med))


if __name__ == "__main__":
    main()
