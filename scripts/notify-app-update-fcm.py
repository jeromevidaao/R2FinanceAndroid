#!/usr/bin/env python3
"""
Push FCM type=app_update to topic r2finance_updates after OTA publish.

Secrets from AWS SSM:
  /fcm/project-id
  /fcm/service-account-json
"""
from __future__ import annotations

import json
import os
import pathlib
import sys
import urllib.request

try:
    from fcm_common import broadcast_topic
except ImportError:
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    from fcm_common import broadcast_topic


def load_prev_version_code() -> int | None:
    prev_path = (os.environ.get("PREV_VERSION_JSON") or "").strip()
    if prev_path and pathlib.Path(prev_path).is_file():
        try:
            prev = json.loads(pathlib.Path(prev_path).read_text(encoding="utf-8"))
            return int(prev.get("versionCode") or 0) or None
        except Exception as e:
            print(f"WARN: PREV_VERSION_JSON ({e})", file=sys.stderr)
    url = (
        os.environ.get("PREV_VERSION_URL")
        or "https://www.cleaningbutton.com/r2finance-builds/version.json"
    ).strip()
    try:
        with urllib.request.urlopen(url, timeout=15) as resp:
            prev = json.loads(resp.read().decode("utf-8"))
        return int(prev.get("versionCode") or 0) or None
    except Exception as e:
        print(f"WARN: fetch prev version.json ({e})", file=sys.stderr)
        return None


def main() -> int:
    manifest_path = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/version.json")
    if not manifest_path.is_file():
        print(f"WARN: {manifest_path} missing; skip FCM", file=sys.stderr)
        return 0

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    version_code = str(manifest.get("versionCode", ""))
    version_name = str(manifest.get("versionName", ""))
    apk_url = str(manifest.get("apkUrl", ""))
    notes = str(manifest.get("releaseNotes", "") or "")[:200]
    if not version_code or not apk_url:
        print("WARN: incomplete version.json; skip FCM", file=sys.stderr)
        return 0
    try:
        new_vc = int(version_code)
    except ValueError:
        return 0

    prev_vc = load_prev_version_code()
    # When PREV_VERSION_JSON is pre-publish snapshot, new_vc must be > prev.
    # After S3 upload, public URL already equals new_vc — so use env PREV only.
    if prev_vc is not None and new_vc <= prev_vc:
        print(f"Skip FCM: {new_vc} not newer than {prev_vc}", flush=True)
        return 0

    title = f"R2Finance update: {version_name or version_code}"
    body = notes or f"R2Finance {version_name} (code {version_code}) is ready. Tap to install."
    data = {
        "type": "app_update",
        "versionCode": str(version_code),
        "versionName": str(version_name),
        "apkUrl": str(apk_url),
        "releaseNotes": str(notes),
        "minVersionCode": str(manifest.get("minVersionCode", 1)),
        "title": title,
        "body": body,
    }
    print(f"Notify app_update versionName={version_name} versionCode={version_code}", flush=True)
    try:
        broadcast_topic(data, topic="r2finance_updates", label="FCM app_update")
    except Exception as e:
        print(f"WARN: FCM app_update failed ({e}); non-fatal", file=sys.stderr)
        return 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
