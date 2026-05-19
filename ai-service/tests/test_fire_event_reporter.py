from typing import Any, Dict, List

from app.models.event import DualStreamEvent
from app.models.task import TaskRecord
from app.services.fire_event_reporter import (
    FireEventReporter,
    build_fire_event_payload,
)


def test_low_alone_does_not_post():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    posted = reporter.maybe_report(task, _event(risk="LOW"))

    assert posted is False
    assert backend.posts == []


def test_low_to_medium_posts_once():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    reporter.maybe_report(task, _event(risk="LOW"))
    posted = reporter.maybe_report(task, _event(risk="MEDIUM"))

    assert posted is True
    assert len(backend.posts) == 1
    assert backend.posts[0]["fireLevel"] == "MEDIUM"


def test_same_level_does_not_repeat_post():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    reporter.maybe_report(task, _event(risk="MEDIUM"))
    reporter.maybe_report(task, _event(risk="MEDIUM"))
    reporter.maybe_report(task, _event(risk="MEDIUM"))

    assert len(backend.posts) == 1


def test_medium_to_high_posts_again():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    reporter.maybe_report(task, _event(risk="MEDIUM"))
    reporter.maybe_report(task, _event(risk="HIGH"))

    assert [p["fireLevel"] for p in backend.posts] == ["MEDIUM", "HIGH"]


def test_high_to_medium_does_not_post():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    reporter.maybe_report(task, _event(risk="HIGH"))
    reporter.maybe_report(task, _event(risk="MEDIUM"))

    assert len(backend.posts) == 1
    assert backend.posts[0]["fireLevel"] == "HIGH"


def test_falling_back_to_low_then_rising_posts_again():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    reporter.maybe_report(task, _event(risk="MEDIUM"))
    reporter.maybe_report(task, _event(risk="LOW"))
    reporter.maybe_report(task, _event(risk="MEDIUM"))

    assert [p["fireLevel"] for p in backend.posts] == ["MEDIUM", "MEDIUM"]


def test_per_task_state_is_isolated():
    backend = RecordingBackend()
    reporter = FireEventReporter(backend)
    task_a = _task(task_id="task-a")
    task_b = _task(task_id="task-b")

    reporter.maybe_report(task_a, _event(risk="MEDIUM"))
    reporter.maybe_report(task_b, _event(risk="MEDIUM"))

    assert [p["eventId"].startswith("task-a") for p in backend.posts] == [True, False]
    assert [p["eventId"].startswith("task-b") for p in backend.posts] == [False, True]


def test_post_failure_logged_and_state_unchanged():
    backend = RaisingBackend()
    reporter = FireEventReporter(backend)
    task = _task()

    posted = reporter.maybe_report(task, _event(risk="MEDIUM"))

    assert posted is False
    # state never advanced, next attempt should retry
    backend2 = RecordingBackend()
    reporter_swap = FireEventReporter(backend2)
    reporter_swap._last_posted_risk = reporter._last_posted_risk  # copy state
    reporter_swap.maybe_report(task, _event(risk="MEDIUM"))
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
) -> DualStreamEvent:
    fusion = round(visible * 0.6 + thermal * 0.4, 3)
    return DualStreamEvent(
        source_ts=ts,
        visible_score=visible,
        thermal_score=thermal,
        fusion_score=fusion,
        risk_level=risk,
        analysis_channel="visible" if thermal == 0 else "dual",
    )


class RecordingBackend:
    def __init__(self) -> None:
        self.posts: List[Dict[str, Any]] = []

    def report_fire_event(self, payload: Dict[str, Any]) -> None:
        self.posts.append(payload)


class RaisingBackend:
    def report_fire_event(self, payload: Dict[str, Any]) -> None:
        raise RuntimeError("backend down")
