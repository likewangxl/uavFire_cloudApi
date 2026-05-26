from typing import Any, Dict, List

from app.models.event import DualStreamEvent
from app.models.task import TaskRecord
from app.services.fire_event_reporter import (
    FireEventReporter,
    build_fire_event_payload,
)
import numpy as np


def test_visible_low_above_noise_floor_does_not_post_before_thermal_confirmation():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    posted = reporter.maybe_report(task, _event(risk="LOW", visible=0.33))

    assert posted is False
    assert backend.posts == []


def test_thermal_low_above_noise_floor_posts_for_operator_prompt():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    posted = reporter.maybe_report(
        task,
        _event(risk="LOW", visible=0.0, thermal=0.17, channel="thermal"),
    )

    assert posted is True
    assert len(backend.posts) == 1
    assert backend.posts[0]["fireLevel"] == "LOW"
    assert backend.posts[0]["confidence"] == 0.17


def test_thermal_reference_frame_posts_at_low_confidence_floor():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    posted = reporter.maybe_report(
        task,
        _event(risk="LOW", visible=0.0, thermal=0.011, channel="thermal"),
    )

    assert posted is True
    assert len(backend.posts) == 1
    assert backend.posts[0]["confidence"] == 0.011


def test_thermal_report_attaches_grayscale_thermal_snapshot():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend, snapshot_writer=FakeSnapshotWriter("http://snapshots/thermal-gray.jpg"))
    task = _task()
    frame = np.zeros((32, 32, 3), dtype=np.uint8)
    frame[:, :, :] = 65
    frame[8:22, 8:22, :] = 180

    posted = reporter.maybe_report(
        task,
        _event(risk="LOW", visible=0.0, thermal=0.17, channel="thermal"),
        thermal_frame=frame,
    )

    assert posted is True
    assert backend.posts[0]["thermalImageUrl"] == "http://snapshots/thermal-gray.jpg"
    assert "visibleImageUrl" not in backend.posts[0]


def test_thermal_report_passes_temperature_to_snapshot_and_backend():
    backend = RecordingBackend()
    snapshot_writer = FakeSnapshotWriter("http://snapshots/thermal-gray.jpg")
    reporter = FireEventReporter(backend, snapshot_writer=snapshot_writer)
    task = _task()
    frame = np.zeros((32, 32, 3), dtype=np.uint8)
    frame[:, :, :] = 65
    frame[8:22, 8:22, :] = 180

    posted = reporter.maybe_report(
        task,
        _event(risk="LOW", visible=0.0, thermal=0.17, channel="thermal", thermal_temperature=57.64),
        thermal_frame=frame,
    )

    assert posted is True
    assert snapshot_writer.last_thermal_temperature == 57.64
    assert backend.posts[0]["thermal_temperature"] == 57.64
    assert backend.posts[0]["thermalTemperature"] == 57.64


def test_thermal_report_passes_measure_roi_to_snapshot_and_backend():
    backend = RecordingBackend()
    snapshot_writer = FakeSnapshotWriter("http://snapshots/thermal-gray.jpg")
    reporter = FireEventReporter(backend, snapshot_writer=snapshot_writer)
    task = _task()
    frame = np.zeros((32, 32, 3), dtype=np.uint8)
    frame[:, :, :] = 65
    frame[8:22, 8:22, :] = 180

    posted = reporter.maybe_report(
        task,
        _event(
            risk="LOW",
            visible=0.0,
            thermal=0.17,
            channel="thermal",
            thermal_temperature=57.64,
            thermal_measure_roi={"x": 0.25, "y": 0.25, "width": 0.5, "height": 0.5},
        ),
        thermal_frame=frame,
    )

    expected = {"x": 0.25, "y": 0.25, "width": 0.5, "height": 0.5}
    assert posted is True
    assert snapshot_writer.last_thermal_measure_roi == expected
    assert backend.posts[0]["thermal_measure_roi"] == expected
    assert backend.posts[0]["thermalMeasureRoi"] == expected


