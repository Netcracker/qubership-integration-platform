package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentService;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.library.constants.ConfigurationPropertiesConstants.*;
import static org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.XmlBeanConstants.*;

@Component
public class ServiceCallBeansBuilder implements ElementBeansBuilder {
    private final SystemService systemService;
    private final EnvironmentService environmentService;

    @Autowired
    public ServiceCallBeansBuilder(
            SystemService systemService,
            EnvironmentService environmentService
    ) {
        this.systemService = systemService;
        this.environmentService = environmentService;
    }

    @Override
    public boolean applicableTo(ChainElement element) {
        return CamelNames.SERVICE_CALL_COMPONENT.equals(element.getType());
    }

    @Override
    public void build(XMLStreamWriter2 streamWriter, ChainElement element, SourceBuilderContext context) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", "ServiceCallInfo-" + element.getId());
        streamWriter.writeAttribute("type", "org.qubership.integration.platform.engine.metadata.ServiceCallInfo");

        streamWriter.writeStartElement("properties");

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "retryCount");
        streamWriter.writeAttribute(ATTR_VALUE, element.getPropertyAsString(SERVICE_CALL_RETRY_COUNT));

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "retryDelay");
        streamWriter.writeAttribute(ATTR_VALUE, element.getPropertyAsString(SERVICE_CALL_RETRY_DELAY));

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "protocol");
        streamWriter.writeAttribute(ATTR_VALUE, element.getPropertyAsString(CamelNames.OPERATION_PROTOCOL_TYPE_PROP));

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "specificationId");
        streamWriter.writeAttribute(ATTR_VALUE, element.getPropertyAsString(CamelOptions.SPECIFICATION_ID));

        if (IntegrationSystemType.EXTERNAL.name().equals(element.getProperty(CamelOptions.SYSTEM_TYPE))) {
            String systemId = (String) element.getProperty(CamelOptions.SYSTEM_ID);
            if (StringUtils.isNotEmpty(systemId)) {
                IntegrationSystem system = systemService.findById(systemId);

                streamWriter.writeEmptyElement(XML_PROPERTY);
                streamWriter.writeAttribute(ATTR_KEY, "externalServiceName");
                streamWriter.writeAttribute(ATTR_VALUE, system.getName());

                String activeEnvironmentId = system.getActiveEnvironmentId();
                if (StringUtils.isNotEmpty(activeEnvironmentId)) {
                    Environment env = environmentService.getByIdForSystem(systemId, activeEnvironmentId);

                    streamWriter.writeEmptyElement(XML_PROPERTY);
                    streamWriter.writeAttribute(ATTR_KEY, "externalServiceAddress");
                    streamWriter.writeAttribute(ATTR_VALUE, env.getAddress());

                    streamWriter.writeEmptyElement(XML_PROPERTY);
                    streamWriter.writeAttribute(ATTR_KEY, "externalServiceEnvironmentName");
                    streamWriter.writeAttribute(ATTR_VALUE, env.getName());
                }
            }
        }

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}
