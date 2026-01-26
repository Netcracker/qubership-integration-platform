package org.qubership.integration.platform.engine.consul;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ConsulKeyValidatorTest {
    private ConsulKeyValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ConsulKeyValidator();
    }

    @Test
    public void shouldReplatePeriodsInKeyNameWithHyphens() {
        assertEquals("foo-bar-baz", validator.makeKeyValid("foo.bar.baz"));
    }
}
