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


def test_upload_msdk_thermal_snapshot_returns_snapshot_url(tmp_path, monkeypatch):
    monkeypatch.setenv("AI_SERVICE_SNAPSHOT_DIR", str(tmp_path))
    monkeypatch.setenv("AI_SERVICE_SNAPSHOT_PUBLIC_BASE_URL", "http://testserver/api/v1/snapshots")
    import cv2
    import numpy as np

    frame = np.zeros((24, 32, 3), dtype=np.uint8)
    frame[:, :] = (40, 40, 40)
    frame[8:16, 12:20] = (240, 240, 240)
    ok, encoded = cv2.imencode(".jpg", frame)
    assert ok

    response = client.post(
        "/api/v1/snapshots/msdk-thermal/event-001",
        files={"file": ("thermal.jpg", encoded.tobytes(), "image/jpeg")},
    )

    assert response.status_code == 201
    assert response.json()["url"] == "http://testserver/api/v1/snapshots/event-001-annotated.jpg"
    assert (tmp_path / "event-001-raw.jpg").exists()
    assert (tmp_path / "event-001-annotated.jpg").exists()


def test_upload_msdk_thermal_snapshot_rejects_visible_looking_image(tmp_path, monkeypatch, caplog):
    monkeypatch.setenv("AI_SERVICE_SNAPSHOT_DIR", str(tmp_path))
    caplog.set_level("WARNING", logger="app.api.routes")
    import cv2
    import numpy as np

    frame = np.zeros((24, 32, 3), dtype=np.uint8)
    frame[:, :] = (40, 130, 230)
    ok, encoded = cv2.imencode(".jpg", frame)
    assert ok

    response = client.post(
        "/api/v1/snapshots/msdk-thermal/event-visible",
        files={"file": ("visible.jpg", encoded.tobytes(), "image/jpeg")},
    )

    assert response.status_code == 422
    assert not (tmp_path / "event-visible-raw.jpg").exists()
    messages = "\n".join(record.getMessage() for record in caplog.records)
    assert "msdk thermal snapshot rejected" in messages
    assert "event_id=event-visible" in messages
    assert "reason=obvious_visible_frame" in messages
    assert "grayscale_ratio=" in messages
    assert "intensity_range=" in messages
    assert "intensity_std=" in messages


def test_upload_msdk_thermal_snapshot_rejects_obvious_visible_even_when_unverified(tmp_path, monkeypatch, caplog):
    monkeypatch.setenv("AI_SERVICE_SNAPSHOT_DIR", str(tmp_path))
    monkeypatch.setenv("AI_SERVICE_SNAPSHOT_PUBLIC_BASE_URL", "http://testserver/api/v1/snapshots")
    caplog.set_level("WARNING", logger="app.api.routes")
    import cv2
    import numpy as np

    frame = np.zeros((24, 32, 3), dtype=np.uint8)
    frame[:, :] = (40, 130, 230)
    ok, encoded = cv2.imencode(".jpg", frame)
    assert ok

    response = client.post(
        "/api/v1/snapshots/msdk-thermal/event-visible-fallback",
        data={"allow_unverified": "true"},
        files={"file": ("visible.jpg", encoded.tobytes(), "image/jpeg")},
    )

    assert response.status_code == 422
    assert not (tmp_path / "event-visible-fallback-raw.jpg").exists()
    messages = "\n".join(record.getMessage() for record in caplog.records)
    assert "msdk thermal snapshot rejected" in messages
    assert "reason=obvious_visible_frame" in messages
    assert "event_id=event-visible-fallback" in messages


