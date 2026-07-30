from app.models.event import DualStreamEvent
from app.models.task import TaskCreateRequest
from app.services.task_registry import TaskRegistry
from typing import Optional
import numpy as np


def test_record_detection_event_appends_local_event_and_reports_to_backend():
    backend = RecordingBackendClient()
    registry = TaskRegistry(backend_client=backend)
    registry.create(
        TaskCreateRequest(
            task_id="task-100",
            drone_sn="DRONE-100",
            visible_stream_url="rtsp://visible",
            thermal_stream_url="rtsp://thermal",
        )
    )

    registry.record_detection_event(
        "task-100",
        DualStreamEvent(
            source_ts=1710000000,
            visible_score=0.81,
            thermal_score=0.74,
            fusion_score=0.782,
            risk_level="HIGH",
            analysis_channel="visible",
        ),
    )

    events = registry.list_events("task-100")
    assert events[-1].event_type == "detection"
    assert events[-1].risk_level == "HIGH"
    assert events[-1].fusion_score == 0.782
    assert events[-1].analysis_channel == "visible"
    assert backend.last_task_id == "task-100"
    assert backend.last_payload["drone_sn"] == "DRONE-100"
    assert backend.last_payload["risk_level"] == "HIGH"
    assert backend.last_payload["analysis_channel"] == "visible"
    assert backend.fire_event_payloads == []


def test_record_detection_event_reports_visible_roi_with_both_aliases():
    backend = RecordingBackendClient()
    registry = TaskRegistry(backend_client=backend)
    registry.create(
        TaskCreateRequest(
            task_id="task-visible-roi",
            drone_sn="DRONE-ROI",
            visible_stream_url="rtsp://visible",
            thermal_stream_url="",
        )
    )

    record = registry.record_detection_event(
        "task-visible-roi",
        DualStreamEvent(
            source_ts=1710000001,
            visible_score=0.81,
            thermal_score=0.0,
            fusion_score=0.81,
            risk_level="HIGH",
            analysis_channel="visible",
            visible_roi={"x": 0.5, "y": 0.2, "width": 0.4, "height": 0.4},
        ),
    )

    expected = {"x": 0.5, "y": 0.2, "width": 0.4, "height": 0.4}
    assert record.visible_roi is not None
    assert record.visible_roi.model_dump() == expected
    assert backend.last_payload["visible_roi"] == expected
    assert backend.last_payload["visibleRoi"] == expected


def test_record_detection_event_reports_visible_low_fire_event_after_two_frames():
    # 纯可见光模式：连续两帧达线即建火情事件，不再等红外确认。
    backend = RecordingBackendClient()
    from app.services.fire_event_reporter import FireEventReporter

    registry = TaskRegistry(
        backend_client=backend,
        fire_event_reporter=FireEventReporter(backend),
    )
    registry.create(
        TaskCreateRequest(
            task_id="task-low",
            drone_sn="DRONE-LOW",
            visible_stream_url="rtsp://visible",
            thermal_stream_url="",
        )
    )
    thermal_palette_frame = np.zeros((16, 16, 3), dtype=np.uint8)
    thermal_palette_frame[:, :, 0] = 18
    thermal_palette_frame[:, :, 1] = 95
    thermal_palette_frame[:, :, 2] = 220

    for source_ts in (1710000000, 1710001500):
        registry.record_detection_event(
            "task-low",
            DualStreamEvent(
                source_ts=source_ts,
                visible_score=0.33,
                thermal_score=0.0,
                fusion_score=0.33,
                risk_level="LOW",
                analysis_channel="visible",
            ),
        )

    assert len(backend.fire_event_payloads) == 1
    assert backend.fire_event_payloads[0]["fireLevel"] == "LOW"
    assert backend.fire_event_payloads[0]["confidence"] == 0.33


