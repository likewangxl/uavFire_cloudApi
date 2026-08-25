from app.config.settings import Settings
from app.models.task import TaskCreateRequest, TaskRecord


SUPPORTED_PAYLOAD_MODELS = {"H20", "H20T", "H30", "H30T"}


def apply_payload_profile(payload: TaskCreateRequest, settings: Settings) -> TaskCreateRequest:
    model = (payload.payload_model_key or "").strip().upper()
    if not model:
        return payload
    if model not in SUPPORTED_PAYLOAD_MODELS:
        raise ValueError(f"unsupported-payload-model:{model}")
    verified = {
        item.strip().upper()
        for item in settings.verified_payload_models.split(",")
        if item.strip()
    }
    if model not in verified:
        raise ValueError(f"payload-model-not-verified:{model}")
    prefix = model.lower()
    model_path = getattr(settings, f"{prefix}_visible_yolo_model_path") or settings.visible_yolo_model_path
    confidence = getattr(settings, f"{prefix}_visible_confidence_floor")
    return payload.model_copy(update={
        "payload_model_key": model,
        "visible_model_path": model_path,
        "visible_confidence_floor": confidence,
    })


def settings_for_task(settings: Settings, task: TaskRecord) -> Settings:
    return settings.model_copy(update={
        "visible_yolo_model_path": task.visible_model_path or settings.visible_yolo_model_path,
        "visible_confidence_floor": (
            task.visible_confidence_floor
            if task.visible_confidence_floor is not None
            else settings.visible_confidence_floor
        ),
    })
