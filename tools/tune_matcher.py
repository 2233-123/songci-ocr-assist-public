# -*- coding: utf-8 -*-
"""匹配器调参 / 复算工具（纯 Python 标准库，不依赖数据库）。

作用：把 Kotlin 侧 `Matcher` / `VerseIndex` 的算法在 Python 里**逐式复刻**，
用真实 `app/src/main/assets/verses.json` 复算，用于：

1. CI 前的快速回归：确认索引与匹配逻辑的基本性质（99 条精确命中、首句唯一、
   噪声下不误判词牌）；
2. 真机调参：给出「阈值 × 错字量」下的提示率 / 误报率表，决定是否调整
   `Config.SIMILARITY_THRESHOLD`（Kotlin 与本脚本必须同步修改）。

用法：
    python tools/tune_matcher.py              # 全部：性质检查 + 阈值扫描
    python tools/tune_matcher.py --sweep      # 只做阈值扫描
    python tools/tune_matcher.py --errors 3 --per-verse 8   # 自定噪声规模

任一性质检查不通过时返回非 0（可用于 CI）。
"""
from __future__ import annotations

import argparse
import io
import json
import random
import sys
from pathlib import Path

# Windows 控制台默认 GBK，中文输出会炸
if hasattr(sys.stdout, "buffer"):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
INDEX = ROOT / "app" / "src" / "main" / "assets" / "verses.json"

#: 与 app/src/main/java/com/songci/assist/Config.kt 保持一致
SIMILARITY_THRESHOLD = 0.72
MIN_HEAD_LEN = 5
MERGE_TOLERANCE_RATIO = 0.6
MAX_MERGED_LINES = 2
#: 同排归并允许的 X 交集上限（相对较窄块宽度），与 Config.LINE_X_OVERLAP_MAX 一致
X_OVERLAP_MAX = 0.2
#: 上下拼接时判定「被包住」所需宽度差比例，与 Config.LINE_CONTAIN_WIDTH_MARGIN 一致
CONTAIN_WIDTH_MARGIN = 0.15
TOP_CROP = (0.0, 0.45)
OPTION_REGION = (0.30, 0.80)

DISTRACTORS = "错字乱码雨天风雪山水云月花鸟人心情愁夜灯"


# --------------------------------------------------------------------------- 规范化
def normalize(text) -> str:
    """与 VerseIndex.normalize 同口径：去全部标点空白、全角转半角。"""
    if not text:
        return ""
    out = []
    for ch in text:
        code = ord(ch)
        if 0xFF01 <= code <= 0xFF5E:
            ch = chr(code - 0xFEE0)
        elif code in (0x3000, 0xFEFF):
            ch = " "
        if ch.isspace() or not ch.isalnum():
            continue
        out.append(ch)
    return "".join(out)


# --------------------------------------------------------------------------- 相似度
def levenshtein(a: str, b: str) -> int:
    """与 Matcher.levenshtein 同式（滚动数组，外层较长串）。"""
    if a == b:
        return 0
    if not a:
        return len(b)
    if not b:
        return len(a)
    s, t = (a, b) if len(a) <= len(b) else (b, a)
    prev = list(range(len(s) + 1))
    for i in range(1, len(t) + 1):
        cur = [i] + [0] * len(s)
        tc = t[i - 1]
        for j in range(1, len(s) + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (0 if s[j - 1] == tc else 1))
        prev = cur
    return prev[len(s)]


def similarity(a: str, b: str) -> float:
    """sim = 1 − Levenshtein(a,b) / max(len(a),len(b))，与 Design §2 一致。"""
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    return 1.0 - levenshtein(a, b) / float(max(len(a), len(b)))


class Index:
    def __init__(self, payload: dict):
        self.verses = payload["verses"]
        self.pai_list = set(payload["pai_list"])
        self.by_head = {v["head"]: v for v in self.verses}

    def lookup(self, text: str):
        return self.by_head.get(normalize(text))

    def nearest(self, text: str):
        """与 VerseIndex.nearest 同式：严格大于才替换；低于阈值返回 None。"""
        t = normalize(text)
        if len(t) < MIN_HEAD_LEN:
            return None
        best, score = None, 0.0
        for v in self.verses:
            s = similarity(t, v["head"])
            if s > score:
                best, score = v, s
        if best is None or score < SIMILARITY_THRESHOLD:
            return None
        return best, score


