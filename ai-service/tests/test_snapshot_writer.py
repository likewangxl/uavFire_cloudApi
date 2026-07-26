import numpy as np

from app.api.routes import ThermalAnnotationRequest, parse_thermal_annotation_request
from app.models.event import DualStreamEvent
from app.services.snapshot_writer import SnapshotWriter
from app.services.snapshot_writer import boxes_from_yolo_results


def test_dual_stream_event_preserves_thermal_temperature():
    event = DualStreamEvent(
        source_ts=1710000000,
        visible_score=0.0,
        thermal_score=0.17,
        fusion_score=0.17,
        risk_level="LOW",
        analysis_channel="thermal",
        thermal_temperature=57.64,
        thermal_measure_roi={"x": 0.2, "y": 0.3, "width": 0.1, "height": 0.2},
    )

    assert event.thermal_temperature == 57.64
    assert event.thermal_measure_roi is not None
    assert event.thermal_measure_roi.x == 0.2


def test_thermal_annotation_request_accepts_backend_camel_case_payload():
    payload = ThermalAnnotationRequest.model_validate(
        {
            "thermalTemperature": 153.0,
            "thermalMeasureRoi": {"x": 0.1, "y": 0.2, "width": 0.3, "height": 0.4},
        }
    )

    assert payload.thermal_temperature == 153.0
    assert payload.thermal_measure_roi == {"x": 0.1, "y": 0.2, "width": 0.3, "height": 0.4}


def test_thermal_annotation_parser_accepts_backend_snake_case_payload():
    payload = parse_thermal_annotation_request(
        {
            "thermal_temperature": "25.4",
            "thermal_measure_roi": {"x": 0.1, "y": 0.2, "width": 0.3, "height": 0.4},
            "thermal_detect_roi": {"x": 0.11, "y": 0.21, "width": 0.1, "height": 0.1},
            "thermal_measurements": [
                {"temperature_c": 25.4, "roi": {"x": 0.1, "y": 0.2, "width": 0.3, "height": 0.4}},
                {"temperatureC": 31.2, "roi": {"x": 0.5, "y": 0.6, "width": 0.1, "height": 0.1}},
            ],
        }
    )

    assert payload.thermal_temperature == 25.4
    assert payload.thermal_measure_roi == {"x": 0.1, "y": 0.2, "width": 0.3, "height": 0.4}
    assert payload.thermal_detect_roi == {"x": 0.11, "y": 0.21, "width": 0.1, "height": 0.1}
    assert len(payload.thermal_measurements) == 2
    assert payload.thermal_measurements[1]["temperatureC"] == 31.2


def test_snapshot_writer_draws_thermal_temperature_label(monkeypatch, tmp_path):
    drawn_text = []
    drawn_rectangles = []

    def fake_put_text(image, text, org, font_face, font_scale, color, thickness, line_type=None):
        drawn_text.append((text, org))
        return image

    def fake_rectangle(image, pt1, pt2, color, thickness=None, line_type=None, shift=None):
        drawn_rectangles.append((pt1, pt2, thickness))
        return image

    monkeypatch.setattr("app.services.snapshot_writer.cv2.putText", fake_put_text)
    monkeypatch.setattr("app.services.snapshot_writer.cv2.rectangle", fake_rectangle)

    writer = SnapshotWriter(str(tmp_path), "http://snapshots")
    frame = np.zeros((100, 400, 3), dtype=np.uint8)
    frame[80:84, 170:174, :] = 240

    raw_url, annotated_url = writer.write_pair(
        "thermal-event",
        frame,
        boxes=None,
        thermal_temperature=57.64,
    )

    assert raw_url == "http://snapshots/thermal-event-raw.jpg"
    assert annotated_url == "http://snapshots/thermal-event-annotated.jpg"
    assert ("57.6C", (266, 50)) in drawn_text
    assert ((140, 35), (260, 65), 2) in drawn_rectangles


def test_boxes_from_yolo_results_suppresses_duplicate_same_label_boxes():
    class Boxes:
        xyxy = [
            [100, 100, 140, 180],
            [102, 102, 142, 182],
            [220, 100, 260, 180],
        ]
        cls = [0, 0, 0]
        conf = [0.23, 0.18, 0.31]

    class Result:
        boxes = Boxes()
        names = {0: "fire"}

    boxes = boxes_from_yolo_results([Result()], target_class_names={"fire"})

    assert boxes == [
        {"x1": 220.0, "y1": 100.0, "x2": 260.0, "y2": 180.0, "conf": 0.31, "label": "fire"},
        {"x1": 100.0, "y1": 100.0, "x2": 140.0, "y2": 180.0, "conf": 0.23, "label": "fire"},
    ]


