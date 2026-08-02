package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element.helpers.MaasClassifierHelper;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentSourceType;
import org.qubership.integration.platform.runtime.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.MaasPropertiesUtils;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.builders.KafkaElementPropertiesBuilder;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.builders.RabbitMqElementPropertiesBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Verifies that the MaaS classifier scope (namespace/tenantId/tenantEnabled) reaches the compiled bean
 * XML and deploy properties straight from the element's environment, while the classifier name comes
 * from the element's {@code operationAsyncProperties} and from nowhere else. This is the read path that
 * replaced the former {@code SnapshotService} snapshot-build projection.
 *
 * <p>Runs with no Spring context: bean XML through a real Woodstox writer, deploy properties through the
 * builders directly. {@code EnvironmentPropertiesHelper.filterAsyncProperties} strips every {@code maas.*}
 * key from route rendering, so no route XML is asserted here.
 */
class MaasClassifierEngineParityHarnessTest {

    private static final String CHAIN_ID = "chain-1";
    private static final String CHAIN_NAME = "Test Chain";
    private static final String ELEMENT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String ELEMENT_ORIGINAL_ID = "element-1-original";
    private static final String ELEMENT_NAME = "Element 1";

    // ---- fixtures -----------------------------------------------------------------------------

    private ChainElement buildElement(String type, String protocol, Map<String, Object> asyncProperties,
                                      Map<String, Object> envProperties) {
        Chain chain = Chain.builder().id(CHAIN_ID).name(CHAIN_NAME).build();
        Snapshot snapshot = Snapshot.builder().chain(chain).build();

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(CamelNames.OPERATION_PROTOCOL_TYPE_PROP, protocol);
        if (asyncProperties != null) {
            properties.put(CamelNames.OPERATION_ASYNC_PROPERTIES, asyncProperties);
        }

        // MAAS_BY_CLASSIFIER drives the %%{elementId_param} placeholders in the deploy-props builders;
        // the scope keys, when present, live on the environment's properties.
        ServiceEnvironment environment = new ServiceEnvironment();
        environment.setSourceType(EnvironmentSourceType.MAAS_BY_CLASSIFIER);
        environment.setProperties(envProperties);

        return ChainElement.builder()
                .id(ELEMENT_ID)
                .originalId(ELEMENT_ORIGINAL_ID)
                .name(ELEMENT_NAME)
                .type(type)
                .snapshot(snapshot)
                .properties(properties)
                .environment(environment)
                .build();
    }

    // Scope on the environment; classifier name authored onto the element.
    private ChainElement scopedElement(String type, String protocol) {
        Map<String, Object> asyncProperties = new LinkedHashMap<>();
        asyncProperties.put(CamelNames.MAAS_CLASSIFIER_NAME_PROP, "element-classifier");
        Map<String, Object> envScope = new LinkedHashMap<>();
        envScope.put(CamelNames.MAAS_CLASSIFIER_NAMESPACE_PROP, "element-ns");
        envScope.put(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ID_CAMEL_NAME, "tenant-42");
        // Environment properties are a free-form jsonb map: the UI writes tenantEnabled as the string
        // "true", an imported file may carry a real Boolean. Readers call toString, so pin the harder case.
        envScope.put(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ENABLED_CAMEL_NAME, Boolean.TRUE);
        return buildElement(type, protocol, asyncProperties, envScope);
    }

    // MAAS_BY_CLASSIFIER environment carrying no scope: readers fall back to null / "false".
    private ChainElement unscopedElement(String type, String protocol) {
        return buildElement(type, protocol, null, null);
    }

    // Classifier name authored on the element, but the environment omits namespace/tenantId: the environment
    // form leaves an empty field out of the map, so the bean writer reads null and defaults it.
    private ChainElement classifierNameOnlyElement(String type, String protocol) {
        Map<String, Object> asyncProperties = new LinkedHashMap<>();
        asyncProperties.put(CamelNames.MAAS_CLASSIFIER_NAME_PROP, "element-classifier");
        return buildElement(type, protocol, asyncProperties, new LinkedHashMap<>());
    }

    private MaasClassifierHelper classifierHelper() {
        return new MaasClassifierHelper();
    }

    // ---- classifier name: element only, no lookup ----------------------------------------------

