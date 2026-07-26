package com.yinxin.uavfir.api

import android.util.Log
import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.session.DualStreamSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * 红外热点探针——仅为 HUD 供数。
 *
 * 周期性对画面最热点测温，结果经 sessionManager 记入
 * runtimeStatus.thermalCenterTemperatureC，驾驶舱 HUD 显示中心温度用。
 *
 * 火情触发与确认已串行化到后端（红外 YOLO 命中 → measure-thermal-region
 * 对检出框实测 → 温度达线确认 → 切可见光补证据照）。探针不再自主上报
 * 热点事件、不再驻留复测、不再自主切可见光拍确认照——2026-07 盛夏实测
 * 45°C 触发线被日晒地面（41~48°C）持续击穿，导致镜头反复切换与误报。
 */
class ThermalHotspotMonitor(
    private val sessionManager: DualStreamSessionManager,
    private val monitorScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val probeIntervalMs: Long = DEFAULT_PROBE_INTERVAL_MS,
    private val frameTriggerDebounceMs: Long = DEFAULT_FRAME_TRIGGER_DEBOUNCE_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : CommandPoller {
    private val probeMutex = Mutex()
    @Volatile
    private var lastProbeAtMs: Long = 0L
    @Volatile
    private var lastFrameTriggerCompletedAtMs: Long = 0L

    fun onFrameHotspotCandidate(droneSn: String): Job = monitorScope.launch {
        runFrameTriggeredProbe(droneSn)
    }

    override suspend fun pollOnce(droneSn: String) {
        if (!probeMutex.tryLock()) {
            return
        }
        try {
            pollOnceLocked(droneSn)
        } finally {
            probeMutex.unlock()
        }
    }

    private suspend fun pollOnceLocked(droneSn: String) {
        if (sessionManager.sessionState != DualStreamSessionState.RUNNING) {
            return
        }
        // 仅在火情监测开启时才探测热区。否则探测会周期性 focusThermal 把共享流切到红外，
        // 即便用户没开监测——见 DualStreamSessionManager.thermalMonitoringEnabled。
        if (!sessionManager.thermalMonitoringEnabled) {
            return
        }
        val now = clockMs()
        if (lastProbeAtMs > 0L && now - lastProbeAtMs < probeIntervalMs) {
            return
        }
        lastProbeAtMs = now

        probeHotspotForHud(droneSn)
    }

    private suspend fun runFrameTriggeredProbe(droneSn: String) {
        if (sessionManager.sessionState != DualStreamSessionState.RUNNING) {
            return
        }
        if (!sessionManager.thermalMonitoringEnabled) {
            return
        }
        if (!probeMutex.tryLock()) {
            return
        }
        var processed = false
        try {
            val now = clockMs()
            if (lastFrameTriggerCompletedAtMs > 0L && now - lastFrameTriggerCompletedAtMs < frameTriggerDebounceMs) {
                return
            }
            processed = true
            probeHotspotForHud(droneSn)
        } finally {
            if (processed) {
                lastFrameTriggerCompletedAtMs = clockMs()
            }
            probeMutex.unlock()
        }
    }

    private suspend fun probeHotspotForHud(droneSn: String) {
        val result = sessionManager.measureThermalHotspot(droneSn = droneSn)
        if (!result.status.equals("applied", ignoreCase = true)) {
            Log.w(TAG, "thermal hotspot probe failed drone=$droneSn message=${result.message}")
        }
    }

    companion object {
        private const val TAG = "ThermalHotspotMonitor"
        const val DEFAULT_PROBE_INTERVAL_MS: Long = 2_000L
        const val DEFAULT_FRAME_TRIGGER_DEBOUNCE_MS: Long = 2_000L
    }
}