def test_thermal_report_does_not_attach_visible_like_snapshot():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend, snapshot_writer=FakeSnapshotWriter("http://snapshots/not-thermal.jpg"))
    task = _task()
    visible_like_frame = np.zeros((32, 32, 3), dtype=np.uint8)
    visible_like_frame[:, :, 0] = 115
    visible_like_frame[:, :, 1] = 105
    visible_like_frame[:, :, 2] = 95

    posted = reporter.maybe_report(
        task,
        _event(risk="LOW", visible=0.0, thermal=0.17, channel="thermal"),
        thermal_frame=visible_like_frame,
    )

    assert posted is True
    assert "thermalImageUrl" not in backend.posts[0]
    assert "visibleImageUrl" not in backend.posts[0]


def test_low_below_noise_floor_does_not_post():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    posted = reporter.maybe_report(task, _event(risk="LOW", visible=0.02))

    assert posted is False
    assert backend.posts == []


def test_low_to_medium_does_not_repeat_inside_debounce_window():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    reporter.maybe_report(task, _event(risk="LOW", thermal=0.17, channel="thermal"))
    posted = reporter.maybe_report(task, _event(risk="MEDIUM", thermal=0.45, channel="thermal"))

    assert posted is False
    assert len(backend.posts) == 1
    assert backend.posts[0]["fireLevel"] == "LOW"


def test_same_level_does_not_repeat_post():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    reporter.maybe_report(task, _event(risk="MEDIUM", thermal=0.45, channel="thermal"))
    reporter.maybe_report(task, _event(risk="MEDIUM", thermal=0.45, channel="thermal"))
    reporter.maybe_report(task, _event(risk="MEDIUM", thermal=0.45, channel="thermal"))

    assert len(backend.posts) == 1


def test_medium_to_high_does_not_repeat_inside_debounce_window():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    reporter.maybe_report(task, _event(risk="MEDIUM", thermal=0.45, channel="thermal"))
    reporter.maybe_report(task, _event(risk="HIGH", thermal=0.75, channel="thermal"))

    assert [p["fireLevel"] for p in backend.posts] == ["MEDIUM"]


def test_same_task_posts_at_most_once_per_minute(monkeypatch):
    now = 1000.0
    monkeypatch.setattr("app.services.fire_event_reporter.time.monotonic", lambda: now)
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    assert reporter.maybe_report(task, _event(risk="HIGH", thermal=0.75, ts=1000, channel="thermal")) is True

    now = 1030.0
    assert reporter.maybe_report(task, _event(risk="HIGH", thermal=0.75, ts=2000, channel="thermal")) is False

    now = 1060.0
    assert reporter.maybe_report(task, _event(risk="HIGH", thermal=0.75, ts=3000, channel="thermal")) is True
    assert [p["eventId"] for p in backend.posts] == [
        "zlm-demo-1000",
        "zlm-demo-3000",
    ]


def test_high_to_medium_does_not_post():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    reporter.maybe_report(task, _event(risk="HIGH", thermal=0.75, channel="thermal"))
    reporter.maybe_report(task, _event(risk="MEDIUM", thermal=0.45, channel="thermal"))

    assert len(backend.posts) == 1
    assert backend.posts[0]["fireLevel"] == "HIGH"


def test_falling_below_noise_floor_then_rising_posts_again():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    reporter.maybe_report(task, _event(risk="MEDIUM", thermal=0.45, channel="thermal"))
    reporter.maybe_report(task, _event(risk="LOW", visible=0.0, thermal=0.005, channel="thermal"))
    reporter.maybe_report(task, _event(risk="MEDIUM", thermal=0.45, channel="thermal"))

    assert [p["fireLevel"] for p in backend.posts] == ["MEDIUM", "MEDIUM"]