    /**
     * The helper takes no services, so it cannot reach the database at all: the classifier name is read
     * from the element and nowhere else, the same single source the deploy-property builders use.
     */
    @Test
    void classifierNameComesFromTheElement() {
        assertEquals("element-classifier", classifierHelper().getMaasClassifierForServiceCallOrAsyncApiElement(
                scopedElement(CamelNames.SERVICE_CALL_COMPONENT, CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA)));
    }

    @Test
    void absentClassifierNameYieldsEmptyStringInsteadOfAnEnvironmentLookup() {
        assertEquals("", classifierHelper().getMaasClassifierForServiceCallOrAsyncApiElement(
                unscopedElement(CamelNames.SERVICE_CALL_COMPONENT, CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA)));
    }

    // ---- bean XML: env-authored scope reaches the MaasClassifierInfo bean ----------------------

    @Test
    void envScopeFlowsIntoKafkaBean() throws Exception {
        ChainElement element = scopedElement(CamelNames.SERVICE_CALL_COMPONENT, CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA);
        KafkaBeansBinder binder = new KafkaBeansBinder(classifierHelper());

        String actual = BeanXmlTestSupport.buildXml(binder, element);

        String expected = "<?xml version='1.0' encoding='UTF-8'?><test-root>"
                + "<bean name=\"" + ELEMENT_ID + "\" type=\"org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory\" builderClass=\"org.qubership.integration.platform.engine.util.builders.KafkaClientFactoryBuilder\" builderMethod=\"build\">"
                + "<properties>"
                + "<property key=\"chainId\" value=\"" + CHAIN_ID + "\"/>"
                + "<property key=\"chainName\" value=\"" + CHAIN_NAME + "\"/>"
                + "<property key=\"elementId\" value=\"" + ELEMENT_ORIGINAL_ID + "\"/>"
                + "<property key=\"elementName\" value=\"" + ELEMENT_NAME + "\"/>"
                + "<property key=\"maasClassifier\" value=\"element-classifier\"/>"
                + "</properties>"
                + "</bean>"
                + "<bean name=\"" + ELEMENT_ID + "-v2\" type=\"org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory\" builderClass=\"org.qubership.integration.platform.engine.util.builders.KafkaClientFactoryBuilder\" builderMethod=\"build\">"
                + "<properties>"
                + "<property key=\"chainId\" value=\"" + CHAIN_ID + "\"/>"
                + "<property key=\"chainName\" value=\"" + CHAIN_NAME + "\"/>"
                + "<property key=\"elementId\" value=\"" + ELEMENT_ORIGINAL_ID + "\"/>"
                + "<property key=\"elementName\" value=\"" + ELEMENT_NAME + "\"/>"
                + "<property key=\"maasClassifier\" value=\"element-classifier\"/>"
                + "</properties>"
                + "</bean>"
                + "<bean name=\"MaasClassifierInfo-" + ELEMENT_ID + "\" type=\"org.qubership.integration.platform.engine.metadata.MaasClassifierInfo\">"
                + "<properties>"
                + "<property key=\"elementId\" value=\"" + ELEMENT_ORIGINAL_ID + "\"/>"
                + "<property key=\"protocol\" value=\"kafka\"/>"
                + "<property key=\"classifier\" value=\"element-classifier\"/>"
                + "<property key=\"namespace\" value=\"element-ns\"/>"
                + "<property key=\"tenantId\" value=\"tenant-42\"/>"
                + "<property key=\"tenantEnabled\" value=\"true\"/>"
                + "</properties>"
                + "</bean>"
                + "</test-root>";

        assertEquals(expected, actual);
    }

