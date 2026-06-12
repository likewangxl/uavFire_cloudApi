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
    # 必须有超时：runner 每帧都会 POST 事件给后端，后端慢/重启时若无超时会无限阻塞，
    # 卡死 runner tick → stop 的 join 超时 → 线程变孤儿继续跑（表现为停止后画面仍红外/可见光来回切）。
    def __init__(self, base_url: str, timeout_s: float = 5.0) -> None:
        self._base_url = base_url.rstrip("/")
        # 非正超时（urlopen 对 0 立即超时、对负值行为未定义）→ 回退默认 5s，避免误配置卡死/丢请求。
        timeout = float(timeout_s)
        self._timeout_s = timeout if timeout > 0 else 5.0

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
        with request.urlopen(req, timeout=self._timeout_s) as response:
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

    def report_fire_event(self, payload: Dict[str, Any]) -> None:
        assert self.transport is not None
        headers = self._auth_headers()
        response = self.transport.post(
            "/api/fire/events",
            json=payload,
            headers=headers or None,
        )
        _raise_for_business_error(response, "backend-fire-event-rejected")

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
            response = self.transport.post(
                "/manage/api/v1/demo-login",
                json={},
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


def _raise_for_business_error(response: Any, prefix: str) -> None:
    if not isinstance(response, dict):
        return
    code = response.get("code")
    if code in (None, 0):
        return
    message = response.get("message") or response.get("msg") or "unknown"
    raise RuntimeError(f"{prefix}: code={code} message={message}")
