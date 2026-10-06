# -*- coding: utf-8 -*-
"""一键自检：索引 + 匹配算法 + 资源引用（不需要 Android SDK）。

用法：
    python tools/verify_all.py          # 全部
    python tools/verify_all.py --quick  # 跳过阈值扫描（更快）

CI 里的 `verify-index` job 等价于本脚本；本地没装 Android SDK 时也建议先跑这个。
返回非 0 表示有问题（可直接用于 pre-commit / CI）。
"""
from __future__ import annotations

import io
import os
import re
import subprocess
import sys
from pathlib import Path

if hasattr(sys.stdout, "buffer"):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
PY = sys.executable


def run(title: str, script: Path, args: list[str] | None = None) -> bool:
    print("\n" + "=" * 70)
    print("▶ %s" % title)
    print("=" * 70)
    cmd = [PY, str(script)] + (args or [])
    result = subprocess.run(cmd, cwd=str(ROOT))
    if result.returncode != 0:
        print("[失败] %s（退出码 %d）" % (title, result.returncode))
        return False
    print("[通过] %s" % title)
    return True


def check_gradle_consistency() -> bool:
    """CI 里用 `gradle` 直接跑，因此它必须与 wrapper 锁定的版本一致。

    检查方式：从 wrapper 解析出版本号，再确认 CI 的「版本自检」步骤里
    **显式断言了这个版本号**（`test "$WRAPPER_VERSION" = "X.Y"` 或 `gradle-version: X.Y`）。

    注意不要去找 `gradle-X.Y-bin.zip` 这种"文件名"——CI 里写的是版本号本身，
    早先按文件名匹配导致本地 `verify_all` 长期误报失败。
    """
    print("\n" + "=" * 70)
    print("▶ Gradle 版本一致性（CI 用 gradle，本地用 wrapper）")
    print("=" * 70)
    props = (ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text(encoding="utf-8")
    workflow = (ROOT / ".github" / "workflows" / "android.yml").read_text(encoding="utf-8")
    m = re.search(r"gradle-([\d.]+)-bin\.zip", props)
    if not m:
        print("[失败] gradle-wrapper.properties 里找不到 distributionUrl 版本")
        return False
    version = m.group(1)
    # CI 里必须显式锁定同一个版本号（两种常见写法都接受）
    patterns = (
        'WRAPPER_VERSION" = "%s"' % version,
        "gradle-version: %s" % version,
        "gradle-version: '%s'" % version,
    )
    if not any(p in workflow for p in patterns):
        print("[失败] CI 的版本自检没有锁定 wrapper 版本：wrapper=%s" % version)
        print("       期望在 workflow 里出现其中之一：")
        for p in patterns:
            print("         %s" % p)
        return False
    if not (ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar").exists():
        print("[失败] 缺少 gradle/wrapper/gradle-wrapper.jar")
        return False
    print("[通过] wrapper 与 CI 都是 Gradle %s（且 wrapper jar 存在）" % version)
    return True


def check_asset_index() -> bool:
    """索引必须能被 gen_verse_index.py --check 认可。"""
    return run("索引校验（已提交的 verses.json）", ROOT / "tools" / "gen_verse_index.py", ["--check"])


def main() -> int:
    quick = "--quick" in sys.argv
    ok = True
    ok &= check_asset_index()
    ok &= run("匹配算法复算 + 阈值扫描", ROOT / "tools" / "tune_matcher.py",
              ["--samples", "200" if quick else "1000"])
    ok &= check_gradle_consistency()
    print("\n" + "=" * 70)
    print("全部通过 ✓" if ok else "存在失败项 ✗")
    print("=" * 70)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