    @Test
    void envScopeFlowsIntoAmpqBean() throws Exception {
        ChainElement element = scopedElement(CamelNames.SERVICE_CALL_COMPONENT, CamelNames.OPERATION_PROTOCOL_TYPE_AMQP);
        AmpqBeansBinder binder = new AmpqBeansBinder(classifierHelper());

        String actual = BeanXmlTestSupport.buildXml(binder, element);

        String expected = "<?xml version='1.0' encoding='UTF-8'?><test-root>"
                + "<bean name=\"" + ELEMENT_ID + "\" type=\"com.rabbitmq.client.MetricsCollector\" builderClass=\"org.qubership.integration.platform.engine.util.builders.RabbitMQMetricsCollectorBuilder\" builderMethod=\"build\">"
                + "<properties>"
                + "<property key=\"chainId\" value=\"" + CHAIN_ID + "\"/>"
                + "<property key=\"chainName\" value=\"" + CHAIN_NAME + "\"/>"
                + "<property key=\"elementId\" value=\"" + ELEMENT_ORIGINAL_ID + "\"/>"
                + "<property key=\"elementName\" value=\"" + ELEMENT_NAME + "\"/>"
                + "<property key=\"maasClassifier\" value=\"element-classifier\"/>"
                + "</properties>"
                + "</bean>"
                + "<bean name=\"MaasClassifierInfo-" + ELEMENT_ID + "\" type=\"org.qubership.integration.platform.engine.metadata.MaasClassifierInfo\">"
                + "<properties>"
                + "<property key=\"elementId\" value=\"" + ELEMENT_ORIGINAL_ID + "\"/>"
                + "<property key=\"protocol\" value=\"amqp\"/>"
                + "<property key=\"classifier\" value=\"element-classifier\"/>"
                + "<property key=\"namespace\" value=\"element-ns\"/>"
                + "<property key=\"tenantId\" value=\"tenant-42\"/>"
                + "<property key=\"tenantEnabled\" value=\"true\"/>"
                + "</properties>"
                + "</bean>"
                + "</test-root>";

        assertEquals(expected, actual);
    }

    // ---- bean XML: classifier name without env scope still writes the bean (no NPE) -------------

    // The classifier name is present (so the bean is written), but the environment carries no
    // namespace/tenantId. Woodstox writeAttribute NPEs on a null value, so the writer must coalesce the
    // missing scope to "" / "false". Reverting the guard makes this throw instead of producing the bean.
    @Test
    void envWithoutScopeStillWritesKafkaBean() throws Exception {
        ChainElement element = classifierNameOnlyElement(CamelNames.SERVICE_CALL_COMPONENT, CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA);
        KafkaBeansBinder binder = new KafkaBeansBinder(classifierHelper());

        String actual = BeanXmlTestSupport.buildXml(binder, element);

        String expected = "<?xml version='1.0' encoding='UTF-8'?><test-root>"
                + "<bean name=\"" + ELEMENT_ID + "\" type=\"org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory\" builderClass=\"org.qubership.integration.platform.engine.util.builders.KafkaClientFactoryBuilder\" builderMethod=\"build\">"
                + "<properties>"
                + "<property key=\"chainId\" value=\"" + CHAIN_ID + "\"/>"
                + "<property key=\"chainName\" value=\"" + CHAIN_NAME + "\"/>"
                + "<property key=\"elementId\" value=\"" + ELEMENT_ORIGINAL_ID + "\"/>"
                + "<property key=\"elementName\" value=\"" + ELEMENT_NAME + "\"/>"
                + "<property key=\"maasClassifier\" value=\"element-classifier\"/>"
                + "</properties>"
                + "</bean>"
                + "<bean name=\"" + ELEMENT_ID + "-v2\" type=\"org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory\" builderClass=\"org.qubership.integration.platform.engine.util.builders.KafkaClientFactoryBuilder\" builderMethod=\"build\">"
                + "<properties>"
                + "<property key=\"chainId\" value=\"" + CHAIN_ID + "\"/>"
                + "<property key=\"chainName\" value=\"" + CHAIN_NAME + "\"/>"
                + "<property key=\"elementId\" value=\"" + ELEMENT_ORIGINAL_ID + "\"/>"
                + "<property key=\"elementName\" value=\"" + ELEMENT_NAME + "\"/>"
                + "<property key=\"maasClassifier\" value=\"element-classifier\"/>"
                + "</properties>"
                + "</bean>"
                + "<bean name=\"MaasClassifierInfo-" + ELEMENT_ID + "\" type=\"org.qubership.integration.platform.engine.metadata.MaasClassifierInfo\">"
                + "<properties>"
                + "<property key=\"elementId\" value=\"" + ELEMENT_ORIGINAL_ID + "\"/>"
                + "<property key=\"protocol\" value=\"kafka\"/>"
                + "<property key=\"classifier\" value=\"element-classifier\"/>"
                + "<property key=\"namespace\" value=\"\"/>"
                + "<property key=\"tenantId\" value=\"\"/>"
                + "<property key=\"tenantEnabled\" value=\"false\"/>"
                + "</properties>"
                + "</bean>"
                + "</test-root>";

        assertEquals(expected, actual);
    }