def test_record_detection_event_reports_visible_snapshot_url_for_backend_confirmation():
    backend = RecordingBackendClient()
    registry = TaskRegistry(
        backend_client=backend,
        detection_snapshot_writer=FakeSnapshotWriter("http://snapshots/visible.jpg"),
    )
    registry.create(
        TaskCreateRequest(
            task_id="task-low",
            drone_sn="DRONE-LOW",
            visible_stream_url="rtsp://visible",
            thermal_stream_url="",
        )
    )
    thermal_palette_frame = np.zeros((16, 16, 3), dtype=np.uint8)
    thermal_palette_frame[:, :, 0] = 18
    thermal_palette_frame[:, :, 1] = 95
    thermal_palette_frame[:, :, 2] = 220

    registry.record_detection_event(
        "task-low",
        DualStreamEvent(
            source_ts=1710000000,
            visible_score=0.33,
            thermal_score=0.0,
            fusion_score=0.33,
            risk_level="LOW",
            analysis_channel="visible",
        ),
        visible_frame=object(),
    )

    assert backend.last_payload["visible_image_url"] == "http://snapshots/visible.jpg"
    assert backend.last_payload["visibleImageUrl"] == "http://snapshots/visible.jpg"
    assert registry.list_events("task-low")[-1].visible_image_url == "http://snapshots/visible.jpg"


def test_record_detection_event_does_not_report_thermal_palette_frame_as_visible_snapshot():
    backend = RecordingBackendClient()
    registry = TaskRegistry(
        backend_client=backend,
        detection_snapshot_writer=FakeSnapshotWriter("http://snapshots/not-visible.jpg"),
    )
    registry.create(
        TaskCreateRequest(
            task_id="task-low",
            drone_sn="DRONE-LOW",
            visible_stream_url="rtsp://visible",
            thermal_stream_url="",
        )
    )
    thermal_palette_frame = np.zeros((16, 16, 3), dtype=np.uint8)
    thermal_palette_frame[:, :, :] = 60
    thermal_palette_frame[4:12, 4:12, :] = 190
    thermal_palette_frame[6:10, 6:10, :] = 235

    registry.record_detection_event(
        "task-low",
        DualStreamEvent(
            source_ts=1710000000,
            visible_score=0.33,
            thermal_score=0.0,
            fusion_score=0.33,
            risk_level="LOW",
            analysis_channel="visible",
        ),
        visible_frame=thermal_palette_frame,
    )

    assert backend.last_payload["visible_image_url"] is None
    assert backend.last_payload["visibleImageUrl"] is None
    assert registry.list_events("task-low")[-1].visible_image_url is None


def test_record_detection_event_reports_thermal_snapshot_url_for_backend_confirmation():
    backend = RecordingBackendClient()
    snapshot_writer = FakeSnapshotWriter("http://snapshots/thermal.jpg")
    registry = TaskRegistry(
        backend_client=backend,
        detection_snapshot_writer=snapshot_writer,
    )
    registry.create(
        TaskCreateRequest(
            task_id="task-low",
            drone_sn="DRONE-LOW",
            visible_stream_url="rtsp://visible",
            thermal_stream_url="",
        )
    )
    thermal_palette_frame = np.zeros((16, 16, 3), dtype=np.uint8)
    thermal_palette_frame[:, :, :] = 60
    thermal_palette_frame[4:12, 4:12, :] = 190
    thermal_palette_frame[6:10, 6:10, :] = 235

    registry.record_detection_event(
        "task-low",
        DualStreamEvent(
            source_ts=1710000000,
            visible_score=0.33,
            thermal_score=0.0,
            fusion_score=0.33,
            risk_level="LOW",
            analysis_channel="thermal",
            thermal_temperature=57.64,
        ),
        thermal_frame=thermal_palette_frame,
    )

    assert backend.last_payload["thermal_image_url"] == "http://snapshots/thermal.jpg"
    assert backend.last_payload["thermalImageUrl"] == "http://snapshots/thermal.jpg"
    assert backend.last_payload["thermal_temperature"] == 57.64
    assert backend.last_payload["thermalTemperature"] == 57.64
    assert backend.last_payload["visible_image_url"] is None
    assert snapshot_writer.last_thermal_temperature == 57.64
    assert snapshot_writer.last_thermal_measure_roi is None
    assert registry.list_events("task-low")[-1].thermal_image_url == "http://snapshots/thermal.jpg"
    assert registry.list_events("task-low")[-1].thermal_temperature == 57.64