def test_upload_msdk_thermal_snapshot_allows_unverified_monochrome_fallback(tmp_path, monkeypatch, caplog):
    monkeypatch.setenv("AI_SERVICE_SNAPSHOT_DIR", str(tmp_path))
    monkeypatch.setenv("AI_SERVICE_SNAPSHOT_PUBLIC_BASE_URL", "http://testserver/api/v1/snapshots")
    caplog.set_level("WARNING", logger="app.api.routes")
    import cv2
    import numpy as np

    frame = np.zeros((24, 32, 3), dtype=np.uint8)
    frame[:, :] = (80, 80, 80)
    frame[8:16, 12:20] = (95, 95, 95)
    ok, encoded = cv2.imencode(".jpg", frame)
    assert ok

    response = client.post(
        "/api/v1/snapshots/msdk-thermal/event-monochrome-fallback",
        data={"allow_unverified": "true"},
        files={"file": ("thermal-low-contrast.jpg", encoded.tobytes(), "image/jpeg")},
    )

    assert response.status_code == 201
    assert response.json()["url"] == "http://testserver/api/v1/snapshots/event-monochrome-fallback-annotated.jpg"
    assert response.json()["thermal_verified"] is False
    assert response.json()["thermal_reject_reason"] == "not_thermal_frame"
    assert (tmp_path / "event-monochrome-fallback-raw.jpg").exists()
    messages = "\n".join(record.getMessage() for record in caplog.records)
    assert "msdk thermal snapshot accepted as unverified fallback" in messages
    assert "event_id=event-monochrome-fallback" in messages


def test_upload_msdk_visible_snapshot_records_visible_detection(tmp_path, monkeypatch, caplog):
    monkeypatch.setenv("AI_SERVICE_SNAPSHOT_DIR", str(tmp_path))
    monkeypatch.setenv("AI_SERVICE_SNAPSHOT_PUBLIC_BASE_URL", "http://testserver/api/v1/snapshots")
    monkeypatch.setenv("AI_SERVICE_VISIBLE_YOLO_MODEL_PATH", "")
    monkeypatch.setenv("AI_SERVICE_VISIBLE_DETECTOR_MODE", "color")
    caplog.set_level("INFO", logger="app.api.routes")
    import cv2
    import numpy as np

    client.post(
        "/api/v1/dual-stream/tasks",
        json={
            "task_id": "task-msdk-visible",
            "drone_sn": "DRONE-VISIBLE",
            "visible_stream_url": "msdk-upload://visible",
            "thermal_stream_url": "msdk-upload://thermal",
        },
    )
    frame = np.zeros((48, 64, 3), dtype=np.uint8)
    frame[:, :] = (20, 30, 40)
    frame[12:36, 16:48] = (30, 150, 240)
    ok, encoded = cv2.imencode(".jpg", frame)
    assert ok

    response = client.post(
        "/api/v1/snapshots/msdk-visible/event-visible-001",
        data={
            "task_id": "task-msdk-visible",
            "drone_sn": "DRONE-VISIBLE",
            "source_ts": "1780071500156",
        },
        files={"file": ("visible.jpg", encoded.tobytes(), "image/jpeg")},
    )

    assert response.status_code == 201
    payload = response.json()
    assert payload["visible_score"] > 0.0
    assert payload["visible_image_url"] == "http://testserver/api/v1/snapshots/event-visible-001-visible-annotated.jpg"
    list_response = client.get("/api/v1/dual-stream/tasks/task-msdk-visible/events")
    event = list_response.json()[-1]
    assert event["analysis_channel"] == "visible"
    assert event["visible_image_url"] == "http://testserver/api/v1/snapshots/event-visible-001-visible-annotated.jpg"
    messages = "\n".join(record.getMessage() for record in caplog.records)
    assert "msdk visible snapshot timing event_id=event-visible-001" in messages
    assert "decode_ms=" in messages
    assert "detect_ms=" in messages
    assert "write_ms=" in messages
    assert "report_ms=" in messages
    assert "total_ms=" in messages


