package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the Camel-K {@code POST /v1/cr} crash on an SDS-trigger element: the {@code cron} value used
 * to be written with {@code writeEmptyElement("value", ...)} (Woodstox reads the first argument as an unbound
 * namespace URI and throws) instead of {@code writeAttribute}. The build must now emit {@code cron} as an
 * attribute value and not throw. Twin of the {@code ServiceCallBeansBuilder} fix.
 */
class SdsTriggerInfoBeanBuilderTest {

    private static final String CRON = "0 0 * * * ?";

    private ChainElement sdsTriggerElement() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(CamelOptions.SDS_JOB_ID, "job-42");
        properties.put("prohibitParallelRun", "true");
        properties.put("parallelRunTimeout", "60");
        properties.put("cron", CRON);
        return ChainElement.builder()
                .id("22222222-2222-2222-2222-222222222222")
                .type(CamelNames.SDS_TRIGGER_COMPONENT)
                .properties(properties)
                .build();
    }

    @Test
    void boundSdsTriggerEmitsCronAttributeWithoutCrashing() {
        String xml = assertDoesNotThrow(() -> BeanXmlTestSupport.buildXml(new SdsTriggerInfoBeanBuilder(), sdsTriggerElement()));
        assertTrue(xml.contains("<property key=\"cron\" value=\"" + CRON + "\"/>"),
                () -> "cron must be an attribute value, was: " + xml);
    }
}
