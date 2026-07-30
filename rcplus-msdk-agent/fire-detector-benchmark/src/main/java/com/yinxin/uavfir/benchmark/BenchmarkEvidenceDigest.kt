package com.yinxin.uavfir.benchmark

import java.math.BigDecimal
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal object BenchmarkEvidenceDigest {
    fun sha256(value: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalJson(value).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun canonicalNumber(value: Number): String {
        when (value) {
            is Double -> check(value.isFinite()) { "Non-finite JSON number is not allowed" }
            is Float -> check(value.isFinite()) { "Non-finite JSON number is not allowed" }
        }
        return runCatching { BigDecimal(value.toString()).stripTrailingZeros().toPlainString() }
            .getOrElse { error("Invalid JSON number") }
            .let { if (it == "-0") "0" else it }
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted()
            .joinToString(prefix = "{", postfix = "}") { key ->
                "${key.length}:$key=${canonicalJson(value.get(key))}"
            }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") {
            canonicalJson(value.get(it))
        }
        is Boolean -> "b:$value"
        is Number -> "n:${canonicalNumber(value)}"
        JSONObject.NULL, null -> "null"
        else -> "s:${value.toString().length}:$value"
    }
}
