package com.yinxin.uavfir.firedetection.store

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.yinxin.uavfir.firedetection.FireSessionState
import java.io.StringReader
import java.math.BigDecimal

internal data class CanonicalReport(
    val payload: String,
    val sha256: String,
)

internal object CanonicalFireReport {
    fun initial(record: InitialConfirmationRecord): CanonicalReport {
        val root = parseAndValidateIdentity(
            payload = record.payload,
            eventId = record.request.eventId,
            sessionId = record.request.sessionId,
            sequence = 1,
            eventTimestampWallMillis = record.eventTimestampWallMillis,
            state = FireSessionState.VISUAL_CONFIRMED,
        )
        val confirmation = record.request.confirmation
        requireString(root, "detectionKind", confirmation.kind.name)
        requireNumber(root, "confidence", confirmation.confidence.toString())
        requireString(root, "locationStatus", "LASER_LOCATING")
        requireString(root, "modelVersion", record.modelVersion)
        requireString(root, "modelHash", record.modelHash.lowercase())
        requireString(root, "policyVersion", confirmation.policyVersion)
        requireLong(root, "inputSize", record.inputSize.toLong())
        requireString(root, "runtime", record.runtime)
        validateRoi(root.requiredObject("visibleRoi"), confirmation.roi)
        return canonical(root)
    }

    fun terminal(record: TerminalResultRecord): CanonicalReport {
        val request = record.request
        val root = parseAndValidateIdentity(
            payload = record.payload,
            eventId = request.eventId,
            sessionId = request.sessionId,
            sequence = record.sequence,
            eventTimestampWallMillis = record.eventTimestampWallMillis,
            state = FireSessionState.RESULT_DURABLE,
        )
        requireString(root, "locationStatus", request.locationStatus.name)
        requireString(root, "geoMethod", request.geoMethod.name)
        return canonical(root)
    }

    fun stage(record: StagePersistenceRecord): CanonicalReport =
        canonical(
            parseAndValidateIdentity(
                payload = record.payload,
                eventId = record.eventId,
                sessionId = record.sessionId,
                sequence = record.sequence,
                eventTimestampWallMillis = record.eventTimestampWallMillis,
                state = record.state,
            ),
        )

    fun manualHold(
        eventId: String,
        sessionId: String,
        sequence: Long,
        eventTimestampWallMillis: Long,
    ): CanonicalReport {
        val root = JsonObject().apply {
            addProperty("eventId", eventId)
            addProperty("sessionId", sessionId)
            addProperty("sequence", sequence)
            addProperty("eventTimestamp", eventTimestampWallMillis)
            addProperty("state", FireSessionState.MANUAL_HOLD.name)
            addProperty("reason", "STARTUP_FLIGHT_STATE_UNRECONCILED")
        }
        validateRootSchema(root)
        return canonical(root)
    }

    private fun parseAndValidateIdentity(
        payload: String,
        eventId: String,
        sessionId: String,
        sequence: Long,
        eventTimestampWallMillis: Long,
        state: FireSessionState,
    ): JsonObject {
        val parsed = runCatching { parseStrict(payload) }
            .getOrElse { throw IllegalArgumentException("Outbox payload is malformed JSON", it) }
        require(parsed.isJsonObject) { "Outbox payload must be a JSON object" }
        val root = parsed.asJsonObject
        rejectSensitiveOrBinary(root)
        validateRootSchema(root)
        requireString(root, "eventId", eventId)
        requireString(root, "sessionId", sessionId)
        requireLong(root, "sequence", sequence)
        requireLong(root, "eventTimestamp", eventTimestampWallMillis)
        requireString(root, "state", state.name)
        return root
    }

    private fun parseStrict(payload: String): JsonElement {
        val reader = JsonReader(StringReader(payload)).apply { isLenient = false }
        val parsed = readStrictElement(reader, depth = 0)
        require(reader.peek() == JsonToken.END_DOCUMENT) { "Outbox payload has trailing JSON" }
        return parsed
    }

