from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, Optional, Protocol
from urllib import request
import json


class SupportsPost(Protocol):
    def post(
        self,
        path: str,
        json: Dict[str, Any],
        headers: Optional[Dict[str, str]] = None,
    ) -> Any:
        ...


class UrllibTransport:
    def __init__(self, base_url: str) -> None:
        self._base_url = base_url.rstrip("/")

    def post(
        self,
        path: str,
        json: Dict[str, Any],
        headers: Optional[Dict[str, str]] = None,
    ) -> Any:
        payload = json_encode(json)
        request_headers = {"Content-Type": "application/json"}
        if headers:
            request_headers.update(headers)
        req = request.Request(
            url=f"{self._base_url}{path}",
            data=payload,
            headers=request_headers,
            method="POST",
        )
        with request.urlopen(req) as response:
            response_payload = response.read()
        if not response_payload:
            return None
        return json_decode(response_payload)


@dataclass
class BackendClient:
    base_url: str
    access_token: str = ""
    username: str = ""
    password: str = ""
    login_flag: int = 1
    transport: Optional[SupportsPost] = None

    def __post_init__(self) -> None:
        if self.transport is None:
            self.transport = UrllibTransport(self.base_url)

    def report_event(self, task_id: str, payload: Dict[str, Any]) -> None:
        assert self.transport is not None
        headers = self._auth_headers()
        self.transport.post(
            f"/manage/api/v1/dual-stream/tasks/{task_id}/events",
            json=payload,
            headers=headers or None,
        )

    def _auth_headers(self) -> Dict[str, str]:
        token = self._ensure_access_token()
        return {"x-auth-token": token} if token else {}

    def _ensure_access_token(self) -> str:
        if self.access_token:
            return self.access_token
        if not self.username or not self.password:
            return ""
        assert self.transport is not None
        response = self.transport.post(
            "/manage/api/v1/login",
            json={
                "username": self.username,
                "password": self.password,
                "flag": self.login_flag,
            },
        )
        token = _extract_access_token(response)
        if not token:
            raise RuntimeError("backend-login-missing-access-token")
        self.access_token = token
        return token


def json_encode(payload: Dict[str, Any]) -> bytes:
    return json.dumps(payload).encode("utf-8")


def json_decode(payload: bytes) -> Any:
    return json.loads(payload.decode("utf-8"))


def _extract_access_token(response: Any) -> str:
    if not isinstance(response, dict):
        return ""
    data = response.get("data")
    if not isinstance(data, dict):
        return ""
    token = data.get("access_token")
    return token if isinstance(token, str) else ""
