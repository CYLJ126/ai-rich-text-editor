package com.arte.core.serialize;

import com.arte.core.utils.ContentDigests;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CanonicalJsonTest {

    @Test
    void normalizationMatchesPreMigrationV1Vector() {
        String input = "{\"z\":[3.000,true,null,{\"中\":\"😀\"}],\"a\":12345678901234567890123456789.1234500}";
        String canonical = "{\"a\":12345678901234567890123456789.12345,\"z\":[3,true,null,{\"中\":\"😀\"}]}";
        assertEquals(canonical, CanonicalJson.normalize(input));
        assertEquals("fb99355b53262432a9d87ecebcd0bbd7a1fa2d566cbbb8ed2b818d7d175f473d", ContentDigests.sha256(canonical));
        assertEquals(canonical, CanonicalJson.normalize(canonical));
    }

    @Test
    void objectOrderAndDecimalScaleDoNotChangeDigestButArrayOrderDoes() {
        var first = CanonicalJson.fields("z", List.of(1, 2), "a", new BigDecimal("1.2300"));
        var second = Map.of("a", new BigDecimal("1.23"), "z", List.of(1, 2));
        assertEquals("{\"a\":1.23,\"z\":[1,2]}", CanonicalJson.encode(first));
        assertEquals(CanonicalJson.digest(first), CanonicalJson.digest(second));
        assertNotEquals(CanonicalJson.digest(first), CanonicalJson.digest(Map.of("a", new BigDecimal("1.23"), "z", List.of(2, 1))));
    }

    @Test
    void utf8HashesAndLengthPrefixedKeysMatchPreMigrationV1Vectors() {
        assertEquals("e973a1c1b41c5c9f4fbac31fcc311536dfffd003bfb914580455170a599953fa", ContentDigests.sha256("中文😀"));
        assertEquals("bacf4b99eab6a34d0c749c27d76695cb66aa02fdb6a4c0e1d1a059349b8c9bf4", ContentDigests.key("租户", "workspace😀", "a:b", ""));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ContentDigests.key());
        assertNotEquals(ContentDigests.key("ab", "c"), ContentDigests.key("a", "bc"));
        assertNotEquals(ContentDigests.key("a:b", "c"), ContentDigests.key("a", "b:c"));
        assertNotEquals(ContentDigests.key(), ContentDigests.key(""));
    }
}