def test_record_detection_event_reports_thermal_measure_roi_to_snapshot_and_backend():
    backend = RecordingBackendClient()
    snapshot_writer = FakeSnapshotWriter("http://snapshots/thermal.jpg")
    registry = TaskRegistry(
        backend_client=backend,
        detection_snapshot_writer=snapshot_writer,
    )
    registry.create(
        TaskCreateRequest(
            task_id="task-roi",
            drone_sn="DRONE-ROI",
            visible_stream_url="rtsp://visible",
            thermal_stream_url="",
        )
    )
    thermal_palette_frame = np.zeros((16, 16, 3), dtype=np.uint8)
    thermal_palette_frame[:, :, :] = 60
    thermal_palette_frame[4:12, 4:12, :] = 190
    thermal_palette_frame[6:10, 6:10, :] = 235

    registry.record_detection_event(
        "task-roi",
        DualStreamEvent(
            source_ts=1710000000,
            visible_score=0.0,
            thermal_score=0.42,
            fusion_score=0.42,
            risk_level="MEDIUM",
            analysis_channel="thermal",
            thermal_temperature=57.64,
            thermal_measure_roi={"x": 0.375, "y": 0.375, "width": 0.25, "height": 0.25},
        ),
        thermal_frame=thermal_palette_frame,
    )

    expected = {"x": 0.375, "y": 0.375, "width": 0.25, "height": 0.25}
    assert snapshot_writer.last_thermal_measure_roi == expected
    assert backend.last_payload["thermal_measure_roi"] == expected
    assert backend.last_payload["thermalMeasureRoi"] == expected
    assert registry.list_events("task-roi")[-1].thermal_measure_roi.model_dump() == expected


def test_record_detection_event_reports_grayscale_thermal_snapshot_url():
    backend = RecordingBackendClient()
    registry = TaskRegistry(
        backend_client=backend,
        detection_snapshot_writer=FakeSnapshotWriter("http://snapshots/thermal-gray.jpg"),
    )
    registry.create(
        TaskCreateRequest(
            task_id="task-gray-thermal",
            drone_sn="DRONE-GRAY",
            visible_stream_url="rtsp://visible",
            thermal_stream_url="",
        )
    )
    grayscale_thermal_frame = np.zeros((32, 32, 3), dtype=np.uint8)
    grayscale_thermal_frame[:, :, :] = 70
    grayscale_thermal_frame[8:20, 10:22, :] = 185
    grayscale_thermal_frame[12:16, 14:18, :] = 235

    registry.record_detection_event(
        "task-gray-thermal",
        DualStreamEvent(
            source_ts=1710000001,
            visible_score=0.0,
            thermal_score=0.42,
            fusion_score=0.42,
            risk_level="MEDIUM",
            analysis_channel="thermal",
        ),
        thermal_frame=grayscale_thermal_frame,
    )

    assert backend.last_payload["thermal_image_url"] == "http://snapshots/thermal-gray.jpg"
    assert backend.last_payload["thermalImageUrl"] == "http://snapshots/thermal-gray.jpg"
    assert registry.list_events("task-gray-thermal")[-1].thermal_image_url == "http://snapshots/thermal-gray.jpg"


def test_record_detection_event_does_not_report_visible_frame_as_thermal_snapshot():
    backend = RecordingBackendClient()
    registry = TaskRegistry(
        backend_client=backend,
        detection_snapshot_writer=FakeSnapshotWriter("http://snapshots/not-thermal.jpg"),
    )
    registry.create(
        TaskCreateRequest(
            task_id="task-low",
            drone_sn="DRONE-LOW",
            visible_stream_url="rtsp://visible",
            thermal_stream_url="",
        )
    )
    visible_frame = np.zeros((16, 16, 3), dtype=np.uint8)
    visible_frame[:, :, 0] = 115
    visible_frame[:, :, 1] = 105
    visible_frame[:, :, 2] = 95

    registry.record_detection_event(
        "task-low",
        DualStreamEvent(
            source_ts=1710000000,
            visible_score=0.0,
            thermal_score=0.33,
            fusion_score=0.33,
            risk_level="LOW",
            analysis_channel="thermal",
        ),
        thermal_frame=visible_frame,
    )

    assert backend.last_payload["thermal_image_url"] is None
    assert backend.last_payload["thermalImageUrl"] is None
    assert registry.list_events("task-low")[-1].thermal_image_url is None


