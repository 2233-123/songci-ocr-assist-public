# -*- coding: utf-8 -*-
"""发布包校验（纯 Python 标准库，调用 build-tools 的 apksigner / zipalign）。

用途：发布前核对 APK 的关键内容与签名，一道兜底 —— 防止"包打错/签名不对/模型没打进去"。

典型流程：
    1) 出包：CI 产物，或本地 ./gradlew assembleRelease → app-release.apk（已用 release keystore 签名）
    2) 校验：python tools/check_apk.py verify app-release.apk
       （核对包名/版本/权限/ABI/assets 与签名指纹）
    3) 只看指纹：python tools/check_apk.py certs app-release.apk

**本工程不做第三方加固**（如 360 加固保）：加固会改写 DEX、hook 反射与 Binder，
而本 App 的关键能力（MediaProjection 录屏、TYPE_APPLICATION_OVERLAY 悬浮窗、
ML Kit 的 native OCR 管道）恰好都依赖这些机制，加固后需逐项真机复测，
收益与风险不成正比。`resign` 子命令因此保留但**默认流程不再使用**
（它只是"给未签名包补签名"的通用能力，改用别的分发渠道时仍可能用上）。

用法：
    python tools/check_apk.py verify  <apk>
    python tools/check_apk.py certs   <apk>        # 只打印签名证书指纹
    python tools/check_apk.py resign  <apk> [--out OUT] [--ks FILE] [--alias A]
                                             [--storepass P] [--keypass P]

keystore 默认从工程根的 keystore.properties 读（CI 与本机都用这一份）。
apksigner/zipalign 从 ANDROID_HOME / ANDROID_SDK_ROOT 下的 build-tools 里找最新的一个。
"""
from __future__ import annotations

import argparse
import io
import json
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

if hasattr(sys.stdout, "buffer"):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]

#: 签名后必须仍然完好的关键内容（抽查，不是全量比对）
REQUIRED_IN_ZIP = [
    "AndroidManifest.xml",
    "classes.dex",
    "assets/verses.json",
    "lib/arm64-v8a/libmlkit_google_ocr_pipeline.so",
]
REQUIRED_PREFIXES = [
    "assets/mlkit-google-ocr-models/",
]


def fail(msg: str) -> int:
    print("[失败] " + msg)
    return 1


