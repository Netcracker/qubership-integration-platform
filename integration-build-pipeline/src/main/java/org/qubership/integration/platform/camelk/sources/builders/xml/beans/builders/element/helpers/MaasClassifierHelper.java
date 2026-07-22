package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element.helpers;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.library.constants.ConnectionSourceType;
import org.qubership.integration.platform.util.ElementUtils;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import javax.xml.stream.XMLStreamException;

import static org.qubership.integration.platform.library.constants.CamelNames.MAAS_CLASSIFIER_NAME_PROP;
import static org.qubership.integration.platform.library.constants.CamelNames.OPERATION_ASYNC_PROPERTIES;
import static org.qubership.integration.platform.library.constants.CamelOptions.DEFAULT_VHOST_CLASSIFIER_NAME;
import static org.qubership.integration.platform.library.constants.CamelOptions.SYSTEM_ID;

@Component
public class MaasClassifierHelper {
    public String getMaasClassifierForServiceCallOrAsyncApiElement(
        Element element,
        IntegrationServiceCatalog integrationServiceCatalog
    ) {
        return Optional.ofNullable(ElementUtils.getPropertyAsString(element, OPERATION_ASYNC_PROPERTIES))
                .map(Map.class::cast)
                .map(m -> m.get(MAAS_CLASSIFIER_NAME_PROP))
                .or(() -> Optional.ofNullable(ElementUtils.getPropertyAsString(element, SYSTEM_ID))
                        .flatMap(integrationServiceCatalog::findById)
                        .flatMap(IntegrationService::getActiveEnvironment)
                        .map(env -> env.getProperties().get(MAAS_CLASSIFIER_NAME_PROP)))
                .map(Object::toString)
                .orElse("");
    }

    public String getMaasClassifierForKafkaElement(Element element) {
        String sourceType = ElementUtils.getPropertyAsString(element, CamelOptions.CONNECTION_SOURCE_TYPE_PROP);
        return ConnectionSourceType.MAAS.toString().equals(sourceType)
                || EnvironmentSourceType.MAAS_BY_CLASSIFIER.toString().equals(sourceType)
                ? Optional.ofNullable(ElementUtils.getPropertyAsString(element, CamelOptions.MAAS_TOPICS_CLASSIFIER_NAME_PROP))
                  .orElse("")
                : "";
    }

    public String getMaasClassifierForAmpqElement(Element element) {
        String sourceType = ElementUtils.getPropertyAsString(element, CamelOptions.CONNECTION_SOURCE_TYPE_PROP);
        return ConnectionSourceType.MAAS.toString().equals(sourceType)
                || EnvironmentSourceType.MAAS_BY_CLASSIFIER.toString().equals(sourceType)
                ? Optional.ofNullable(ElementUtils.getPropertyAsString(element, CamelOptions.MAAS_VHOST_CLASSIFIER_NAME_PROP))
                  .orElse(DEFAULT_VHOST_CLASSIFIER_NAME)
                : "";
    }

    public void addMaasClassifierInfoBean(
            XMLStreamWriter2 streamWriter,
            Element element,
            String protocol,
            String classifier,
            String namespace,
            String tenantId,
            String tenantEnabled
    ) throws XMLStreamException {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", "MaasClassifierInfo-" + element.getId());
        streamWriter.writeAttribute("type", "org.qubership.integration.platform.engine.metadata.MaasClassifierInfo");

        streamWriter.writeStartElement("properties");

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "elementId");
        streamWriter.writeAttribute("value", element.getOriginalId().orElse(element.getId()));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "protocol");
        streamWriter.writeAttribute("value", protocol);

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "classifier");
        streamWriter.writeAttribute("value", classifier);

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "namespace");
        streamWriter.writeAttribute("value", namespace);

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "tenantId");
        streamWriter.writeAttribute("value", tenantId);

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "tenantEnabled");
        streamWriter.writeAttribute("value", tenantEnabled);

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();

    }
}
