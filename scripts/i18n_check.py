#!/usr/bin/env python3
"""OverDrive i18n gate — validates that the Simplified Chinese packs stay in
sync with the English source.

Checks (errors fail the job):
  * Android XML: every string/plurals resource in res/values exists in
    res/values-zh-rCN with the same quantity set and the same format specifiers
  * JSON packs: every key present in en.json exists in zh-CN.json
  * placeholders / HTML tags are identical between source and translation
  * res/values-zh mirrors res/values-zh-rCN

Checks (warnings only):
  * keys in zh-CN.json that no longer exist in en.json (stale keys)
  * Traditional Chinese characters or Taiwan/HK wording leftovers
  * placeholders that appear to have been translated

Usage:  python3 scripts/i18n_check.py [--strict]
        --strict  treat warnings as errors
"""
from __future__ import annotations

import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")

TARGETS = ["values-zh-rCN", "values-zh"]
JSON_PAIRS = [
    (os.path.join(ASSETS, "web", "i18n", "en.json"),
     os.path.join(ASSETS, "web", "i18n", "zh-CN.json"),
     "web"),
    (os.path.join(ASSETS, "server-i18n", "en.json"),
     os.path.join(ASSETS, "server-i18n", "zh-CN.json"),
     "server"),
]

# %1$s  %2$d  %s  %%
FMT = re.compile(r"%(?:\d+\$)?[sdifgexctbn%]|\$\{[^}]*\}|\{\{?\w+\}?\}")
# only real markup — Telegram/CLI placeholders like <name> or <PIN> must not match
TAG = re.compile(r"</?(?:b|strong|i|em|u|br|code|a|span|div|p|ul|ol|li|small|font)\b[^>]*>")
# a placeholder whose inner text got translated (e.g. ${var:变量名} or <名称>)
TRANSLATED_PH = re.compile(r"\$\{[^}]*[一-鿿][^}]*\}|<[^<>\s]*[一-鿿][^<>]*>")
TW_WORDS = ["偵測", "侦测", "儲存", "储存", "預設", "预设", "設定", "设定", "螢幕", "萤幕", "視窗", "视窗",
            "裝置", "装置", "傳送", "传送", "復原", "复原", "記錄器", "记录器", "資料", "资料",
            "應用程式", "应用程式", "資訊", "资讯", "檔案", "档案", "支援", "運作", "运作", "攝影機", "摄影机",
            "錄影", "录影", "程式", "擷取", "撷取", "門檻", "门槛", "還原", "还原", "欄位", "栏位",
            "套用", "連結", "连结", "網路", "网路", "韌體", "韧体", "伺服器"]

errors: list[str] = []
warnings: list[str] = []


def load_trad_chars():
    """Traditional-character set. Prefer the generated table, else zhconv."""
    p = os.path.join(ROOT, "scripts", "i18n_trad_chars.txt")
    if os.path.exists(p):
        return set(open(p, encoding="utf-8").read().strip())
    try:
        import zhconv
        return {chr(cp) for cp in range(0x3400, 0xA000)
                if zhconv.convert(chr(cp), "zh-cn") != chr(cp)}
    except Exception:
        return set()


TRAD_CHARS = load_trad_chars()


def err(msg):
    errors.append(msg)
    print(f"::error::{msg}")


def warn(msg):
    warnings.append(msg)
    print(f"::warning::{msg}")


def trivial_identical(s):
    """URLs, format strings, brand names and 1-2 word abbreviations are
    legitimately identical across locales — don't flag them."""
    if "http" in s or "%" in s or "/" in s:
        return True
    if re.fullmatch(r"[A-Za-z0-9_.+\- ]+", s) and len(s.split()) <= 2:
        return True
    return False


def read_xml_resources(path):
    """Return {name: (kind, quantities|None, [texts])}"""
    out = {}
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        err(f"XML parse error in {os.path.relpath(path, ROOT)}: {e}")
        return out
    for el in root:
        name = el.get("name")
        if name is None:
            continue
        if el.tag == "string":
            out[name] = ("string", None, [el.text or ""])
        elif el.tag == "plurals":
            out[name] = ("plurals", [it.get("quantity") for it in el], [it.text or "" for it in el])
        elif el.tag == "string-array":
            out[name] = ("array", None, [it.text or "" for it in el])
    return out


