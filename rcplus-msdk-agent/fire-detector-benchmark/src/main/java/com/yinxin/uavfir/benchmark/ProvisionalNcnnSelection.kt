package com.yinxin.uavfir.benchmark

import org.json.JSONObject

internal data class ProvisionalNcnnSelection(
    val status: String,
    val task3DevelopmentBuildAllowed: Boolean,
)

internal object ProvisionalNcnnSelectionPolicy {
    fun parse(json: String): ProvisionalNcnnSelection {
        val value = JSONObject(json)
        check(value.getInt("schemaVersion") == 1)
        check(value.getString("status") == "PROVISIONAL_NCNN_SELECTED")
        check(value.getString("engine") == "ncnn")
        check(value.getString("evidenceModality") == "thermal" && value.getInt("inputSize") == 640)
        check(value.getDouble("recall") == 0.985)
        check(value.getDouble("p95InferenceMillis") == 152.0)
        check(value.getDouble("firstFiveMinuteP95Millis") == 138.0)
        check(value.getDouble("finalFiveMinuteP95Millis") == 153.0)
        check(value.getBoolean("task3DevelopmentBuildAllowed"))
        check(!value.getBoolean("visible960GatePassed"))
        check(!value.getBoolean("defaultDetectionEnabled"))
        check(!value.getBoolean("productionReleaseAllowed"))
        check(value.getString("requiredFollowup").isNotBlank())
        return ProvisionalNcnnSelection(
            status = "PROVISIONAL_NCNN_SELECTED",
            task3DevelopmentBuildAllowed = true,
        )
    }
}
