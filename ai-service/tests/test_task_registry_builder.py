from app.config.settings import Settings
from app.services.task_registry import (
    _build_backend_client,
    _build_thermal_analyzer,
    _build_visible_detector,
)


def test_build_visible_detector_returns_color_heuristic_when_model_path_empty_by_default():
    settings = Settings(visible_yolo_model_path="")
    detector = _build_visible_detector(settings)
    from app.inference.visible.detector import ColorFireVisibleDetector

    assert isinstance(detector, ColorFireVisibleDetector)


def test_build_visible_detector_uses_configured_fire_saturation_ratio():
    settings = Settings(
        visible_yolo_model_path="",
        visible_fire_saturation_ratio=0.012,
    )
    detector = _build_visible_detector(settings)

    assert detector._saturation_ratio == 0.012


def test_build_visible_detector_returns_stub_when_stub_mode_requested():
    settings = Settings(visible_yolo_model_path="", visible_detector_mode="stub")
    detector = _build_visible_detector(settings)
    from app.inference.visible.detector import StubVisibleDetector

    assert isinstance(detector, StubVisibleDetector)


def test_build_visible_detector_returns_yolo_when_model_path_set():
    settings = Settings(visible_yolo_model_path="best.pt")
    detector = _build_visible_detector(settings)
    from app.inference.visible.detector import YoloVisibleDetector

    assert isinstance(detector, YoloVisibleDetector)


def test_build_thermal_analyzer_returns_hotspot_when_continuous_runner_enabled():
    settings = Settings(use_continuous_runner=True)
    analyzer = _build_thermal_analyzer(settings)
    from app.inference.thermal.analyzer import HotSpotThermalAnalyzer

    assert isinstance(analyzer, HotSpotThermalAnalyzer)


def test_build_thermal_analyzer_returns_stub_when_continuous_runner_disabled():
    settings = Settings(use_continuous_runner=False)
    analyzer = _build_thermal_analyzer(settings)
    from app.inference.thermal.analyzer import StubThermalAnalyzer

    assert isinstance(analyzer, StubThermalAnalyzer)


def test_build_backend_client_returns_none_when_base_url_empty():
    settings = Settings(backend_base_url="")

    assert _build_backend_client(settings) is None


def test_build_backend_client_uses_auth_settings():
    settings = Settings(
        backend_base_url="http://backend",
        backend_access_token="token-abc",
        backend_username="adminPC",
        backend_password="adminPC",
        backend_login_flag=1,
    )

    client = _build_backend_client(settings)

    assert client is not None
    assert client.base_url == "http://backend"
    assert client.access_token == "token-abc"
    assert client.username == "adminPC"
    assert client.password == "adminPC"
    assert client.login_flag == 1
