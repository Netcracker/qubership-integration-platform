package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.camelk.sources.SourceBuilderContext;
import org.qubership.integration.platform.camelk.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element.helpers.MaasClassifierHelper;
import org.qubership.integration.platform.chain.model.Chain;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.util.ElementUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import javax.xml.stream.XMLStreamException;

import static org.qubership.integration.platform.library.constants.CamelNames.*;
import static org.qubership.integration.platform.library.constants.CamelNames.OPERATION_PROTOCOL_TYPE_PROP;
import static org.qubership.integration.platform.library.constants.CamelOptions.*;

@Component
public class KafkaBeansBinder implements ElementBeansBuilder {
    private static final Set<String> KAFKA_ELEMENTS = Set.of(
            CamelNames.KAFKA_SENDER_COMPONENT,
            CamelNames.KAFKA_SENDER_2_COMPONENT,
            CamelNames.KAFKA_TRIGGER_COMPONENT,
            CamelNames.KAFKA_TRIGGER_2_COMPONENT
    );

    private final MaasClassifierHelper maasClassifierHelper;

    @Autowired
    public KafkaBeansBinder(
            MaasClassifierHelper maasClassifierHelper
    ) {
        this.maasClassifierHelper = maasClassifierHelper;
    }

    @Override
    public boolean applicableTo(Element element) {
        String type = element.getType();
        return KAFKA_ELEMENTS.contains(type)
                || (
                Set.of(ASYNC_API_TRIGGER_COMPONENT, SERVICE_CALL_COMPONENT).contains(type)
                        && OPERATION_PROTOCOL_TYPE_KAFKA.equals(
                        element.getProperties().get(OPERATION_PROTOCOL_TYPE_PROP))
        );
    }

    @Override
    public void build(XMLStreamWriter2 streamWriter, Element element, SourceBuilderContext context) throws Exception {
        addKafkaClientFactoryBean(streamWriter, element.getId(), element, context);
        addKafkaClientFactoryBean(streamWriter, element.getId() + "-v2", element, context);

        String maasClassifier = getMaasClassifier(element, context);
        if (StringUtils.isNotBlank(maasClassifier)) {
            addMaasClassifierInfoBean(streamWriter, element, context);
        }
    }

    private void addKafkaClientFactoryBean(
            XMLStreamWriter2 streamWriter,
            String name,
            Element element,
            SourceBuilderContext context
    ) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", name);
        streamWriter.writeAttribute("type", "org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory");
        streamWriter.writeAttribute("builderClass", "org.qubership.integration.platform.engine.util.builders.KafkaClientFactoryBuilder");
        streamWriter.writeAttribute("builderMethod", "build");

        streamWriter.writeStartElement("properties");

        Chain chain = element.getSnapshot().map(Snapshot::getChain)
            .orElseThrow(() -> new RuntimeException("Failed to get chain from snapshot"));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "chainId");
        streamWriter.writeAttribute("value", chain.getId());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "chainName");
        streamWriter.writeAttribute("value", chain.getName());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "elementId");
        streamWriter.writeAttribute("value", element.getOriginalId().orElse(element.getId()));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "elementName");
        streamWriter.writeAttribute("value", element.getName());

        String maasClassifier = getMaasClassifier(element, context);
        if (StringUtils.isNotBlank(maasClassifier)) {
            streamWriter.writeEmptyElement("property");
            streamWriter.writeAttribute("key", "maasClassifier");
            streamWriter.writeAttribute("value", maasClassifier);
        }

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }

    private void addMaasClassifierInfoBean(
            XMLStreamWriter2 streamWriter,
            Element element,
            SourceBuilderContext context
    ) throws XMLStreamException {
        String maasClassifier = getMaasClassifier(element, context);

        String namespace;
        String tenantId;
        String tenantEnabled;

        if (KAFKA_ELEMENTS.contains(element.getType())) {
            namespace = Optional.ofNullable(element.getProperties().get(MAAS_CLASSIFIER_NAMESPACE))
                    .map(Object::toString).orElse(null);
            tenantId = Optional.ofNullable(element.getProperties().get(MAAS_CLASSIFIER_TENANT_ID))
                    .map(Object::toString).orElse(null);
            tenantEnabled = Optional.ofNullable(element.getProperties().get(MAAS_CLASSIFIER_TENANT_ENABLED))
                    .map(Object::toString).orElse("false");
        } else { // Async API Trigger and Service Call elements
            namespace = String.valueOf(ElementUtils.extractOperationAsyncProperties(element.getProperties())
                    .get(CamelNames.MAAS_CLASSIFIER_NAMESPACE_PROP));
            tenantId = String.valueOf(ElementUtils.extractOperationAsyncProperties(element.getProperties())
                    .get(CamelNames.MAAS_CLASSIFIER_TENANT_ID_CAMEL_NAME));
            tenantEnabled = String.valueOf(ElementUtils.extractOperationAsyncProperties(element.getProperties())
                    .get(CamelNames.MAAS_CLASSIFIER_TENANT_ENABLED_CAMEL_NAME));
        }
        maasClassifierHelper.addMaasClassifierInfoBean(
                streamWriter,
                element,
                OPERATION_PROTOCOL_TYPE_AMQP,
                maasClassifier,
                namespace,
                tenantId,
                tenantEnabled
        );
    }

    private String getMaasClassifier(Element element, SourceBuilderContext context) {
        return KAFKA_ELEMENTS.contains(element.getType())
                ? maasClassifierHelper.getMaasClassifierForKafkaElement(element)
                : maasClassifierHelper.getMaasClassifierForServiceCallOrAsyncApiElement(
                        element, context.getIntegrationServiceCatalog());
    }
}
