from app.fusion.service import DualStreamFusionService


def test_fusion_service_combines_visible_and_thermal_scores():
    fusion = DualStreamFusionService()
    event = fusion.combine(
        visible_score=0.82,
        thermal_score=0.74,
        source_ts=1710000000,
    )

    assert event.risk_level == "HIGH"
    assert event.fusion_score > 0.7
