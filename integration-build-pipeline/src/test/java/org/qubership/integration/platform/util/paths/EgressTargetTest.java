package org.qubership.integration.platform.util.paths;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EgressTargetTest {

    @Test
    void parsesHostAndDefaultsHttpsPortWhenAbsent() {
        EgressTarget target = EgressTarget.parse("https://api.example.com/v2/orders");

        assertEquals("https", target.scheme());
        assertEquals("api.example.com", target.host());
        assertEquals(443, target.port());
        assertEquals("/v2/orders", target.path());
        assertTrue(target.isHttps());
    }

    @Test
    void defaultsHttpPortWhenAbsent() {
        EgressTarget target = EgressTarget.parse("http://plain-host/path");

        assertEquals(80, target.port());
        assertFalse(target.isHttps());
    }

    @Test
    void preservesAnExplicitPort() {
        EgressTarget target = EgressTarget.parse("http://internal-host:9090");

        assertEquals(9090, target.port());
    }

    @Test
    void defaultsPathToSlashWhenAbsent() {
        EgressTarget target = EgressTarget.parse("https://host:8443");

        assertEquals("/", target.path());
    }

    @Test
    void preservesAnExplicitPath() {
        EgressTarget target = EgressTarget.parse("https://host/a/b/c");

        assertEquals("/a/b/c", target.path());
    }

    @Test
    void hostResourceNameIsCaseInsensitiveAndConvergesForTheSameHost() {
        String lower = EgressTarget.parse("https://Api.Example.COM/v2").hostResourceName();
        String upper = EgressTarget.parse("https://api.example.com/v9").hostResourceName();

        assertEquals(lower, upper);
    }

    @Test
    void hostResourceNameDiffersForHostsThatSanitizeToTheSameBaseString() {
        String first = EgressTarget.parse("https://a.b.c/x").hostResourceName();
        String second = EgressTarget.parse("https://abc/x").hostResourceName();

        assertNotEquals(first, second);
    }

    @Test
    void hostResourceNameStaysWithinTheKubernetesNameLengthLimit() {
        String longHost = "a".repeat(300) + ".example.com";
        String name = EgressTarget.parse("https://" + longHost + "/x").hostResourceName();

        assertTrue(name.length() <= 63);
    }
}