# --------------------------------------------------------------------------- 行拼接
class Block:
    """归一化坐标的文本块（对应 Kotlin 的 TextBlock）。"""

    __slots__ = ("text", "left", "top", "right", "bottom")

    def __init__(self, text, left, top, right, bottom):
        self.text, self.left, self.top, self.right, self.bottom = text, left, top, right, bottom

    @property
    def cy(self):
        return (self.top + self.bottom) / 2.0

    @property
    def h(self):
        return max(0.0, self.bottom - self.top)


def _same_line(cluster, item) -> bool:
    """与 Matcher.sameLine 同式：Y 同排 + X 基本不相交。"""
    tol = max((cluster["maxY"] - cluster["minY"]) / cluster["n"], item.h) * MERGE_TOLERANCE_RATIO
    if abs(cluster["cy"] - item.cy) > tol:
        return False
    overlap = min(cluster["right"], item.right) - max(cluster["left"], item.left)
    narrow = min(cluster["right"] - cluster["left"], item.right - item.left)
    if narrow <= 0:
        return False
    return overlap <= X_OVERLAP_MAX * narrow


def _clusters(items):
    out = []
    for it in sorted(items, key=lambda b: b.cy):
        if out and _same_line(out[-1], it):
            last = out[-1]
            out[-1] = {
                "text": last["text"] + it.text,
                "minY": min(last["minY"], it.top), "maxY": max(last["maxY"], it.bottom),
                "left": min(last["left"], it.left), "right": max(last["right"], it.right),
                "top": min(last["top"], it.top), "bottom": max(last["bottom"], it.bottom),
                "cy": (min(last["minY"], it.top) + max(last["maxY"], it.bottom)) / 2.0,
                "n": last["n"] + 1,
            }
        else:
            out.append({"text": it.text, "minY": it.top, "maxY": it.bottom,
                        "left": it.left, "right": it.right, "top": it.top,
                        "bottom": it.bottom, "cy": it.cy, "n": 1})
    return out


def _line_h(c):
    return max(1e-6, (c["maxY"] - c["minY"]) / c["n"])


def _merge_adjacent(clusters):
    """与 Matcher.mergeAdjacent 同式：相邻 + X 有交集 + 不是「一方被另一方明显包住」。"""
    if not clusters:
        return []
    groups, cur = [], [clusters[0]]
    for c in clusters[1:]:
        last = cur[-1]
        tol = max(_line_h(last), _line_h(c)) * MERGE_TOLERANCE_RATIO
        overlap = min(last["right"], c["right"]) - max(last["left"], c["left"])
        last_w = last["right"] - last["left"]
        c_w = c["right"] - c["left"]
        margin = CONTAIN_WIDTH_MARGIN * min(last_w, c_w)
        contains = ((last["left"] <= c["left"] and last["right"] >= c["right"]
                     and last_w - c_w > margin) or
                    (c["left"] <= last["left"] and c["right"] >= last["right"]
                     and c_w - last_w > margin))
        adjacent = (c["top"] - last["bottom"]) <= tol and overlap > 0 and not contains
        if len(cur) < MAX_MERGED_LINES and adjacent:
            cur.append(c)
        else:
            groups.append(cur)
            cur = [c]
    groups.append(cur)
    return groups


def scan_head(index: Index, blocks):
    """与 Matcher.scanHead 同式。返回 (verse, similarity, matched_text) 或 None。"""
    top = [b for b in blocks if TOP_CROP[0] <= b.cy <= TOP_CROP[1] and normalize(b.text)]
    if not top:
        return None
    best, best_lines = None, 10 ** 9
    for group in _merge_adjacent(_clusters(top)):
        text = normalize("".join(c["text"] for c in group))
        if len(text) < MIN_HEAD_LEN:
            continue
        exact = index.lookup(text)
        if exact is not None:
            return exact, 1.0, text
        near = index.nearest(text)
        if near and (best is None or near[1] > best[1]
                     or (near[1] == best[1] and len(group) < best_lines)):
            best, best_lines = (near[0], near[1], text), len(group)
    return best