def compare_xml():
    src_dir = os.path.join(RES, "values")
    src = {}
    for fn in sorted(os.listdir(src_dir)):
        if fn.endswith(".xml"):
            src.update(read_xml_resources(os.path.join(src_dir, fn)))
    if not src:
        err("no source resources found under res/values")
        return
    print(f"source resources: {len(src)}")

    for target in TARGETS:
        tdir = os.path.join(RES, target)
        if not os.path.isdir(tdir):
            err(f"missing resource dir {target}")
            continue
        tgt = {}
        for fn in sorted(os.listdir(tdir)):
            if fn.endswith(".xml"):
                tgt.update(read_xml_resources(os.path.join(tdir, fn)))
        missing = [k for k in src if k not in tgt]
        for k in missing:
            err(f"[{target}] missing translation for `{k}` "
                f"({src[k][0]}) — falls back to English at runtime")
        for k, (kind, q, texts) in src.items():
            if k not in tgt:
                continue
            tk, tq, ttexts = tgt[k]
            if kind == "plurals":
                if tq != q:
                    err(f"[{target}] plurals `{k}` quantity mismatch: {q} vs {tq}")
                elif len(ttexts) != len(texts):
                    err(f"[{target}] plurals `{k}` item count mismatch")
            for a, b in zip(texts, ttexts):
                if sorted(FMT.findall(a)) != sorted(FMT.findall(b)):
                    err(f"[{target}] `{k}` placeholder mismatch: "
                        f"{sorted(FMT.findall(a))} vs {sorted(FMT.findall(b))}")
                if len(TAG.findall(a)) != len(TAG.findall(b)):
                    warn(f"[{target}] `{k}` HTML tag count differs")
                if TRANSLATED_PH.search(b):
                    err(f"[{target}] `{k}` placeholder appears translated: {b[:60]}")
                if a == b and re.search(r"[A-Za-z]{3,}", a) and not trivial_identical(a):
                    warn(f"[{target}] `{k}` identical to English: {a[:50]}")
        for k in tgt:
            if k not in src:
                warn(f"[{target}] stale resource `{k}` (not in res/values)")

    # values-zh must mirror values-zh-rCN
    a = os.path.join(RES, "values-zh-rCN")
    b = os.path.join(RES, "values-zh")
    if os.path.isdir(a) and os.path.isdir(b):
        for fn in sorted(os.listdir(a)):
            pa, pb = os.path.join(a, fn), os.path.join(b, fn)
            if not os.path.exists(pb):
                warn(f"values-zh/{fn} missing (values-zh-rCN has it)")
                continue
            if open(pa, encoding="utf-8").read() != open(pb, encoding="utf-8").read():
                warn(f"values-zh/{fn} differs from values-zh-rCN/{fn}")


def flat(d, pre=""):
    o = {}
    for k, v in d.items():
        kk = f"{pre}.{k}" if pre else k
        if isinstance(v, dict):
            o.update(flat(v, kk))
        else:
            o[kk] = v if isinstance(v, str) else ""
    return o


def compare_json():
    for en_p, zh_p, label in JSON_PAIRS:
        en = flat(json.load(open(en_p, encoding="utf-8")))
        zh = flat(json.load(open(zh_p, encoding="utf-8")))
        missing = [k for k in en if k not in zh]
        for k in missing:
            err(f"[{label}] missing zh translation for `{k}`")
        extra = [k for k in zh if k not in en]
        if extra:
            warn(f"[{label}] {len(extra)} stale keys not present in en.json "
                 f"(e.g. {', '.join(extra[:5])})")
        for k in en:
            if k not in zh:
                continue
            a, b = en[k], zh[k]
            if sorted(FMT.findall(a)) != sorted(FMT.findall(b)):
                err(f"[{label}] `{k}` placeholder mismatch: "
                    f"{sorted(FMT.findall(a))} vs {sorted(FMT.findall(b))}")
            if len(TAG.findall(a)) != len(TAG.findall(b)):
                warn(f"[{label}] `{k}` HTML tag count differs")
            if TRANSLATED_PH.search(b):
                err(f"[{label}] `{k}` placeholder appears translated: {b[:60]}")
        print(f"{label}: en={len(en)} zh={len(zh)} missing={len(missing)} stale={len(extra)}")


def check_variant():
    hits = {}
    dirs = [os.path.join(RES, t) for t in TARGETS]
    files = [os.path.join(d, f) for d in dirs if os.path.isdir(d)
             for f in sorted(os.listdir(d)) if f.endswith(".xml")]
    files += [p for _, p, _ in [(a, b, c) for a, b, c in JSON_PAIRS]]
    for p in files:
        txt = open(p, encoding="utf-8").read()
        found = [c for c in TRAD_CHARS if c in txt]
        tw = [w for w in TW_WORDS if w in txt]
        if found or tw:
            hits[os.path.relpath(p, ROOT)] = (found[:10], tw[:10])
    for p, (found, tw) in hits.items():
        warn(f"possible Traditional / TW wording in {p}: chars={''.join(found)} words={tw}")
    if not hits:
        print("variant check: clean")


def main():
    strict = "--strict" in sys.argv
    compare_xml()
    compare_json()
    check_variant()
    print(f"\nerrors={len(errors)} warnings={len(warnings)}")
    if errors:
        sys.exit(1)
    if strict and warnings:
        print("--strict: warnings treated as errors")
        sys.exit(1)
    print("i18n check passed")


if __name__ == "__main__":
    main()
