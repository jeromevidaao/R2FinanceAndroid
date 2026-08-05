#!/usr/bin/env python3
"""Fail if versionCode is not greater than published OTA manifest."""
from __future__ import annotations

import json
import os
import pathlib
import re
import sys
import urllib.request


def main() -> int:
    gradle = pathlib.Path("app/build.gradle.kts")
    text = gradle.read_text(encoding="utf-8")
    vc_m = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not vc_m:
        print("Could not parse versionCode", file=sys.stderr)
        return 1
    local_vc = int(vc_m.group(1))

    published_vc = 0
    prev = (os.environ.get("PREV_VERSION_JSON") or "").strip()
    if prev and pathlib.Path(prev).is_file():
        try:
            published_vc = int(json.loads(pathlib.Path(prev).read_text()).get("versionCode") or 0)
        except Exception:
            published_vc = 0
    else:
        url = (
            os.environ.get("UPDATE_MANIFEST_URL")
            or "https://www.cleaningbutton.com/r2finance-builds/version.json"
        )
        try:
            with urllib.request.urlopen(url, timeout=15) as resp:
                published_vc = int(json.loads(resp.read().decode()).get("versionCode") or 0)
        except Exception as e:
            print(f"WARN: could not fetch published version.json ({e})", file=sys.stderr)

    if published_vc > 0 and local_vc <= published_vc:
        print(
            f"ERROR: versionCode {local_vc} must be > published {published_vc}",
            file=sys.stderr,
        )
        return 1
    print(f"OK: local versionCode={local_vc} published={published_vc}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