def test_msdk_visible_snapshot_does_not_overwrite_thermal_snapshot(tmp_path, monkeypatch):
    monkeypatch.setenv("AI_SERVICE_SNAPSHOT_DIR", str(tmp_path))
    monkeypatch.setenv("AI_SERVICE_SNAPSHOT_PUBLIC_BASE_URL", "http://testserver/api/v1/snapshots")
    monkeypatch.setenv("AI_SERVICE_VISIBLE_YOLO_MODEL_PATH", "")
    monkeypatch.setenv("AI_SERVICE_VISIBLE_DETECTOR_MODE", "color")
    import cv2
    import numpy as np

    client.post(
        "/api/v1/dual-stream/tasks",
        json={
            "task_id": "task-no-overwrite",
            "drone_sn": "DRONE-VISIBLE",
            "visible_stream_url": "msdk-upload://visible",
            "thermal_stream_url": "msdk-upload://thermal",
        },
    )
    thermal_frame = np.zeros((48, 64, 3), dtype=np.uint8)
    thermal_frame[:, :] = (30, 30, 30)
    thermal_frame[8:32, 16:44] = (240, 240, 240)
    ok, thermal_encoded = cv2.imencode(".jpg", thermal_frame)
    assert ok

    visible_frame = np.zeros((48, 64, 3), dtype=np.uint8)
    visible_frame[:, :] = (20, 30, 40)
    visible_frame[12:36, 16:48] = (30, 150, 240)
    ok, visible_encoded = cv2.imencode(".jpg", visible_frame)
    assert ok

    thermal_response = client.post(
        "/api/v1/snapshots/msdk-thermal/event-same-id",
        files={"file": ("thermal.jpg", thermal_encoded.tobytes(), "image/jpeg")},
    )
    assert thermal_response.status_code == 201
    thermal_bytes_before = (tmp_path / "event-same-id-raw.jpg").read_bytes()

    visible_response = client.post(
        "/api/v1/snapshots/msdk-visible/event-same-id",
        data={
            "task_id": "task-no-overwrite",
            "drone_sn": "DRONE-VISIBLE",
            "source_ts": "1780071500156",
        },
        files={"file": ("visible.jpg", visible_encoded.tobytes(), "image/jpeg")},
    )

    assert visible_response.status_code == 201
    assert visible_response.json()["visible_image_url"] == "http://testserver/api/v1/snapshots/event-same-id-visible-annotated.jpg"
    assert (tmp_path / "event-same-id-raw.jpg").read_bytes() == thermal_bytes_before
    assert (tmp_path / "event-same-id-visible-raw.jpg").exists()


def test_msdk_visible_snapshot_reports_backend_when_local_task_is_missing(tmp_path, monkeypatch):
    monkeypatch.setenv("AI_SERVICE_SNAPSHOT_DIR", str(tmp_path))
    monkeypatch.setenv("AI_SERVICE_SNAPSHOT_PUBLIC_BASE_URL", "http://testserver/api/v1/snapshots")
    monkeypatch.setenv("AI_SERVICE_VISIBLE_YOLO_MODEL_PATH", "")
    monkeypatch.setenv("AI_SERVICE_VISIBLE_DETECTOR_MODE", "color")
    import cv2
    import numpy as np

    reported = []

    class _Backend:
        def report_event(self, task_id, payload):
            reported.append((task_id, payload))

    monkeypatch.setattr("app.api.routes._build_backend_client", lambda settings: _Backend(), raising=False)

    frame = np.zeros((48, 64, 3), dtype=np.uint8)
    frame[:, :] = (20, 30, 40)
    frame[12:36, 16:48] = (30, 150, 240)
    ok, encoded = cv2.imencode(".jpg", frame)
    assert ok

    response = client.post(
        "/api/v1/snapshots/msdk-visible/event-without-local-task",
        data={
            "task_id": "task-only-in-backend",
            "drone_sn": "DRONE-VISIBLE",
            "source_ts": "1780071500156",
            "thermal_source_event_id": "thermal-source-001",
            "thermal_image_url": "http://ai/snapshots/thermal-source-001-annotated.jpg",
        },
        files={"file": ("visible.jpg", encoded.tobytes(), "image/jpeg")},
    )

    assert response.status_code == 201
    assert reported
    task_id, payload = reported[-1]
    assert task_id == "task-only-in-backend"
    assert payload["analysis_channel"] == "visible"
    assert payload["analysisChannel"] == "visible"
    assert payload["visible_image_url"] == "http://testserver/api/v1/snapshots/event-without-local-task-visible-annotated.jpg"
    assert payload["visibleImageUrl"] == "http://testserver/api/v1/snapshots/event-without-local-task-visible-annotated.jpg"
    assert payload["thermal_source_event_id"] == "thermal-source-001"
    assert payload["thermalSourceEventId"] == "thermal-source-001"
    assert payload["thermal_image_url"] == "http://ai/snapshots/thermal-source-001-annotated.jpg"
    assert payload["thermalImageUrl"] == "http://ai/snapshots/thermal-source-001-annotated.jpg"
