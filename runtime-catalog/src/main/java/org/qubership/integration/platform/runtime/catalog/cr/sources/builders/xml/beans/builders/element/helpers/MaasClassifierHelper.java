package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element.helpers;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.model.constant.ConnectionSourceType;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentSourceType;
import org.qubership.integration.platform.runtime.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.MaasPropertiesUtils;
import org.qubership.integration.platform.runtime.catalog.util.ElementUtils;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.stream.XMLStreamException;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.MAAS_CLASSIFIER_NAME_PROP;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.DEFAULT_VHOST_CLASSIFIER_NAME;

@Component
public class MaasClassifierHelper {

    /** Element types whose MaaS classifier scope is the active environment rather than the element itself. */
    private static final Set<String> ENVIRONMENT_SCOPED_TYPES =
            Set.of(CamelNames.ASYNC_API_TRIGGER_COMPONENT, CamelNames.SERVICE_CALL_COMPONENT);

    /**
     * The classifier name a service-call or async-api-trigger deploys with. It lives on the element, put
     * there from the operation's {@code x-maas-classifier-name}, and that is the only place to read it:
     * the deploy-property builders, {@code OperationElementPropertiesVerifier}, the design generators and
     * the chain filter all read the same element property, and nothing in the platform ever writes
     * {@code maas.classifier.name} onto an environment. The scope around the name still comes from the
     * environment — see {@link #writeMaasClassifierInfoBean}.
     */
    public String getMaasClassifierForServiceCallOrAsyncApiElement(ChainElement element) {
        return Optional.ofNullable(ElementUtils.extractOperationAsyncProperties(element.getProperties())
                        .get(MAAS_CLASSIFIER_NAME_PROP))
                .map(Object::toString)
                .orElse("");
    }

    public String getMaasClassifierForKafkaElement(ChainElement element) {
        String sourceType = element.getPropertyAsString(CamelOptions.CONNECTION_SOURCE_TYPE_PROP);
        return ConnectionSourceType.MAAS.toString().equals(sourceType)
                || EnvironmentSourceType.MAAS_BY_CLASSIFIER.toString().equals(sourceType)
                ? Optional.ofNullable(element.getPropertyAsString(CamelOptions.MAAS_TOPICS_CLASSIFIER_NAME_PROP))
                  .orElse("")
                : "";
    }

    public String getMaasClassifierForAmpqElement(ChainElement element) {
        String sourceType = element.getPropertyAsString(CamelOptions.CONNECTION_SOURCE_TYPE_PROP);
        return ConnectionSourceType.MAAS.toString().equals(sourceType)
                || EnvironmentSourceType.MAAS_BY_CLASSIFIER.toString().equals(sourceType)
                ? Optional.ofNullable(element.getPropertyAsString(CamelOptions.MAAS_VHOST_CLASSIFIER_NAME_PROP))
                  .orElse(DEFAULT_VHOST_CLASSIFIER_NAME)
                : "";
    }

    // Writes the MaasClassifierInfo bean, reading namespace/tenantId/tenantEnabled from whichever scope the
    // element type carries: async service-call / async-api-trigger read the environment's dotted keys, while
    // standalone kafka/rabbit elements read their own properties. Defaulting is left to the writer below.
    public void writeMaasClassifierInfoBean(
            XMLStreamWriter2 streamWriter,
            ChainElement element,
            String protocol,
            String classifier
    ) throws XMLStreamException {
        boolean async = ENVIRONMENT_SCOPED_TYPES.contains(element.getType());
        Map<String, Object> props = async
                ? Optional.ofNullable(element.getEnvironment()).map(ServiceEnvironment::getProperties).orElse(null)
                : element.getProperties();
        String namespaceKey = async ? CamelNames.MAAS_CLASSIFIER_NAMESPACE_PROP : CamelOptions.MAAS_CLASSIFIER_NAMESPACE;
        String tenantIdKey = async ? CamelNames.MAAS_CLASSIFIER_TENANT_ID_CAMEL_NAME : CamelOptions.MAAS_CLASSIFIER_TENANT_ID;
        String tenantEnabledKey = async
                ? CamelNames.MAAS_CLASSIFIER_TENANT_ENABLED_CAMEL_NAME
                : CamelOptions.MAAS_CLASSIFIER_TENANT_ENABLED;

        addMaasClassifierInfoBean(
                streamWriter,
                element,
                protocol,
                classifier,
                MaasPropertiesUtils.scopeValue(props, namespaceKey, null),
                MaasPropertiesUtils.scopeValue(props, tenantIdKey, null),
                MaasPropertiesUtils.scopeValue(props, tenantEnabledKey, null)
        );
    }

    private void addMaasClassifierInfoBean(
            XMLStreamWriter2 streamWriter,
            ChainElement element,
            String protocol,
            String classifier,
            String namespace,
            String tenantId,
            String tenantEnabled
    ) throws XMLStreamException {
        // Woodstox writeAttribute throws NPE on a null value; a MAAS_BY_CLASSIFIER environment may
        // legitimately omit namespace/tenantId (deploy to the current namespace), so coalesce nulls.
        namespace = namespace == null ? "" : namespace;
        tenantId = tenantId == null ? "" : tenantId;
        tenantEnabled = tenantEnabled == null ? "false" : tenantEnabled;

        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", "MaasClassifierInfo-" + element.getId());
        streamWriter.writeAttribute("type", "org.qubership.integration.platform.engine.metadata.MaasClassifierInfo");

        streamWriter.writeStartElement("properties");

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "elementId");
        streamWriter.writeAttribute("value", element.getOriginalId());

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