    @Test
    void envWithoutScopeStillWritesAmpqBean() throws Exception {
        ChainElement element = classifierNameOnlyElement(CamelNames.SERVICE_CALL_COMPONENT, CamelNames.OPERATION_PROTOCOL_TYPE_AMQP);
        AmpqBeansBinder binder = new AmpqBeansBinder(classifierHelper());

        String actual = BeanXmlTestSupport.buildXml(binder, element);

        String expected = "<?xml version='1.0' encoding='UTF-8'?><test-root>"
                + "<bean name=\"" + ELEMENT_ID + "\" type=\"com.rabbitmq.client.MetricsCollector\" builderClass=\"org.qubership.integration.platform.engine.util.builders.RabbitMQMetricsCollectorBuilder\" builderMethod=\"build\">"
                + "<properties>"
                + "<property key=\"chainId\" value=\"" + CHAIN_ID + "\"/>"
                + "<property key=\"chainName\" value=\"" + CHAIN_NAME + "\"/>"
                + "<property key=\"elementId\" value=\"" + ELEMENT_ORIGINAL_ID + "\"/>"
                + "<property key=\"elementName\" value=\"" + ELEMENT_NAME + "\"/>"
                + "<property key=\"maasClassifier\" value=\"element-classifier\"/>"
                + "</properties>"
                + "</bean>"
                + "<bean name=\"MaasClassifierInfo-" + ELEMENT_ID + "\" type=\"org.qubership.integration.platform.engine.metadata.MaasClassifierInfo\">"
                + "<properties>"
                + "<property key=\"elementId\" value=\"" + ELEMENT_ORIGINAL_ID + "\"/>"
                + "<property key=\"protocol\" value=\"amqp\"/>"
                + "<property key=\"classifier\" value=\"element-classifier\"/>"
                + "<property key=\"namespace\" value=\"\"/>"
                + "<property key=\"tenantId\" value=\"\"/>"
                + "<property key=\"tenantEnabled\" value=\"false\"/>"
                + "</properties>"
                + "</bean>"
                + "</test-root>";

        assertEquals(expected, actual);
    }

    // ---- deploy properties + placeholders: env-authored scope ----------------------------------

    @Test
    void envScopeFlowsIntoKafkaDeployProperties() {
        ChainElement element = scopedElement(CamelNames.SERVICE_CALL_COMPONENT, CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA);
        KafkaElementPropertiesBuilder propertiesBuilder = new KafkaElementPropertiesBuilder(mock(MaasPropertiesUtils.class));

        Map<String, String> properties = propertiesBuilder.build(element);

        assertEquals("%%{" + ELEMENT_ORIGINAL_ID + "_" + CamelNames.OPERATION_PATH_TOPIC + "}", properties.get(CamelOptions.TOPICS));
        assertEquals("%%{" + ELEMENT_ORIGINAL_ID + "_" + CamelOptions.BROKERS + "}", properties.get(CamelOptions.BROKERS));
        assertEquals("%%{" + ELEMENT_ORIGINAL_ID + "_" + CamelOptions.SECURITY_PROTOCOL + "}", properties.get(CamelOptions.SECURITY_PROTOCOL));
        assertEquals("%%{" + ELEMENT_ORIGINAL_ID + "_" + CamelOptions.SASL_MECHANISM + "}", properties.get(CamelOptions.SASL_MECHANISM));
        assertEquals("%%{" + ELEMENT_ORIGINAL_ID + "_" + CamelOptions.SASL_JAAS_CONFIG + "}", properties.get(CamelOptions.SASL_JAAS_CONFIG));

        assertEquals("element-ns", properties.get(CamelOptions.MAAS_CLASSIFIER_NAMESPACE_PROP));
        assertEquals("element-classifier", properties.get(CamelOptions.MAAS_DEPLOYMENT_CLASSIFIER_PROP));
        assertEquals("true", properties.get(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ENABLED_PROP));
        assertEquals("tenant-42", properties.get(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ID_PROP));
    }

