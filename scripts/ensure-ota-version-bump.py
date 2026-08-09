#!/usr/bin/env python3
"""Ensure versionCode is greater than published OTA; auto-bump if not.

Previously this step failed hard when a main commit forgot to bump versionCode
(common when shipping small domain fixes). That left green unit tests but a red
OTA publish. Now CI rewrites app/build.gradle.kts in the workspace so the APK
always ships with published+1 (and a bumped patch versionName when possible).

Repo source of truth still prefers explicit bumps in the commit; this is a
safety net so OTA never blocks on versionCode alone.
"""
from __future__ import annotations

import json
import os
import pathlib
import re
import sys
import urllib.request


GRADLE = pathlib.Path("app/build.gradle.kts")
VC_RE = re.compile(r"(versionCode\s*=\s*)(\d+)")
VN_RE = re.compile(r'(versionName\s*=\s*")([^"]+)(")')


def parse_local(text: str) -> tuple[int, str]:
    vc_m = VC_RE.search(text)
    vn_m = VN_RE.search(text)
    if not vc_m:
        raise SystemExit("Could not parse versionCode")
    version_name = vn_m.group(2) if vn_m else "0.0.0"
    return int(vc_m.group(2)), version_name


def bump_version_name(name: str) -> str:
    """Bump trailing numeric segment: 0.9.29 -> 0.9.30, 1.0 -> 1.1."""
    parts = name.split(".")
    for i in range(len(parts) - 1, -1, -1):
        if parts[i].isdigit():
            parts[i] = str(int(parts[i]) + 1)
            return ".".join(parts)
    return f"{name}.1"


def published_version_code() -> int:
    prev = (os.environ.get("PREV_VERSION_JSON") or "").strip()
    if prev and pathlib.Path(prev).is_file():
        try:
            return int(json.loads(pathlib.Path(prev).read_text()).get("versionCode") or 0)
        except Exception:
            return 0
    url = (
        os.environ.get("UPDATE_MANIFEST_URL")
        or "https://www.cleaningbutton.com/r2finance-builds/version.json"
    )
    try:
        with urllib.request.urlopen(url, timeout=15) as resp:
            return int(json.loads(resp.read().decode()).get("versionCode") or 0)
    except Exception as e:
        print(f"WARN: could not fetch published version.json ({e})", file=sys.stderr)
        return 0


def write_gradle(text: str, version_code: int, version_name: str) -> None:
    text2, n_vc = VC_RE.subn(rf"\g<1>{version_code}", text, count=1)
    if n_vc != 1:
        raise SystemExit("Failed to rewrite versionCode")
    text3, n_vn = VN_RE.subn(rf"\g<1>{version_name}\g<3>", text2, count=1)
    if n_vn != 1:
        # versionName optional for bump path if missing; keep text2
        GRADLE.write_text(text2, encoding="utf-8")
        return
    GRADLE.write_text(text3, encoding="utf-8")


def main() -> int:
    if not GRADLE.is_file():
        print("app/build.gradle.kts not found", file=sys.stderr)
        return 1
    text = GRADLE.read_text(encoding="utf-8")
    local_vc, local_vn = parse_local(text)
    published_vc = published_version_code()

    if published_vc > 0 and local_vc <= published_vc:
        new_vc = published_vc + 1
        new_vn = bump_version_name(local_vn)
        # If name already advanced past what we would produce, keep name but
        # still force versionCode ahead of published.
        write_gradle(text, new_vc, new_vn)
        print(
            f"AUTO-BUMP: versionCode {local_vc} -> {new_vc}, "
            f"versionName {local_vn} -> {new_vn} "
            f"(published was {published_vc})",
            file=sys.stderr,
        )
        local_vc, local_vn = new_vc, new_vn

    print(f"OK: local versionCode={local_vc} versionName={local_vn} published={published_vc}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
