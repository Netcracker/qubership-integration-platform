package org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements.ElementTemplateUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpTriggerDDSConverterTest {

    // The detailed design used to answer 400 with ClassCastException on this string.
    @Test
    void readsExternalRouteStoredAsString() {
        RoutePrefixProvider routePrefixProvider = mock(RoutePrefixProvider.class);
        when(routePrefixProvider.getRoutePrefix(false)).thenReturn("/routes/");
        HttpTriggerDDSConverter converter =
                new HttpTriggerDDSConverter(mock(ElementTemplateUtils.class), new ObjectMapper(), routePrefixProvider);
        ChainElement trigger = ChainElement.builder().id("t").type("http-trigger")
                .properties(Map.of("contextPath", "orders", "externalRoute", "false"))
                .build();

        assertEquals("/routes/orders", converter.convert(trigger).getProperties().get("endpointUri"));
    }
}
