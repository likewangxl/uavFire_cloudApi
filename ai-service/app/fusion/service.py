from app.models.event import DualStreamEvent


class DualStreamFusionService:
    def combine(
        self,
        visible_score: float,
        thermal_score: float,
        source_ts: int,
        analysis_channel: str | None = None,
    ) -> DualStreamEvent:
        fusion_score = round((visible_score * 0.6) + (thermal_score * 0.4), 3)
        risk_level = (
            "HIGH"
            if fusion_score >= 0.7
            else "MEDIUM"
            if fusion_score >= 0.4
            else "LOW"
        )
        return DualStreamEvent(
            source_ts=source_ts,
            visible_score=visible_score,
            thermal_score=thermal_score,
            fusion_score=fusion_score,
            risk_level=risk_level,
            analysis_channel=analysis_channel,
        )
