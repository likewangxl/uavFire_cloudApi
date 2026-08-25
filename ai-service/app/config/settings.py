from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    host: str = "0.0.0.0"
    port: int = 9000
    log_level: str = "INFO"
    max_concurrent_tasks: int = 2
    backend_base_url: str = ""
    backend_access_token: str = ""
    backend_username: str = ""
    backend_password: str = ""
    backend_login_flag: int = 1
    use_continuous_runner: bool = False
    visible_detector_mode: str = "color"
    visible_yolo_model_path: str = ""
    visible_yolo_imgsz: int = 1280
    visible_yolo_device: str = "auto"
    visible_confidence_floor: float = 0.25
    visible_box_display_floor: float = 0.25
    visible_target_classes: str = "fire,smoke"
    visible_fire_saturation_ratio: float = 0.01
    h20_visible_yolo_model_path: str = ""
    h20_visible_confidence_floor: float = 0.25
    h20t_visible_yolo_model_path: str = ""
    h20t_visible_confidence_floor: float = 0.25
    h30_visible_yolo_model_path: str = ""
    h30_visible_confidence_floor: float = 0.25
    h30t_visible_yolo_model_path: str = ""
    h30t_visible_confidence_floor: float = 0.25
    verified_payload_models: str = ""
    thermal_detector_mode: str = "brightness"
    thermal_yolo_model_path: str = ""
    thermal_yolo_imgsz: int = 640
    thermal_yolo_conf_threshold: float = 0.25
    thermal_intensity_threshold: int = 200
    thermal_saturation_ratio: float = 0.05
    snapshot_dir: str = "data/fire-snapshots"
    snapshot_public_base_url: str = "http://127.0.0.1:9000/api/v1/snapshots"

    model_config = SettingsConfigDict(
        env_prefix="AI_SERVICE_",
        case_sensitive=False,
        env_file=".env",
        extra="ignore",
    )
