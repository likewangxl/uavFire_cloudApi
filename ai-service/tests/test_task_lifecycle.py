from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


def test_task_start_stop_and_query():
    created = client.post(
        "/api/v1/dual-stream/tasks",
        json={
            "task_id": "task-002",
            "drone_sn": "DRONE-002",
            "visible_stream_url": "rtsp://visible",
            "thermal_stream_url": "rtsp://thermal",
        },
    )

    assert created.status_code == 201

    started = client.post("/api/v1/dual-stream/tasks/task-002/start")
    assert started.status_code == 200
    assert started.json()["status"] == "running"

    queried = client.get("/api/v1/dual-stream/tasks/task-002")
    assert queried.status_code == 200
    assert queried.json()["status"] == "running"

    stopped = client.post("/api/v1/dual-stream/tasks/task-002/stop")
    assert stopped.status_code == 200
    assert stopped.json()["status"] == "stopped"