    private fun readStrictElement(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= MAX_JSON_DEPTH) { "Outbox payload is nested too deeply" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().also { target ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(!target.has(name)) { "Outbox payload contains duplicate field $name" }
                    target.add(name, readStrictElement(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().also { target ->
                reader.beginArray()
                while (reader.hasNext()) {
                    require(target.size() < MAX_JSON_ARRAY_SIZE) { "Outbox JSON array is too large" }
                    target.add(readStrictElement(reader, depth + 1))
                }
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(
                runCatching { BigDecimal(reader.nextString()) }
                    .getOrElse { throw IllegalArgumentException("Outbox payload has an invalid number", it) },
            )
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                JsonNull.INSTANCE
            }
            else -> throw IllegalArgumentException("Outbox payload has an invalid JSON token")
        }
    }

    private fun validateRootSchema(root: JsonObject) {
        requireAllowedKeys(root, ROOT_FIELDS, "report")
        root.optionalString("detectionKind")
        root.optionalNumber("confidence")
        root.optionalObject("visibleRoi")?.let(::validateRoiShape)
        root.optionalString("locationStatus")
        root.optionalString("flightStatus")
        root.optionalString("modelVersion")
        root.optionalString("modelHash")
        root.optionalString("policyVersion")
        root.optionalLong("inputSize")
        root.optionalString("runtime")
        root.optionalObject("aircraft")?.let(::validateAircraft)
        root.optionalNullableNumber("fireLat")
        root.optionalNullableNumber("fireLng")
        root.optionalNullableNumber("fireAlt")
        root.optionalString("geoMethod")
        root.optionalNumber("errorRadiusMeters")
        root.optionalArray("laserSamples")?.let(::validateLaserSamples)
        root.optionalString("reason")?.let {
            require(SAFE_CODE.matches(it)) { "Report reason must be a safe code" }
        }
    }

    private fun validateRoiShape(roi: JsonObject) {
        requireAllowedKeys(roi, ROI_FIELDS, "visibleRoi")
        ROI_FIELDS.forEach { roi.requiredNumber(it) }
    }

    private fun validateRoi(
        roi: JsonObject,
        expected: com.yinxin.uavfir.firedetection.NormalizedRoi,
    ) {
        validateRoiShape(roi)
        requireDecimal(roi, "x", expected.left.toString())
        requireDecimal(roi, "y", expected.top.toString())
        requireDecimal(roi, "width", (expected.right - expected.left).toString())
        requireDecimal(roi, "height", (expected.bottom - expected.top).toString())
    }

    private fun validateAircraft(aircraft: JsonObject) {
        requireAllowedKeys(aircraft, AIRCRAFT_FIELDS, "aircraft")
        AIRCRAFT_FIELDS.forEach { aircraft.requiredNumber(it) }
    }

    private fun validateLaserSamples(samples: JsonArray) {
        require(samples.size() in 1..3) { "laserSamples must contain one to three samples" }
        samples.forEachIndexed { index, element ->
            require(element.isJsonObject) { "laserSamples[$index] must be an object" }
            val sample = element.asJsonObject
            requireAllowedKeys(sample, LASER_SAMPLE_FIELDS, "laserSamples[$index]")
            sample.requiredString("status")
            sample.requiredNumber("rangeMeters")
            sample.requiredNumber("lat")
            sample.requiredNumber("lng")
            sample.requiredNumber("alt")
            sample.requiredLong("eventTimestamp")
        }
    }

    private fun rejectSensitiveOrBinary(element: JsonElement) {
        when {
            element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, value) ->
                require(key.lowercase() !in FORBIDDEN_KEYS) {
                    "Report contains forbidden field $key"
                }
                rejectSensitiveOrBinary(value)
            }
            element.isJsonArray -> element.asJsonArray.forEach(::rejectSensitiveOrBinary)
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                val value = element.asString
                require(!value.contains("data:", ignoreCase = true)) {
                    "Report contains a data URL"
                }
                require(!(value.length >= 128 && BASE64_VALUE.matches(value))) {
                    "Report contains an embedded binary value"
                }
                require(!EMAIL_VALUE.containsMatchIn(value) && !PHONE_VALUE.matches(value)) {
                    "Report contains raw contact data"
                }
            }
        }
    }

    private fun canonical(root: JsonObject): CanonicalReport {
        val payload = canonicalJson(root)
        return CanonicalReport(payload, sha256(payload))
    }

    private fun canonicalJson(element: JsonElement): String = when {
        element is JsonNull || element.isJsonNull -> "null"
        element.isJsonObject -> element.asJsonObject.entrySet()
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                "${GSON.toJson(key)}:${canonicalJson(value)}"
            }
        element.isJsonArray -> element.asJsonArray
            .joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
        element.isJsonPrimitive -> canonicalPrimitive(element.asJsonPrimitive)
        else -> error("Unsupported JSON element")
    }

    private fun canonicalPrimitive(value: JsonPrimitive): String = when {
        value.isBoolean -> if (value.asBoolean) "true" else "false"
        value.isNumber -> normalizeNumber(value.asString)
        value.isString -> GSON.toJson(value.asString)
        else -> throw IllegalArgumentException("Unsupported JSON primitive")
    }

    private fun normalizeNumber(value: String): String {
        val decimal = runCatching { BigDecimal(value) }
            .getOrElse { throw IllegalArgumentException("Invalid JSON number") }
        return if (decimal.compareTo(BigDecimal.ZERO) == 0) {
            "0"
        } else {
            decimal.stripTrailingZeros().toPlainString()
        }
    }

    private fun requireAllowedKeys(root: JsonObject, allowed: Set<String>, context: String) {
        val unknown = root.keySet() - allowed
        require(unknown.isEmpty()) { "$context contains unsupported fields: ${unknown.sorted()}" }
    }

    private fun requireString(root: JsonObject, field: String, expected: String) {
        require(root.requiredString(field) == expected) { "$field does not match the typed record" }
    }

    private fun requireLong(root: JsonObject, field: String, expected: Long) {
        require(root.requiredLong(field) == expected) { "$field does not match the typed record" }
    }

    private fun requireNumber(root: JsonObject, field: String, expected: String) {
        requireDecimal(root, field, expected)
    }

    private fun requireDecimal(root: JsonObject, field: String, expected: String) {
        val actual = root.requiredNumber(field)
        require(actual.compareTo(BigDecimal(expected)) == 0) {
            "$field does not match the typed record"
        }
    }

    private fun JsonObject.required(field: String): JsonElement =
        get(field) ?: throw IllegalArgumentException("Report field $field is required")

    private fun JsonObject.requiredString(field: String): String {
        val value = required(field)
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "Report field $field must be a string"
        }
        return value.asString
    }

    private fun JsonObject.requiredLong(field: String): Long {
        val number = requiredNumber(field)
        return try {
            number.longValueExact()
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("Report field $field must be an integer", error)
        }
    }

    private fun JsonObject.requiredNumber(field: String): BigDecimal {
        val value = required(field)
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            "Report field $field must be a number"
        }
        return runCatching { BigDecimal(value.asString) }
            .getOrElse { throw IllegalArgumentException("Report field $field is not finite", it) }
    }

    private fun JsonObject.requiredObject(field: String): JsonObject {
        val value = required(field)
        require(value.isJsonObject) { "Report field $field must be an object" }
        return value.asJsonObject
    }

    private fun JsonObject.optionalString(field: String): String? =
        get(field)?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString) {
                "Report field $field must be a string"
            }
            it.asString
        }

    private fun JsonObject.optionalLong(field: String): Long? =
        get(field)?.let { requiredLong(field) }

    private fun JsonObject.optionalNumber(field: String): BigDecimal? =
        get(field)?.let { requiredNumber(field) }

    private fun JsonObject.optionalNullableNumber(field: String): BigDecimal? =
        get(field)?.takeUnless { it.isJsonNull }?.let { requiredNumber(field) }

    private fun JsonObject.optionalObject(field: String): JsonObject? =
        get(field)?.let {
            require(it.isJsonObject) { "Report field $field must be an object" }
            it.asJsonObject
        }

    private fun JsonObject.optionalArray(field: String): JsonArray? =
        get(field)?.let {
            require(it.isJsonArray) { "Report field $field must be an array" }
            it.asJsonArray
        }

    private val ROOT_FIELDS = setOf(
        "eventId",
        "sessionId",
        "sequence",
        "eventTimestamp",
        "state",
        "detectionKind",
        "confidence",
        "visibleRoi",
        "locationStatus",
        "flightStatus",
        "modelVersion",
        "modelHash",
        "policyVersion",
        "inputSize",
        "runtime",
        "aircraft",
        "fireLat",
        "fireLng",
        "fireAlt",
        "geoMethod",
        "errorRadiusMeters",
        "laserSamples",
        "reason",
    )
    private val ROI_FIELDS = setOf("x", "y", "width", "height")
    private val AIRCRAFT_FIELDS = setOf("lat", "lng", "alt")
    private val LASER_SAMPLE_FIELDS =
        setOf("status", "rangeMeters", "lat", "lng", "alt", "eventTimestamp")
    private val FORBIDDEN_KEYS = setOf(
        "password",
        "authorization",
        "accesstoken",
        "refreshtoken",
        "token",
        "phone",
        "email",
        "contact",
        "imagebytes",
        "jpegbytes",
        "base64",
        "blob",
        "credential",
        "credentials",
    )
    private val SAFE_CODE = Regex("^[A-Z0-9_:-]{1,128}$")
    private val BASE64_VALUE = Regex("^[A-Za-z0-9+/]+={0,2}$")
    private val EMAIL_VALUE = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val PHONE_VALUE = Regex("""^\+?[0-9][0-9 ()-]{7,}$""")
    private val GSON = Gson()
    private const val MAX_JSON_DEPTH = 16
    private const val MAX_JSON_ARRAY_SIZE = 64
}