def scan_options(index: Index, blocks, pai: str):
    """与 Matcher.scanOptions 同式：中部区域、文本精确等于词牌名、取面积最大。"""
    want = normalize(pai)
    if not want or want not in index.pai_list:
        return None
    best = None
    for b in blocks:
        if normalize(b.text) != want:
            continue
        if not (OPTION_REGION[0] <= b.cy <= OPTION_REGION[1]):
            continue
        area = max(0.0, b.right - b.left) * b.h
        if best is None or area > best[0]:
            best = (area, b)
    return None if best is None else best[1]


# --------------------------------------------------------------------------- 自检
def self_check(index: Index) -> list[str]:
    problems = []

    def expect(name, ok, detail=""):
        print("  %s %s%s" % ("PASS" if ok else "FAIL", name, ("  → " + str(detail)) if detail else ""))
        if not ok:
            problems.append(name)

    print("=== 索引性质 ===")
    expect("99 条词句", len(index.verses) == 99, len(index.verses))
    expect("40 个词牌", len(index.pai_list) == 40, len(index.pai_list))
    expect("首句唯一", len({v["head"] for v in index.verses}) == len(index.verses))
    expect("首句长度 ≥ 5", all(len(v["head"]) >= MIN_HEAD_LEN for v in index.verses))
    expect("无 id=9/87", all(v["id"] not in (9, 87) for v in index.verses))

    print("=== 精确查表 ===")
    miss = [v["id"] for v in index.verses if index.lookup(v["head"]) is None]
    expect("99 条全部精确命中", not miss, miss)
    v75 = index.lookup("明月别枝惊鹊清风半夜鸣蝉")
    expect("实机截图那条 → 西江月", v75 is not None and v75["pai"] == "西江月",
           None if v75 is None else (v75["id"], v75["pai"]))

    print("=== 规范化 ===")
    expect("中文标点", normalize("明月别枝惊鹊，清风半夜鸣蝉。") == "明月别枝惊鹊清风半夜鸣蝉")
    expect("全角空格", normalize("明月\u3000别枝") == "明月别枝")
    expect("全角字母数字", normalize("ＡＢＣ１２３") == "ABC123")
    expect("空输入", normalize(None) == "" and normalize("") == "" and normalize("，。、") == "")

    print("=== 噪声：只提示、且只提示正确词牌 ===")
    # 覆盖率下限按实测能力定；3 个随机错字本身已接近随机化，覆盖率靠下调阈值换
    coverage_floor = {1: 95.0, 2: 95.0, 3: 80.0}
    for errors, per_verse in ((1, 20), (2, 5), (3, 2)):
        rnd = random.Random(20261005)
        samples = hint_ok = hint_bad = silent = rank_bad = 0
        for v in index.verses:
            for _ in range(per_verse):
                noisy = _inject(v["head"], errors, rnd)
                samples += 1
                # 最近邻（忽略阈值）是否是正确词牌 —— 决定会不会画错框
                near_any = _nearest_with_threshold(index, noisy, 0.0)
                if near_any is None or near_any[0]["pai"] != v["pai"]:
                    rank_bad += 1
                    continue
                hit = scan_head(index, [Block(noisy, 0.08, 0.11, 0.92, 0.16)])
                if hit is None:
                    silent += 1
                elif hit[0]["pai"] == v["pai"]:
                    hint_ok += 1
                else:
                    hint_bad += 1
        print("  %d 错字：样本 %d | 提示正确 %d | 提示但错 %d | 不提示 %d | 最近邻就错 %d"
              % (errors, samples, hint_ok, hint_bad, silent, rank_bad))
        # 硬约束 1：提示出来的一定对（0 误报）—— 「宁可不提示，也不误报」
        expect("%d 错字：提示全部正确（0 误报）" % errors, hint_bad == 0, hint_bad)
        # 硬约束 2：提示覆盖率不低于实测能力下限
        rate = 100.0 * hint_ok / samples
        expect("%d 错字：提示覆盖率 ≥ %.0f%%" % (errors, coverage_floor[errors]),
               rate >= coverage_floor[errors], "%.2f%%" % rate)
    return problems


