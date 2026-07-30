package com.yinxin.uavfir.benchmark

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkEvidenceDigestTest {
    @Test
    fun integralDoubleAndIntegerHaveTheSameCanonicalDigest() {
        val fromDouble = BenchmarkEvidenceDigest.sha256(JSONObject().put("p95", 152.0))
        val fromInteger = BenchmarkEvidenceDigest.sha256(JSONObject().put("p95", 152))

        assertEquals(fromInteger, fromDouble)
    }

    @Test(expected = IllegalStateException::class)
    fun nonFiniteNumberIsRejected() {
        BenchmarkEvidenceDigest.canonicalNumber(Double.POSITIVE_INFINITY)
    }
}
