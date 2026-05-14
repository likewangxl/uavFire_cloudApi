from typing import Optional

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
