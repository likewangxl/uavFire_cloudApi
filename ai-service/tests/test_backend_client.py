from typing import Optional

import pytest

from app.clients.backend_client import BackendClient


def test_backend_client_posts_dual_stream_event():
    transport = RecordingTransport()
    client = BackendClient(base_url="http://backend", transport=transport)

    client.report_event(
        task_id="task-001",
        payload={
            "drone_sn": "DRONE-001",
            "fusion_score": 0.712,
            "risk_level": "HIGH",
        },
    )

    assert transport.last_path == "/manage/api/v1/dual-stream/tasks/task-001/events"
    assert transport.last_json["risk_level"] == "HIGH"


def test_backend_client_posts_fire_event_to_fire_events_endpoint():
    transport = RecordingTransport()
    client = BackendClient(base_url="http://backend", transport=transport)

    client.report_fire_event(
        payload={
            "eventId": "zlm-demo-1779163200000",
            "source": "M4T",
            "deviceSn": "DRONE-1",
            "confidence": 0.78,
            "fireLevel": "MEDIUM",
            "timestamp": "2026-05-19T04:00:00.000Z",
        }
    )

    assert transport.last_path == "/api/fire/events"
    assert transport.last_json["fireLevel"] == "MEDIUM"
    assert transport.last_json["confidence"] == 0.78


def test_backend_client_fire_event_carries_access_token_header():
    transport = RecordingTransport()
    client = BackendClient(
        base_url="http://backend",
        access_token="token-abc",
        transport=transport,
    )

    client.report_fire_event(payload={"eventId": "x", "fireLevel": "MEDIUM"})

    assert transport.last_headers == {"x-auth-token": "token-abc"}


def test_backend_client_adds_configured_token_header_when_reporting_event():
    transport = RecordingTransport()
    client = BackendClient(
        base_url="http://backend",
        access_token="token-123",
        transport=transport,
    )

    client.report_event(
        task_id="task-token",
        payload={
            "drone_sn": "DRONE-TOKEN",
            "fusion_score": 0.8,
            "risk_level": "HIGH",
        },
    )

    assert transport.last_headers == {"x-auth-token": "token-123"}


def test_backend_client_logs_in_once_when_credentials_are_configured():
    transport = RecordingTransport(
        responses={
            "/manage/api/v1/login": {
                "code": 0,
                "data": {
                    "access_token": "login-token",
                },
            }
        }
    )
    client = BackendClient(
        base_url="http://backend",
        username="adminPC",
        password="adminPC",
        login_flag=1,
        transport=transport,
    )

    client.report_event(
        task_id="task-login",
        payload={
            "drone_sn": "DRONE-LOGIN",
            "fusion_score": 0.9,
            "risk_level": "HIGH",
        },
    )
    client.report_event(
        task_id="task-login",
        payload={
            "drone_sn": "DRONE-LOGIN",
            "fusion_score": 0.7,
            "risk_level": "HIGH",
        },
    )

    assert transport.paths.count("/manage/api/v1/login") == 1
    assert transport.posts[0]["json"] == {
        "username": "adminPC",
        "password": "adminPC",
        "flag": 1,
    }
    assert transport.posts[1]["headers"] == {"x-auth-token": "login-token"}
    assert transport.posts[2]["headers"] == {"x-auth-token": "login-token"}


def test_backend_client_falls_back_to_demo_login_when_captcha_login_fails():
    transport = RecordingTransport(
        responses={
            "/manage/api/v1/login": {
                "code": 401,
                "message": "验证码不能为空",
            },
            "/manage/api/v1/demo-login": {
                "code": 0,
                "data": {
                    "access_token": "demo-token",
                },
            },
        }
    )
    client = BackendClient(
        base_url="http://backend",
        username="adminPC",
        password="adminPC",
        login_flag=1,
        transport=transport,
    )

    client.report_fire_event(payload={"eventId": "x", "fireLevel": "LOW"})

    assert transport.paths[:2] == ["/manage/api/v1/login", "/manage/api/v1/demo-login"]
    assert transport.posts[2]["headers"] == {"x-auth-token": "demo-token"}


def test_backend_client_raises_when_fire_event_business_response_fails():
    transport = RecordingTransport(
        responses={
            "/api/fire/events": {
                "code": -1,
                "message": "OSD has no latitude/longitude for device: DRONE-1",
            }
        }
    )
    client = BackendClient(base_url="http://backend", transport=transport)

    with pytest.raises(RuntimeError, match="backend-fire-event-rejected"):
        client.report_fire_event(payload={"eventId": "x", "fireLevel": "HIGH"})


class RecordingTransport:
    def __init__(self, responses: Optional[dict] = None) -> None:
        self.last_path: Optional[str] = None
        self.last_json: Optional[dict] = None
        self.last_headers: Optional[dict] = None
        self.paths = []
        self.posts = []
        self._responses = responses or {}

    def post(self, path: str, json: dict, headers: Optional[dict] = None):
        self.last_path = path
        self.last_json = json
        self.last_headers = headers
        self.paths.append(path)
        self.posts.append({"path": path, "json": json, "headers": headers})
        return self._responses.get(path)
