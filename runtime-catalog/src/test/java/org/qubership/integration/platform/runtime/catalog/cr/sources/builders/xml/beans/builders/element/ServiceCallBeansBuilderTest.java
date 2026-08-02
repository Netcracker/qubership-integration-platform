package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentService;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.qubership.integration.platform.runtime.catalog.consul.ConfigurationPropertiesConstants.SERVICE_CALL_RETRY_COUNT;
import static org.qubership.integration.platform.runtime.catalog.consul.ConfigurationPropertiesConstants.SERVICE_CALL_RETRY_DELAY;

/**
 * Regression for the Camel-K {@code POST /v1/cr} crash: a bound service-call element used to write the
 * {@code specificationId} value via {@code writeEmptyElement("value", ...)} (Woodstox reads it as an
 * unbound namespace URI and throws), instead of {@code writeAttribute}. The build must now emit a
 * {@code specificationId} attribute and not throw.
 */
class ServiceCallBeansBuilderTest {

    private static final String SPECIFICATION_ID = "spec-123";

    private ChainElement serviceCallElement() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(SERVICE_CALL_RETRY_COUNT, "0");
        properties.put(SERVICE_CALL_RETRY_DELAY, "0");
        properties.put(CamelNames.OPERATION_PROTOCOL_TYPE_PROP, CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA);
        properties.put(CamelOptions.SPECIFICATION_ID, SPECIFICATION_ID);
        return ChainElement.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .type(CamelNames.SERVICE_CALL_COMPONENT)
                .properties(properties)
                .build();
    }

    private static ServiceCallBeansBuilder builder() {
        return new ServiceCallBeansBuilder(mock(SystemService.class), mock(EnvironmentService.class));
    }

    @Test
    void boundServiceCallEmitsSpecificationIdAttributeWithoutCrashing() {
        String xml = assertDoesNotThrow(() -> BeanXmlTestSupport.buildXml(builder(), serviceCallElement()));
        assertTrue(xml.contains("<property key=\"specificationId\" value=\"" + SPECIFICATION_ID + "\"/>"),
                () -> "specificationId must be an attribute value, was: " + xml);
    }
}
