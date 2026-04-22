package org.qubership.integration.platform.engine.util.builders;

import io.micrometer.core.instrument.Tag;
import jakarta.enterprise.inject.spi.CDI;
import org.qubership.integration.platform.engine.camel.components.servlet.ServletTagsProvider;
import org.qubership.integration.platform.engine.service.MetricTagsHelper;

import java.util.Collection;

public class ServletTagsProviderBuilder {
    private String cId;
    private String cName;
    private String eId;
    private String eName;

    public ServletTagsProviderBuilder() {
        cId = "";
        cName = "";
        eId = "";
        eName = "";
    }

    public ServletTagsProviderBuilder chainId(String value) {
        cId = value;
        return this;
    }

    public ServletTagsProviderBuilder chainName(String value) {
        cName = value;
        return this;
    }

    public ServletTagsProviderBuilder elementId(String value) {
        eId = value;
        return this;
    }

    public ServletTagsProviderBuilder elementName(String value) {
        eName = value;
        return this;
    }

    public ServletTagsProvider build() {
        MetricTagsHelper metricTagsHelper = CDI.current().select(MetricTagsHelper.class).get();
        Collection<Tag> tags = metricTagsHelper.buildMetricTags(cId, cName, eId, eName);
        return new ServletTagsProvider(tags);
    }
}