def test_snapshot_writer_draws_thermal_temperature_on_requested_measure_roi(monkeypatch, tmp_path):
    drawn_text = []
    drawn_rectangles = []

    def fake_put_text(image, text, org, font_face, font_scale, color, thickness, line_type=None):
        drawn_text.append((text, org))
        return image

    def fake_rectangle(image, pt1, pt2, color, thickness=None, line_type=None, shift=None):
        drawn_rectangles.append((pt1, pt2, thickness))
        return image

    monkeypatch.setattr("app.services.snapshot_writer.cv2.putText", fake_put_text)
    monkeypatch.setattr("app.services.snapshot_writer.cv2.rectangle", fake_rectangle)

    writer = SnapshotWriter(str(tmp_path), "http://snapshots")
    frame = np.zeros((100, 400, 3), dtype=np.uint8)

    writer.write_pair(
        "thermal-event",
        frame,
        boxes=None,
        thermal_temperature=57.64,
        thermal_measure_roi={"x": 0.10, "y": 0.20, "width": 0.25, "height": 0.30},
    )

    assert ((40, 20), (140, 50), 2) in drawn_rectangles
    assert ("57.6C", (146, 35)) in drawn_text


def test_snapshot_writer_draws_measure_roi_relative_to_active_thermal_content(monkeypatch, tmp_path):
    drawn_text = []
    drawn_rectangles = []

    def fake_put_text(image, text, org, font_face, font_scale, color, thickness, line_type=None):
        drawn_text.append((text, org))
        return image

    def fake_rectangle(image, pt1, pt2, color, thickness=None, line_type=None, shift=None):
        drawn_rectangles.append((pt1, pt2, thickness))
        return image

    monkeypatch.setattr("app.services.snapshot_writer.cv2.putText", fake_put_text)
    monkeypatch.setattr("app.services.snapshot_writer.cv2.rectangle", fake_rectangle)

    writer = SnapshotWriter(str(tmp_path), "http://snapshots")
    frame = np.zeros((100, 200, 3), dtype=np.uint8)
    frame[:, 50:150, :] = 40

    writer.write_pair(
        "thermal-event",
        frame,
        boxes=None,
        thermal_temperature=57.64,
        thermal_measure_roi={"x": 0.66, "y": 0.46, "width": 0.08, "height": 0.08},
    )

    assert ((116, 46), (124, 54), 2) in drawn_rectangles
    assert ("57.6C", (84, 50)) in drawn_text


def test_snapshot_writer_draws_detected_hotspot_and_measure_roi(monkeypatch, tmp_path):
    drawn_rectangles = []

    def fake_rectangle(image, pt1, pt2, color, thickness=None, line_type=None, shift=None):
        drawn_rectangles.append((pt1, pt2, color, thickness))
        return image

    monkeypatch.setattr("app.services.snapshot_writer.cv2.rectangle", fake_rectangle)

    writer = SnapshotWriter(str(tmp_path), "http://snapshots")
    frame = np.zeros((100, 200, 3), dtype=np.uint8)

    writer.write_pair(
        "thermal-event",
        frame,
        boxes=None,
        thermal_temperature=57.64,
        thermal_measure_roi={"x": 0.60, "y": 0.40, "width": 0.08, "height": 0.08},
        thermal_detect_roi={"x": 0.62, "y": 0.42, "width": 0.04, "height": 0.04},
    )

    assert ((124, 42), (132, 46), (255, 0, 255), 1) in drawn_rectangles
    assert ((120, 40), (136, 48), (0, 255, 255), 2) in drawn_rectangles