def test_per_task_state_is_isolated():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task_a = _task(task_id="task-a")
    task_b = _task(task_id="task-b")

    reporter.maybe_report(task_a, _event(risk="MEDIUM", thermal=0.45, channel="thermal"))
    reporter.maybe_report(task_b, _event(risk="MEDIUM", thermal=0.45, channel="thermal"))

    assert [p["eventId"].startswith("task-a") for p in backend.posts] == [True, False]
    assert [p["eventId"].startswith("task-b") for p in backend.posts] == [False, True]


def test_post_failure_logged_and_state_unchanged():
    backend = RaisingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    posted = reporter.maybe_report(task, _event(risk="MEDIUM", thermal=0.45, channel="thermal"))

    assert posted is False
    # state never advanced, next attempt should retry
    backend2 = RecordingBackend()
    reporter_swap = FireEventReporter(backend2)
    reporter_swap.maybe_report(task, _event(risk="MEDIUM", thermal=0.45, channel="thermal"))
    assert len(backend2.posts) == 1


def test_build_fire_event_payload_shape():
    task = _task(task_id="zlm-demo", drone_sn="DRONE-1")
    event = DualStreamEvent(
        source_ts=1779163200000,
        visible_score=0.78,
        thermal_score=0.41,
        fusion_score=0.45,
        risk_level="MEDIUM",
        analysis_channel="visible",
    )

    payload = build_fire_event_payload(task, event)

    assert payload["eventId"] == "zlm-demo-1779163200000"
    assert payload["source"] == "M4T"
    assert payload["deviceSn"] == "DRONE-1"
    assert payload["confidence"] == 0.78  # max(visible, thermal)
    assert payload["fireLevel"] == "MEDIUM"
    assert payload["timestamp"] == "2026-05-19T04:00:00.000Z"


def test_confidence_uses_thermal_when_higher():
    task = _task()
    event = _event(risk="MEDIUM", visible=0.3, thermal=0.62)
    payload = build_fire_event_payload(task, event)
    assert payload["confidence"] == 0.62


def _task(task_id: str = "zlm-demo", drone_sn: str = "DRONE-1") -> TaskRecord:
    return TaskRecord(
        task_id=task_id,
        drone_sn=drone_sn,
        visible_stream_url="rtsp://visible",
        thermal_stream_url="",
    )


def _event(
    risk: str,
    visible: float = 0.78,
    thermal: float = 0.0,
    ts: int = 1779163200000,
    channel: str | None = None,
    thermal_temperature: float | None = None,
    thermal_measure_roi: dict | None = None,
) -> DualStreamEvent:
    fusion = round(visible * 0.6 + thermal * 0.4, 3)
    return DualStreamEvent(
        source_ts=ts,
        visible_score=visible,
        thermal_score=thermal,
        fusion_score=fusion,
        risk_level=risk,
        analysis_channel=channel or ("visible" if thermal == 0 else "dual"),
        thermal_temperature=thermal_temperature,
        thermal_measure_roi=thermal_measure_roi,
    )


class RecordingBackend:
    def __init__(self) -> None:
        self.posts: List[Dict[str, Any]] = []

    def report_fire_event(self, payload: Dict[str, Any]) -> None:
        self.posts.append(payload)


class RaisingBackend:
    def report_fire_event(self, payload: Dict[str, Any]) -> None:
        raise RuntimeError("backend down")


class FakeSnapshotWriter:
    def __init__(self, url: str) -> None:
        self.url = url
        self.last_thermal_temperature: float | None = None
        self.last_thermal_measure_roi: dict | None = None

    def write_pair(
        self,
        event_id: str,
        frame: object,
        boxes: object,
        thermal_temperature: float | None = None,
        thermal_measure_roi: object | None = None,
    ) -> tuple[str, str]:
        self.last_thermal_temperature = thermal_temperature
        self.last_thermal_measure_roi = (
            thermal_measure_roi.model_dump()
            if hasattr(thermal_measure_roi, "model_dump")
            else thermal_measure_roi
        )
        return f"{event_id}.raw.jpg", self.url
