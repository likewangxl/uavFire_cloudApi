from app.config.settings import Settings


def test_settings_default_task_limit():
    settings = Settings(_env_file=None)

    assert settings.max_concurrent_tasks == 2
    assert settings.log_level == "INFO"
    assert settings.backend_access_token == ""
    assert settings.backend_username == ""
    assert settings.backend_password == ""
    assert settings.backend_login_flag == 1
    assert settings.visible_detector_mode == "color"
    assert settings.visible_fire_saturation_ratio == 0.01
    assert settings.visible_yolo_imgsz == 1280