def test_snapshot_writer_draws_all_thermal_measurements(monkeypatch, tmp_path):
    drawn_text = []
    drawn_rectangles = []

    def fake_put_text(image, text, org, font_face, font_scale, color, thickness, line_type=None):
        drawn_text.append((text, org))
        return image

    def fake_rectangle(image, pt1, pt2, color, thickness=None, line_type=None, shift=None):
        drawn_rectangles.append((pt1, pt2, color, thickness))
        return image

    monkeypatch.setattr("app.services.snapshot_writer.cv2.putText", fake_put_text)
    monkeypatch.setattr("app.services.snapshot_writer.cv2.rectangle", fake_rectangle)

    writer = SnapshotWriter(str(tmp_path), "http://snapshots")
    frame = np.zeros((100, 200, 3), dtype=np.uint8)

    writer.write_pair(
        "thermal-event",
        frame,
        boxes=None,
        thermal_temperature=88.5,
        thermal_measure_roi={"x": 0.60, "y": 0.40, "width": 0.08, "height": 0.08},
        thermal_measurements=[
            {"temperature_c": 88.5, "roi": {"x": 0.60, "y": 0.40, "width": 0.08, "height": 0.08}},
            {"temperatureC": 57.2, "roi": {"x": 0.10, "y": 0.70, "width": 0.06, "height": 0.06}},
        ],
    )

    assert ((120, 40), (136, 48), (0, 255, 255), 2) in drawn_rectangles
    assert ((20, 70), (32, 76), (0, 200, 255), 1) in drawn_rectangles
    assert any(text == "88.5C" for text, _ in drawn_text)
    assert any(text == "57.2C" for text, _ in drawn_text)


def test_snapshot_writer_can_refresh_existing_thermal_annotation(monkeypatch, tmp_path):
    drawn_text = []

    def fake_put_text(image, text, org, font_face, font_scale, color, thickness, line_type=None):
        drawn_text.append((text, org))
        return image

    monkeypatch.setattr("app.services.snapshot_writer.cv2.putText", fake_put_text)

    writer = SnapshotWriter(str(tmp_path), "http://snapshots")
    frame = np.zeros((100, 400, 3), dtype=np.uint8)
    writer.write_pair("thermal-event", frame, boxes=None)

    refreshed_url = writer.refresh_thermal_annotation(
        "thermal-event",
        thermal_temperature=57.64,
        thermal_measure_roi={"x": 0.10, "y": 0.20, "width": 0.25, "height": 0.30},
    )

    assert refreshed_url == "http://snapshots/thermal-event-annotated.jpg"
    assert ("57.6C", (146, 35)) in drawn_text


def test_load_raw_reads_existing_snapshot_and_none_when_missing(tmp_path):
    writer = SnapshotWriter(str(tmp_path), "http://snapshots")
    frame = np.full((10, 20, 3), 128, dtype=np.uint8)
    writer.write_pair("load-raw-event", frame, boxes=None)

    loaded = writer.load_raw("load-raw-event")

    assert loaded is not None
    assert loaded.shape == (10, 20, 3)
    assert writer.load_raw("no-such-event") is None


def test_refresh_thermal_annotation_draws_detection_boxes(monkeypatch, tmp_path):
    drawn_text = []
    drawn_rectangles = []

    def fake_put_text(image, text, org, font_face, font_scale, color, thickness, line_type=None):
        drawn_text.append((text, org))
        return image

    def fake_rectangle(image, pt1, pt2, color, thickness=None, line_type=None, shift=None):
        drawn_rectangles.append((pt1, pt2, color, thickness))
        return image

    monkeypatch.setattr("app.services.snapshot_writer.cv2.putText", fake_put_text)
    monkeypatch.setattr("app.services.snapshot_writer.cv2.rectangle", fake_rectangle)

    writer = SnapshotWriter(str(tmp_path), "http://snapshots")
    frame = np.zeros((100, 200, 3), dtype=np.uint8)
    writer.write_pair("thermal-boxes-event", frame, boxes=None)

    refreshed_url = writer.refresh_thermal_annotation(
        "thermal-boxes-event",
        thermal_temperature=153.0,
        thermal_measure_roi={"x": 0.10, "y": 0.20, "width": 0.06, "height": 0.06},
        boxes=[{"x1": 20, "y1": 30, "x2": 60, "y2": 70, "conf": 0.68, "label": "fire"}],
    )

    assert refreshed_url == "http://snapshots/thermal-boxes-event-annotated.jpg"
    assert ((20, 30), (60, 70), (0, 0, 255), 2) in drawn_rectangles
    assert any(text == "fire 0.68" for text, _ in drawn_text)
