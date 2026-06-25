package org.qubership.integration.platform.engine.service;

import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MetricTagsHelperTest {

    private MetricTagsHelper metricTagsHelper;

    @Mock
    private EngineInfo engineInfo;

    @BeforeEach
    void setUp() {
        metricTagsHelper = new MetricTagsHelper(engineInfo);
    }

    @Test
    void shouldBuildMetricTagsWithChainElementAndEngineDomain() {
        when(engineInfo.getDomain()).thenReturn("customer-domain");

        Collection<Tag> result = metricTagsHelper.buildMetricTags(
                "chain-id",
                "Chain name",
                "element-id",
                "Element name");

        assertEquals(List.of(
                Tag.of(CHAIN_ID_TAG, "chain-id"),
                Tag.of(CHAIN_NAME_TAG, "Chain name"),
                Tag.of(ELEMENT_ID_TAG, "element-id"),
                Tag.of(ELEMENT_NAME_TAG, "Element name"),
                Tag.of(ENGINE_DOMAIN_TAG, "customer-domain")
        ), List.copyOf(result));
    }
}
