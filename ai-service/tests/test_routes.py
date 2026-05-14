from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


def test_healthz():
    response = client.get("/healthz")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_create_task():
    response = client.post(
        "/api/v1/dual-stream/tasks",
        json={
            "task_id": "task-001",
            "drone_sn": "DRONE-001",
            "visible_stream_url": "rtsp://visible",
            "thermal_stream_url": "rtsp://thermal",
        },
    )

    assert response.status_code == 201
    assert response.json()["task_id"] == "task-001"


def test_record_detection_event_and_query_events():
    create_response = client.post(
        "/api/v1/dual-stream/tasks",
        json={
            "task_id": "task-003",
            "drone_sn": "DRONE-003",
            "visible_stream_url": "rtsp://visible",
            "thermal_stream_url": "rtsp://thermal",
        },
    )
    assert create_response.status_code == 201

    event_response = client.post(
        "/api/v1/dual-stream/tasks/task-003/events",
        json={
            "source_ts": 1710000000,
            "visible_score": 0.82,
            "thermal_score": 0.74,
            "fusion_score": 0.788,
            "risk_level": "HIGH",
        },
    )
    assert event_response.status_code == 201
    assert event_response.json()["event_type"] == "detection"

    list_response = client.get("/api/v1/dual-stream/tasks/task-003/events")
    assert list_response.status_code == 200
    assert list_response.json()[-1]["risk_level"] == "HIGH"


def test_start_task_generates_detection_event():
    create_response = client.post(
        "/api/v1/dual-stream/tasks",
        json={
            "task_id": "task-004",
            "drone_sn": "DRONE-004",
            "visible_stream_url": "rtsp://visible",
            "thermal_stream_url": "rtsp://thermal",
        },
    )
    assert create_response.status_code == 201

    start_response = client.post("/api/v1/dual-stream/tasks/task-004/start")
    assert start_response.status_code == 200
    assert start_response.json()["status"] == "running"

    list_response = client.get("/api/v1/dual-stream/tasks/task-004/events")
    assert list_response.status_code == 200
    assert list_response.json()[-1]["event_type"] == "detection"
