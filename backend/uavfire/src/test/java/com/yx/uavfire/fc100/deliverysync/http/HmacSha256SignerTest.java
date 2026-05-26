package com.yx.uavfire.fc100.deliverysync.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HmacSha256SignerTest {

    @Test
    void signsWebApiRequestAsLowercaseHex() {
        String signature = HmacSha256Signer.sign("AK", "SK", "GET", "1700000000000", "abc123");

        assertEquals("e611a0882dac0fd20cee129b627eaaefb51cdb18cd149a2167e2d69cd00dde77", signature);
    }
}
