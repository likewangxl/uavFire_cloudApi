import pytest

from app.config.payload_profiles import apply_payload_profile
from app.config.settings import Settings
from app.models.task import TaskCreateRequest


def task(model: str) -> TaskCreateRequest:
    return TaskCreateRequest(
        task_id="task-1",
        drone_sn="M300-1",
        visible_stream_url="rtmp://example/live/M300-1-0",
        thermal_stream_url="",
        payload_model_key=model,
    )


def test_each_verified_payload_selects_its_own_profile():
    settings = Settings(
        verified_payload_models="H20,H20T,H30,H30T",
        h20_visible_confidence_floor=0.21,
        h20t_visible_confidence_floor=0.22,
        h30_visible_confidence_floor=0.23,
        h30t_visible_confidence_floor=0.24,
    )
    assert [apply_payload_profile(task(model), settings).visible_confidence_floor for model in
            ("H20", "H20T", "H30", "H30T")] == [0.21, 0.22, 0.23, 0.24]


def test_unverified_payload_fails_closed():
    with pytest.raises(ValueError, match="payload-model-not-verified:H30T"):
        apply_payload_profile(task("H30T"), Settings())
