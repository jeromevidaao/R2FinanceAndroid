#!/usr/bin/env python3
"""Build r2finance-builds/version.json (+ history.json) for in-app OTA.

Same contract as R2Android / Cleaning Button OTA.
"""
from __future__ import annotations

import json
import os
import pathlib
import re
import sys
from datetime import datetime, timezone


def load_prev_history() -> list[dict]:
    path = (os.environ.get("PREV_HISTORY_JSON") or "").strip()
    if not path or not pathlib.Path(path).is_file():
        return []
    try:
        data = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
        entries = data.get("entries") if isinstance(data, dict) else data
        if not isinstance(entries, list):
            return []
        return [e for e in entries if isinstance(e, dict)]
    except Exception:
        return []


def main() -> int:
    gradle = pathlib.Path("app/build.gradle.kts")
    if not gradle.is_file():
        print("app/build.gradle.kts not found", file=sys.stderr)
        return 1
    text = gradle.read_text(encoding="utf-8")
    vc_m = re.search(r"versionCode\s*=\s*(\d+)", text)
    vn_m = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not vc_m or not vn_m:
        print("Could not parse versionCode/versionName", file=sys.stderr)
        return 1
    version_code = int(vc_m.group(1))
    version_name = vn_m.group(1)
    latest_url = os.environ.get("LATEST_URL", "").strip()
    if not latest_url:
        print("LATEST_URL required", file=sys.stderr)
        return 1
    notes = (os.environ.get("RELEASE_NOTES") or "R2Finance update").strip()
    sha = (os.environ.get("GITHUB_SHA") or "")[:7]
    ref = os.environ.get("GITHUB_REF_NAME") or ""
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    version = {
        "versionCode": version_code,
        "versionName": version_name,
        "apkUrl": latest_url,
        "releaseNotes": notes,
        "minVersionCode": 1,
        "publishedAt": now,
        "sha": sha,
        "ref": ref,
    }
    out = pathlib.Path(os.environ.get("VERSION_JSON_OUT") or "/tmp/version.json")
    out.write_text(json.dumps(version, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {out}: versionCode={version_code} versionName={version_name}")

    entry = {
        "versionCode": version_code,
        "versionName": version_name,
        "summary": notes.splitlines()[0][:240] if notes else f"Build {sha}",
        "detail": notes,
        "publishedAt": now,
        "sha": sha,
        "ref": ref,
        "apkUrl": latest_url,
    }
    entries = [entry] + [
        e for e in load_prev_history() if int(e.get("versionCode") or 0) != version_code
    ]
    entries.sort(key=lambda e: int(e.get("versionCode") or 0), reverse=True)
    history = {"version": 1, "updatedAt": now, "entries": entries[:120]}
    hist_out = pathlib.Path(os.environ.get("HISTORY_JSON_OUT") or "/tmp/history.json")
    hist_out.write_text(json.dumps(history, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {hist_out}: {len(entries)} entries")
    return 0


if __name__ == "__main__":
    sys.exit(main())
