package com.yinxin.uavfir.firedetection

import android.content.SharedPreferences

data class VisibleDetectorStatus(
    val intent: String,
    val state: String,
    val health: String,
    val reason: String? = null,
    val intentVersion: Long = 0L,
) {
    val running: Boolean get() = state == "ARMED" && health == "HEALTHY"

    companion object {
        fun disarmed(version: Long = 0L) = VisibleDetectorStatus("DISARMED", "DISARMED", "HEALTHY", "operator-disarmed", version)
    }
}

data class PersistedDetectorIntent(val intent: String, val version: Long)

interface DetectorIntentStore {
    fun load(): PersistedDetectorIntent?
    fun save(intent: PersistedDetectorIntent): Boolean
    fun loadFailureReason(): String? = null
}

class InMemoryDetectorIntentStore : DetectorIntentStore {
    @Volatile private var value: PersistedDetectorIntent? = null
    override fun load(): PersistedDetectorIntent? = value
    override fun save(intent: PersistedDetectorIntent): Boolean {
        value = intent
        return true
    }
}

class SharedPreferencesDetectorIntentStore(
    private val preferences: SharedPreferences,
) : DetectorIntentStore {
    @Volatile private var loadFailure: String? = null

    override fun load(): PersistedDetectorIntent? {
        return try {
            if (!preferences.contains(KEY_VERSION)) return null
            PersistedDetectorIntent(
                preferences.getString(KEY_INTENT, "DISARMED") ?: "DISARMED",
                preferences.getLong(KEY_VERSION, 0L),
            ).also { loadFailure = null }
        } catch (failure: RuntimeException) {
            loadFailure = "intent-store-load-failed:${failure::class.simpleName}"
            null
        }
    }

    override fun loadFailureReason(): String? = loadFailure

    override fun save(intent: PersistedDetectorIntent): Boolean = preferences.edit()
        .putString(KEY_INTENT, intent.intent)
        .putLong(KEY_VERSION, intent.version)
        .commit()

    private companion object {
        const val KEY_INTENT = "detector_intent"
        const val KEY_VERSION = "detector_intent_version"
    }
}

data class DetectorIntentApplication(val applied: Boolean, val status: VisibleDetectorStatus, val reason: String? = null)

class VisibleDetectorControl(
    private val store: DetectorIntentStore = InMemoryDetectorIntentStore(),
    private val armingHealth: () -> CoordinatorArmingHealth,
) {
    private val loadedIntent = runCatching { store.load() }
    private val loadedRecord = loadedIntent.getOrNull()
    @Volatile
    private var startupFailure = loadedIntent.exceptionOrNull()?.let {
        "intent-store-load-failed:${it::class.simpleName}"
    } ?: store.loadFailureReason() ?: loadedRecord?.takeIf {
        it.version < 0L || (it.intent != "ARMED" && it.intent != "DISARMED")
    }?.let { "intent-store-load-invalid" }
    @Volatile
    private var desired = loadedRecord
        ?.takeIf { it.version >= 0L && (it.intent == "ARMED" || it.intent == "DISARMED") }
        ?: PersistedDetectorIntent("DISARMED", 0L)
    @Volatile
    private var authorityConfirmed = desired.intent != "ARMED" && startupFailure == null

    fun isArmRequested(): Boolean = desired.intent == "ARMED" && authorityConfirmed && startupFailure == null

    fun arm(): VisibleDetectorStatus {
        return applyIntent("ARMED", desired.version + 1L).status
    }

    fun disarm(): VisibleDetectorStatus {
        return applyIntent("DISARMED", desired.version + 1L).status
    }

    @Synchronized
    fun applyIntent(intent: String, version: Long): DetectorIntentApplication {
        val normalized = intent.uppercase()
        if (normalized != "ARMED" && normalized != "DISARMED") {
            return DetectorIntentApplication(false, snapshot(), "invalid-intent")
        }
        if (version < desired.version) {
            return DetectorIntentApplication(false, snapshot(), "stale-intent-version")
        }
        if (version == desired.version) {
            return if (normalized == desired.intent) {
                authorityConfirmed = true
                startupFailure = null
                DetectorIntentApplication(true, snapshot(), "intent-already-applied")
            } else {
                DetectorIntentApplication(false, snapshot(), "intent-version-conflict")
            }
        }
        val next = PersistedDetectorIntent(normalized, version)
        if (!store.save(next)) {
            return DetectorIntentApplication(false, snapshot(), "intent-persist-failed")
        }
        desired = next
        authorityConfirmed = true
        startupFailure = null
        return DetectorIntentApplication(true, snapshot())
    }

    fun snapshot(): VisibleDetectorStatus {
        val current = desired
        startupFailure?.let {
            return VisibleDetectorStatus("DISARMED", "BLOCKED", "UNHEALTHY", it, current.version)
        }
        if (current.intent != "ARMED") return VisibleDetectorStatus.disarmed(current.version)
        if (!authorityConfirmed) {
            return VisibleDetectorStatus(
                "ARMED", "BLOCKED", "UNHEALTHY", "authority-reconciliation-required", current.version,
            )
        }
        val gates = armingHealth().copy(detectorArmRequested = true)
        val reason = gates.firstFailureReason()
        return if (reason == null) {
            VisibleDetectorStatus("ARMED", "ARMED", "HEALTHY", intentVersion = current.version)
        } else {
            VisibleDetectorStatus("ARMED", "BLOCKED", "UNHEALTHY", reason, current.version)
        }
    }
}

private fun CoordinatorArmingHealth.firstFailureReason(): String? = when {
    !featureEnabled -> "feature-disabled"
    !visibleSourceActive -> "visible-source-inactive"
    !sourceGenerationValid -> "source-generation-invalid"
    !detectorHealthy -> "detector-unhealthy"
    !storeHealthy -> "store-unhealthy"
    !outboxHealthy -> "outbox-unhealthy"
    !missionAdaptersHealthy -> "mission-adapter-unhealthy"
    !safetyAdaptersHealthy -> "safety-adapter-unhealthy"
    manualHoldActive -> "manual-hold-active"
    competingOwnerActive -> "competing-owner-active"
    else -> null
}