def find_sdk_tool(name: str) -> str | None:
    """在 Android SDK 的 build-tools 里找最新版本的工具。"""
    roots = []
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(key)
        if v:
            roots.append(Path(v))
    # local.properties 里的 sdk.dir
    lp = ROOT / "local.properties"
    if lp.exists():
        for line in io.open(lp, encoding="utf-8"):
            if line.strip().startswith("sdk.dir="):
                roots.append(Path(line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")))

    candidates: list[Path] = []
    for root in roots:
        bt = root / "build-tools"
        if bt.is_dir():
            candidates += [d for d in bt.iterdir() if d.is_dir()]
    # 版本号排序（34.0.0 > 9.0.0 这类简单场景够用）
    def key(p: Path):
        parts = p.name.split(".")
        out = []
        for x in parts:
            out.append(int(x) if x.isdigit() else 0)
        return out

    for d in sorted(candidates, key=key, reverse=True):
        for ext in (".bat", ".exe", ""):
            exe = d / (name + ext)
            if exe.exists():
                return str(exe)
    found = shutil.which(name)
    return found


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    print("  $ " + " ".join(str(c) for c in cmd))
    return subprocess.run(cmd, **kw)


def load_keystore_args(args) -> tuple[str, str, str, str]:
    store = args.ks
    storepass = args.storepass
    alias = args.alias
    keypass = args.keypass
    props = ROOT / "keystore.properties"
    if props.exists():
        kv = {}
        for line in io.open(props, encoding="utf-8"):
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            kv[k.strip()] = v.strip()
        store = store or kv.get("storeFile")
        storepass = storepass or kv.get("storePassword")
        alias = alias or kv.get("keyAlias")
        keypass = keypass or kv.get("keyPassword")
        if store and not os.path.isabs(store):
            store = str(ROOT / store)
    return store or "", storepass or "", alias or "", keypass or ""


def apk_summary(apk: Path) -> None:
    print("  文件   : %s" % apk)
    print("  大小   : %.2f MB" % (apk.stat().st_size / 1048576))

    aapt = find_sdk_tool("aapt2")
    if aapt:
        r = run([aapt, "dump", "badging", str(apk)], capture_output=True, text=True,
                encoding="utf-8", errors="replace")
        if r.returncode == 0:
            for line in r.stdout.splitlines():
                if line.startswith(("package:", "sdkVersion:", "targetSdkVersion:",
                                    "application-label:", "native-code:")):
                    print("  " + line.strip())
                elif line.startswith("uses-permission:"):
                    print("  " + line.strip())
        else:
            print("  [注意] aapt2 dump 失败，跳过清单信息")

    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        print("  关键内容:")
        for item in REQUIRED_IN_ZIP:
            print("    %-52s %s" % (item, "✓" if item in names else "✗ 缺失"))
        for prefix in REQUIRED_PREFIXES:
            n = sum(1 for x in names if x.startswith(prefix))
            print("    %-52s %s (%d 个文件)" % (prefix + "*", "✓" if n else "✗ 缺失", n))
        dex = sorted(x for x in names if x.startswith("classes") and x.endswith(".dex"))
        print("    %-52s %s" % ("classes*.dex", ", ".join(dex) if dex else "✗ 缺失"))


def signed_certs(apk: Path) -> tuple[bool, str]:
    apksigner = find_sdk_tool("apksigner")
    if not apksigner:
        return False, "找不到 apksigner（设置 ANDROID_HOME 或 local.properties）"
    r = run([apksigner, "verify", "--print-certs", str(apk)],
            capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        return False, (r.stdout or "") + (r.stderr or "")
    return True, r.stdout or ""


def cmd_verify(args) -> int:
    apk = Path(args.apk)
    if not apk.exists():
        return fail("找不到 %s" % apk)
    print("=== APK 摘要 ===")
    apk_summary(apk)
    print("=== 签名 ===")
    ok, out = signed_certs(apk)
    if not ok:
        print("[失败] 签名校验未通过：")
        print(out)
        print("提示：这个包没有有效签名，无法安装。检查 keystore.properties 是否配好，")
        print("      或用 python tools/check_apk.py resign <apk> 补签名。")
        return 1
    for line in out.splitlines():
        if "SHA-256" in line or "Signer" in line or "Verified using" in line:
            print("  " + line.strip())
    print("[通过] 签名有效，关键内容齐全")
    return 0


def cmd_certs(args) -> int:
    apk = Path(args.apk)
    if not apk.exists():
        return fail("找不到 %s" % apk)
    ok, out = signed_certs(apk)
    print(out if ok else "[失败] " + out)
    return 0 if ok else 1


def cmd_resign(args) -> int:
    apk = Path(args.apk)
    if not apk.exists():
        return fail("找不到 %s" % apk)
    out = Path(args.out) if args.out else apk.with_name(apk.stem + "-signed.apk")
    store, storepass, alias, keypass = load_keystore_args(args)
    if not store or not os.path.exists(store):
        return fail("keystore 不存在：%r（用 --ks 指定，或写 keystore.properties）" % store)
    zipalign = find_sdk_tool("zipalign")
    apksigner = find_sdk_tool("apksigner")
    if not apksigner:
        return fail("找不到 apksigner")

    aligned = out.with_name(out.stem + "-aligned.apk")
    if zipalign:
        print("=== 1/2 zipalign ===")
        # 已对齐则可能失败；失败就退回直接在原包上签名
        r = run([zipalign, "-f", "-p", "4", str(apk), str(aligned)],
                capture_output=True, text=True, encoding="utf-8", errors="replace")
        target = aligned if r.returncode == 0 else apk
        if r.returncode != 0:
            print("  [注意] zipalign 失败，跳过对齐直接签名")
    else:
        print("  [注意] 找不到 zipalign，跳过对齐")
        target = apk

    # 口令走临时文件，避免出现在命令行/日志里
    tmpdir = Path(__import__("tempfile").mkdtemp(prefix="songci-sign-"))
    sp = tmpdir / "storepass.txt"
    kp = tmpdir / "keypass.txt"
    sp.write_text(storepass, encoding="utf-8")
    kp.write_text(keypass or storepass, encoding="utf-8")
    try:
        print("=== 2/2 apksigner 重签名 ===")
        cmd = [apksigner, "sign",
               "--ks", store, "--ks-key-alias", alias,
               "--ks-pass", "file:" + str(sp), "--key-pass", "file:" + str(kp),
               "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
               "--v3-signing-enabled", "true",
               "--out", str(out), str(target)]
        # 只回显命令里不含口令的部分
        print("  $ %s sign --ks %s --ks-key-alias %s --ks-pass file:<tmp> --key-pass file:<tmp> ... --out %s %s"
              % (apksigner, store, alias, out, target))
        r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if r.returncode != 0:
            return fail("重签名失败：\n" + (r.stdout or "") + (r.stderr or ""))
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    if aligned.exists():
        aligned.unlink()

    print("=== 校验 ===")
    ok, certs = signed_certs(out)
    if not ok:
        return fail("重签名后校验未通过：\n" + certs)
    for line in certs.splitlines():
        if "SHA-256" in line or "Signer" in line:
            print("  " + line.strip())
    print("[通过] 重签名完成：%s" % out)
    print("       下一步：python tools/check_apk.py verify %s" % out)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="发布包校验（签名 / 关键内容）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    for name, fn, help_text in (
        ("verify", cmd_verify, "校验签名与关键内容"),
        ("certs", cmd_certs, "打印签名证书指纹"),
    ):
        p = sub.add_parser(name, help=help_text)
        p.add_argument("apk")
        p.set_defaults(func=fn)

    p = sub.add_parser("resign", help="给未签名 APK 补 release 签名（默认流程不用）")
    p.add_argument("apk")
    p.add_argument("--out")
    p.add_argument("--ks")
    p.add_argument("--alias")
    p.add_argument("--storepass")
    p.add_argument("--keypass")
    p.set_defaults(func=cmd_resign)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
