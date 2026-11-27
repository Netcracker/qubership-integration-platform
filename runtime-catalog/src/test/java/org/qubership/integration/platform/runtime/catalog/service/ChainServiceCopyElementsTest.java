package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class ChainServiceCopyElementsTest {
    @Mock
    PropertyPlaceholderService propertyPlaceholderService;
    @InjectMocks
    ChainService chainService;

    @Test
    void triggerReferencesFollowTheCopiedTriggerAndOthersStay() {
        ChainElement http = element("http", "http-trigger", Map.of(
                "chainFailureHandlerContainer", new HashMap<>(Map.of("elementId", "trigger")),
                "idempotency", new HashMap<>(Map.of(
                        "chainTriggerParameters", new HashMap<>(Map.of("triggerElementId", "trigger"))))));
        ChainElement ownCall = element("own-call", "chain-call-2", Map.of("elementId", "trigger"));
        ChainElement foreignCall = element("foreign-call", "chain-call-2", Map.of("elementId", "foreign"));
        ChainElement trigger = element("trigger", "chain-trigger-2", Map.of());

        chainService.copyElements(List.of(http, ownCall, foreignCall, trigger), "copy");

        Map<?, ?> failureHandler = (Map<?, ?>) http.getProperties().get("chainFailureHandlerContainer");
        Map<?, ?> idempotency = (Map<?, ?>) http.getProperties().get("idempotency");
        Map<?, ?> chainTriggerParameters = (Map<?, ?>) idempotency.get("chainTriggerParameters");
        assertEquals(trigger.getId(), failureHandler.get("elementId"));
        assertEquals(trigger.getId(), chainTriggerParameters.get("triggerElementId"));
        assertEquals(trigger.getId(), ownCall.getProperty("elementId"));
        assertEquals("foreign", foreignCall.getProperty("elementId"));
    }

    private static ChainElement element(String id, String type, Map<String, Object> properties) {
        return ChainElement.builder().id(id).type(type).properties(new HashMap<>(properties)).build();
    }
}