def test_record_detection_event_does_not_report_visible_fire_colored_frame_as_thermal_snapshot():
    backend = RecordingBackendClient()
    registry = TaskRegistry(
        backend_client=backend,
        detection_snapshot_writer=FakeSnapshotWriter("http://snapshots/visible-fire.jpg"),
    )
    registry.create(
        TaskCreateRequest(
            task_id="task-visible-fire",
            drone_sn="DRONE-FIRE",
            visible_stream_url="rtsp://visible",
            thermal_stream_url="",
        )
    )
    visible_fire_frame = np.zeros((32, 32, 3), dtype=np.uint8)
    visible_fire_frame[:, :, 0] = 25
    visible_fire_frame[:, :, 1] = 80
    visible_fire_frame[:, :, 2] = 220

    registry.record_detection_event(
        "task-visible-fire",
        DualStreamEvent(
            source_ts=1710000002,
            visible_score=0.0,
            thermal_score=0.92,
            fusion_score=0.92,
            risk_level="HIGH",
            analysis_channel="thermal",
        ),
        thermal_frame=visible_fire_frame,
    )

    assert backend.last_payload["thermal_image_url"] is None
    assert backend.last_payload["thermalImageUrl"] is None
    assert registry.list_events("task-visible-fire")[-1].thermal_image_url is None


def test_record_detection_event_reports_low_thermal_fire_event_when_reporter_is_bound():
    backend = RecordingBackendClient()
    from app.services.fire_event_reporter import FireEventReporter

    registry = TaskRegistry(
        backend_client=backend,
        fire_event_reporter=FireEventReporter(backend),
    )
    registry.create(
        TaskCreateRequest(
            task_id="task-low",
            drone_sn="DRONE-LOW",
            visible_stream_url="rtsp://visible",
            thermal_stream_url="",
        )
    )

    registry.record_detection_event(
        "task-low",
        DualStreamEvent(
            source_ts=1710000000,
            visible_score=0.0,
            thermal_score=0.17,
            fusion_score=0.17,
            risk_level="LOW",
            analysis_channel="thermal",
        ),
    )

    assert len(backend.fire_event_payloads) == 1
    assert backend.fire_event_payloads[0]["eventId"] == "task-low-1710000000"
    assert backend.fire_event_payloads[0]["deviceSn"] == "DRONE-LOW"
    assert backend.fire_event_payloads[0]["fireLevel"] == "LOW"
    assert backend.fire_event_payloads[0]["confidence"] == 0.17


def test_record_detection_event_keeps_local_event_when_backend_report_fails():
    backend = FailingBackendClient()
    registry = TaskRegistry(backend_client=backend)
    registry.create(
        TaskCreateRequest(
            task_id="task-backend-down",
            drone_sn="DRONE-DOWN",
            visible_stream_url="rtsp://visible",
            thermal_stream_url="",
        )
    )

    record = registry.record_detection_event(
        "task-backend-down",
        DualStreamEvent(
            source_ts=1710000003,
            visible_score=0.5,
            thermal_score=0.0,
            fusion_score=0.5,
            risk_level="MEDIUM",
            analysis_channel="visible",
        ),
    )

    assert record.event_type == "detection"
    assert registry.list_events("task-backend-down")[-1].source_ts == 1710000003


class RecordingBackendClient:
    def __init__(self) -> None:
        self.last_task_id: Optional[str] = None
        self.last_payload: Optional[dict] = None
        self.fire_event_payloads = []

    def report_event(self, task_id: str, payload: dict) -> None:
        self.last_task_id = task_id
        self.last_payload = payload

    def report_fire_event(self, payload: dict) -> None:
        self.fire_event_payloads.append(payload)


class FailingBackendClient:
    def report_event(self, task_id: str, payload: dict) -> None:
        raise RuntimeError("backend-502")


class FakeSnapshotWriter:
    def __init__(self, url: str) -> None:
        self.url = url
        self.last_thermal_temperature: Optional[float] = None
        self.last_thermal_measure_roi: Optional[dict] = None

    def write_pair(
        self,
        event_id: str,
        frame: object,
        boxes: object,
        thermal_temperature: Optional[float] = None,
        thermal_measure_roi: Optional[object] = None,
    ) -> tuple[str, str]:
        self.last_thermal_temperature = thermal_temperature
        self.last_thermal_measure_roi = (
            thermal_measure_roi.model_dump()
            if hasattr(thermal_measure_roi, "model_dump")
            else thermal_measure_roi
        )
        return f"{event_id}.raw.jpg", self.url
