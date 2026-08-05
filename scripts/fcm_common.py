#!/usr/bin/env python3
"""FCM helpers for R2Finance (topic + optional device tokens). CI only."""
from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request

try:
    import boto3
    from google.oauth2 import service_account
    from google.auth.transport.requests import Request
except ImportError as e:  # pragma: no cover
    boto3 = None  # type: ignore
    service_account = None  # type: ignore
    Request = None  # type: ignore
    _IMPORT_ERROR: Exception | None = e
else:
    _IMPORT_ERROR = None


def require_deps() -> None:
    if _IMPORT_ERROR is not None:
        raise RuntimeError(f"missing FCM deps: {_IMPORT_ERROR}")


def ssm_get(name: str, decrypt: bool = True) -> str:
    require_deps()
    client = boto3.client("ssm", region_name="us-east-1")
    r = client.get_parameter(Name=name, WithDecryption=decrypt)
    return r["Parameter"]["Value"]


def fcm_access_token(sa_json: str) -> str:
    require_deps()
    info = json.loads(sa_json)
    creds = service_account.Credentials.from_service_account_info(
        info, scopes=["https://www.googleapis.com/auth/firebase.messaging"]
    )
    creds.refresh(Request())
    if not creds.token:
        raise RuntimeError("empty FCM access token")
    return creds.token


def load_fcm_credentials() -> tuple[str, str]:
    project_id = ssm_get("/fcm/project-id", decrypt=False)
    sa_json = ssm_get("/fcm/service-account-json", decrypt=True)
    return project_id, sa_json


def send_to_topic(
    project_id: str,
    access_token: str,
    topic: str,
    data: dict[str, str],
) -> None:
    url = f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
    payload = {
        "message": {
            "topic": topic,
            "data": {k: str(v) for k, v in data.items()},
            "android": {"priority": "HIGH"},
        }
    }
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {access_token}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        resp.read()


def broadcast_topic(data: dict[str, str], topic: str = "r2finance_updates", label: str = "FCM") -> None:
    project_id, sa_json = load_fcm_credentials()
    access = fcm_access_token(sa_json)
    send_to_topic(project_id, access, topic, data)
    print(f"{label} sent to topic={topic}", flush=True)
