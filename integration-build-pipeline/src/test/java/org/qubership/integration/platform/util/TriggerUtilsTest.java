package org.qubership.integration.platform.util;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.chain.impl.ElementBuilder;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.library.constants.CamelOptions;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.library.constants.CamelNames.HTTP_TRIGGER_COMPONENT;

class TriggerUtilsTest {

    private static Element httpTrigger(Map<String, Object> properties) {
        return ElementBuilder.createNew().id("trigger").type(HTTP_TRIGGER_COMPONENT).properties(properties).build();
    }

    // A deployment used to fail with ClassCastException on these strings.
    @Test
    void readsRouteFlagsStoredAsStrings() {
        Element trigger = httpTrigger(Map.of(
                CamelOptions.IS_EXTERNAL_ROUTE, "false",
                CamelOptions.IS_PRIVATE_ROUTE, "true"));

        assertFalse(TriggerUtils.isExternalHttpTrigger(trigger));
        assertTrue(TriggerUtils.isPrivateHttpTrigger(trigger));
    }
}
