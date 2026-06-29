package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.camelk.sources.SourceBuilderContext;
import org.qubership.integration.platform.camelk.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.chain.model.ServiceEnvironment;
import org.qubership.integration.platform.chain.model.ServiceType;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.util.ElementUtils;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static org.qubership.integration.platform.library.constants.ConfigurationPropertiesConstants.*;

@Component
public class ServiceCallBeansBuilder implements ElementBeansBuilder {
    @Override
    public boolean applicableTo(Element element) {
        return CamelNames.SERVICE_CALL_COMPONENT.equals(element.getType());
    }

    @Override
    public void build(XMLStreamWriter2 streamWriter, Element element, SourceBuilderContext context) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", "ServiceCallInfo-" + element.getId());
        streamWriter.writeAttribute("type", "org.qubership.integration.platform.engine.metadata.ServiceCallInfo");

        streamWriter.writeStartElement("properties");

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "retryCount");
        streamWriter.writeAttribute("value", ElementUtils.getPropertyAsString(element, SERVICE_CALL_RETRY_COUNT));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "retryDelay");
        streamWriter.writeAttribute("value", ElementUtils.getPropertyAsString(element, SERVICE_CALL_RETRY_DELAY));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "protocol");
        streamWriter.writeAttribute("value", ElementUtils.getPropertyAsString(element, CamelNames.OPERATION_PROTOCOL_TYPE_PROP));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "specificationId");
        streamWriter.writeEmptyElement("value", ElementUtils.getPropertyAsString(element, CamelOptions.SPECIFICATION_ID));

        if (ServiceType.EXTERNAL.name().equals(element.getProperties().get(CamelOptions.SYSTEM_TYPE))) {
            String serviceId = ElementUtils.getPropertyAsString(element, CamelOptions.SYSTEM_ID);
            if (StringUtils.isNotEmpty(serviceId)) {
                IntegrationService service = context.getIntegrationServiceCatalog()
                    .findById(serviceId)
                    .orElseThrow(() -> new RuntimeException("Service not found: " + serviceId));

                streamWriter.writeEmptyElement("property");
                streamWriter.writeAttribute("key", "externalServiceName");
                streamWriter.writeAttribute("value", service.getName());

                Optional<ServiceEnvironment> maybeEnvironment = service.getActiveEnvironment();
                if (maybeEnvironment.isPresent()) {
                    ServiceEnvironment env = maybeEnvironment.get();

                    streamWriter.writeEmptyElement("property");
                    streamWriter.writeAttribute("key", "externalServiceAddress");
                    streamWriter.writeAttribute("value", env.getAddress());

                    streamWriter.writeEmptyElement("property");
                    streamWriter.writeAttribute("key", "externalServiceEnvironmentName");
                    streamWriter.writeAttribute("value", env.getName());
                }
            }
        }

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}