    @Test
    void envScopeFlowsIntoRabbitDeployProperties() {
        ChainElement element = scopedElement(CamelNames.SERVICE_CALL_COMPONENT, CamelNames.OPERATION_PROTOCOL_TYPE_AMQP);
        RabbitMqElementPropertiesBuilder propertiesBuilder = new RabbitMqElementPropertiesBuilder(mock(MaasPropertiesUtils.class));

        Map<String, String> properties = propertiesBuilder.build(element);

        assertEquals("%%{" + ELEMENT_ORIGINAL_ID + "_" + CamelOptions.SSL + "}", properties.get(CamelOptions.SSL));
        assertEquals("%%{" + ELEMENT_ORIGINAL_ID + "_" + CamelOptions.ADDRESSES + "}", properties.get(CamelOptions.ADDRESSES));
        assertEquals("%%{" + ELEMENT_ORIGINAL_ID + "_" + CamelOptions.USERNAME + "}", properties.get(CamelOptions.USERNAME));
        assertEquals("%%{" + ELEMENT_ORIGINAL_ID + "_" + CamelOptions.PASSWORD + "}", properties.get(CamelOptions.PASSWORD));
        assertEquals("%%{" + ELEMENT_ORIGINAL_ID + "_" + CamelOptions.VHOST + "}", properties.get(CamelOptions.VHOST));

        assertEquals("element-ns", properties.get(CamelOptions.MAAS_CLASSIFIER_NAMESPACE_PROP));
        assertEquals("element-classifier", properties.get(CamelOptions.MAAS_DEPLOYMENT_CLASSIFIER_PROP));
        assertEquals("true", properties.get(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ENABLED_PROP));
        assertEquals("tenant-42", properties.get(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ID_PROP));
    }

    // ---- deploy properties: environment without scope falls back to null / "false" -------------

    @Test
    void absentEnvScopeYieldsDefaultKafkaDeployProperties() {
        ChainElement element = unscopedElement(CamelNames.SERVICE_CALL_COMPONENT, CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA);
        KafkaElementPropertiesBuilder propertiesBuilder = new KafkaElementPropertiesBuilder(mock(MaasPropertiesUtils.class));

        Map<String, String> properties = propertiesBuilder.build(element);

        // Placeholders still come from element.getEnvironment().sourceType, not from the classifier scope.
        assertEquals("%%{" + ELEMENT_ORIGINAL_ID + "_" + CamelNames.OPERATION_PATH_TOPIC + "}", properties.get(CamelOptions.TOPICS));
        assertEquals("%%{" + ELEMENT_ORIGINAL_ID + "_" + CamelOptions.BROKERS + "}", properties.get(CamelOptions.BROKERS));

        assertNull(properties.get(CamelOptions.MAAS_CLASSIFIER_NAMESPACE_PROP));
        assertNull(properties.get(CamelOptions.MAAS_DEPLOYMENT_CLASSIFIER_PROP));
        assertEquals("false", properties.get(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ENABLED_PROP));
        assertNull(properties.get(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ID_PROP));
    }

    @Test
    void absentEnvScopeYieldsDefaultRabbitDeployProperties() {
        ChainElement element = unscopedElement(CamelNames.SERVICE_CALL_COMPONENT, CamelNames.OPERATION_PROTOCOL_TYPE_AMQP);
        RabbitMqElementPropertiesBuilder propertiesBuilder = new RabbitMqElementPropertiesBuilder(mock(MaasPropertiesUtils.class));

        Map<String, String> properties = propertiesBuilder.build(element);

        assertEquals("%%{" + ELEMENT_ORIGINAL_ID + "_" + CamelOptions.SSL + "}", properties.get(CamelOptions.SSL));
        assertEquals("%%{" + ELEMENT_ORIGINAL_ID + "_" + CamelOptions.ADDRESSES + "}", properties.get(CamelOptions.ADDRESSES));

        assertNull(properties.get(CamelOptions.MAAS_CLASSIFIER_NAMESPACE_PROP));
        assertNull(properties.get(CamelOptions.MAAS_DEPLOYMENT_CLASSIFIER_PROP));
        assertEquals("false", properties.get(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ENABLED_PROP));
        assertNull(properties.get(MaasPropertiesUtils.MAAS_CLASSIFIER_TENANT_ID_PROP));
    }
}