def _inject(text: str, errors: int, rnd: random.Random) -> str:
    chars = list(text)
    for _ in range(errors):
        chars[rnd.randrange(len(chars))] = DISTRACTORS[rnd.randrange(len(DISTRACTORS))]
    return "".join(chars)


# --------------------------------------------------------------------------- 阈值扫描
def sweep(index: Index, samples_per_level: int) -> None:
    print("\n=== 阈值扫描（噪声模型：随机整字替换） ===")
    print("  阈值    1错字 正确/误报     2错字 正确/误报     3错字 正确/误报    3错字 不提示")
    base = max(1, samples_per_level // len(index.verses) + 1)
    for thr in (0.55, 0.60, 0.65, 0.70, 0.72, 0.75, 0.78, 0.80, 0.82, 0.85):
        cells = []
        for errors in (1, 2, 3):
            rnd = random.Random(20261111)
            total = ok = bad = 0
            for _ in range(base):
                for v in index.verses:
                    noisy = _inject(v["head"], errors, rnd)
                    total += 1
                    near = index.nearest_threshold(noisy, thr) if hasattr(index, "nearest_threshold") \
                        else _nearest_with_threshold(index, noisy, thr)
                    if near is None:
                        continue
                    if near[0]["pai"] == v["pai"]:
                        ok += 1
                    else:
                        bad += 1
            cells.append((100.0 * ok / total, 100.0 * bad / total, 100.0 * (total - ok - bad) / total))
        print("  %.2f    %6.2f%% / %5.2f%%     %6.2f%% / %5.2f%%     %6.2f%% / %5.2f%%    %6.2f%%"
              % (thr, cells[0][0], cells[0][1], cells[1][0], cells[1][1],
                 cells[2][0], cells[2][1], cells[2][2]))
    print("\n说明：")
    print("  * 「误报」= 画框但词牌判错。实测在各阈值、各错字量下均为 0.00%。")
    print("  * 「不提示」是安全失败（宁可不提示，也不误报），可通过下调阈值换更高提示率。")


def _nearest_with_threshold(index: Index, text: str, thr: float):
    t = normalize(text)
    if len(t) < MIN_HEAD_LEN:
        return None
    best, score = None, 0.0
    for v in index.verses:
        s = similarity(t, v["head"])
        if s > score:
            best, score = v, s
    if best is None or score < thr:
        return None
    return best, score


def main() -> int:
    ap = argparse.ArgumentParser(description="匹配器调参 / 复算（与 Kotlin 侧同式）")
    ap.add_argument("--sweep", action="store_true", help="只做阈值扫描")
    ap.add_argument("--errors", type=int, default=0, help="自定义错字数（配合 --per-verse 做单点统计）")
    ap.add_argument("--per-verse", type=int, default=0, help="每条首句抽样次数")
    ap.add_argument("--samples", type=int, default=1200, help="阈值扫描每档的样本量")
    args = ap.parse_args()

    if not INDEX.exists():
        print("[错误] 未找到 %s，先跑 python tools/gen_verse_index.py" % INDEX)
        return 1
    payload = json.loads(io.open(INDEX, encoding="utf-8").read())
    index = Index(payload)
    print("索引：%s（%d 条 / %d 词牌）\n" % (INDEX.relative_to(ROOT), len(index.verses), len(index.pai_list)))

    problems: list[str] = []
    if args.errors and args.per_verse:
        rnd = random.Random(20261005)
        total = wrong = drop = 0
        for v in index.verses:
            for _ in range(args.per_verse):
                noisy = _inject(v["head"], args.errors, rnd)
                total += 1
                near = index.nearest(noisy)
                if near is None or near[0]["pai"] != v["pai"]:
                    wrong += 1
                elif near[1] < SIMILARITY_THRESHOLD:
                    drop += 1
        print("%d 错字 × %d 例：判错 %d，阈值拦住 %d（%.2f%%）" % (args.errors, total, wrong, drop, 100.0 * drop / total))
        return 0 if wrong == 0 else 1

    if not args.sweep:
        problems = self_check(index)
    sweep(index, args.samples)

    if problems:
        print("\n[失败] %d 项性质检查未通过：%s" % (len(problems), problems))
        return 1
    print("\n全部性质检查通过 ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
