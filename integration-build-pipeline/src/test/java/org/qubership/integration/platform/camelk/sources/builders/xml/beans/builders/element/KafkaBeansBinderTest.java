/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element;

import com.ctc.wstx.stax.WstxOutputFactory;
import org.codehaus.stax2.XMLStreamWriter2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.camelk.sources.SourceBuilderContext;
import org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element.helpers.MaasClassifierHelper;
import org.qubership.integration.platform.chain.impl.ElementBuilder;
import org.qubership.integration.platform.chain.impl.ElementImpl;
import org.qubership.integration.platform.chain.model.Chain;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.library.constants.CamelNames;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.library.constants.CamelNames.ASYNC_API_TRIGGER_COMPONENT;
import static org.qubership.integration.platform.library.constants.CamelNames.KAFKA_SENDER_2_COMPONENT;
import static org.qubership.integration.platform.library.constants.CamelNames.KAFKA_SENDER_COMPONENT;
import static org.qubership.integration.platform.library.constants.CamelNames.KAFKA_TRIGGER_2_COMPONENT;
import static org.qubership.integration.platform.library.constants.CamelNames.KAFKA_TRIGGER_COMPONENT;
import static org.qubership.integration.platform.library.constants.CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA;
import static org.qubership.integration.platform.library.constants.CamelNames.OPERATION_PROTOCOL_TYPE_PROP;
import static org.qubership.integration.platform.library.constants.CamelNames.SERVICE_CALL_COMPONENT;
import static org.qubership.integration.platform.library.constants.CamelOptions.MAAS_CLASSIFIER_NAMESPACE;
import static org.qubership.integration.platform.library.constants.CamelOptions.MAAS_CLASSIFIER_TENANT_ENABLED;
import static org.qubership.integration.platform.library.constants.CamelOptions.MAAS_CLASSIFIER_TENANT_ID;

@ExtendWith(MockitoExtension.class)
class KafkaBeansBinderTest {

    @Mock
    private MaasClassifierHelper maasClassifierHelper;

    @Mock
    private XMLStreamWriter2 streamWriter;

    @Mock
    private IntegrationServiceCatalog integrationServiceCatalog;

    private SourceBuilderContext context;

    private KafkaBeansBinder binder;

    @BeforeEach
    void setUp() {
        binder = new KafkaBeansBinder(maasClassifierHelper);
        context = SourceBuilderContext.builder()
                .integrationServiceCatalog(integrationServiceCatalog)
                .build();
    }

    // ----- applicableTo -----

    @ParameterizedTest(name = "applicableTo returns true for native Kafka element type: {0}")
    @ValueSource(strings = {
        KAFKA_TRIGGER_COMPONENT,
        KAFKA_TRIGGER_2_COMPONENT,
        KAFKA_SENDER_COMPONENT,
        KAFKA_SENDER_2_COMPONENT
    })
    void applicableToReturnsTrueForAllNativeKafkaElementTypes(String type) {
        Element element = ElementBuilder.createNew().id("el-id").type(type).build();
        assertTrue(binder.applicableTo(element));
    }

    @ParameterizedTest(name = "applicableTo returns true for {0} component with Kafka protocol")
    @ValueSource(strings = {ASYNC_API_TRIGGER_COMPONENT, SERVICE_CALL_COMPONENT})
    void applicableToReturnsTrueForAsyncApiAndServiceCallWithKafkaProtocol(String type) {
        Element element = ElementBuilder.createNew()
                .id("el-id")
                .type(type)
                .properties(new HashMap<>(Map.of(OPERATION_PROTOCOL_TYPE_PROP, OPERATION_PROTOCOL_TYPE_KAFKA)))
                .build();
        assertTrue(binder.applicableTo(element));
    }

    @Test
    void applicableToReturnsFalseForUnrelatedElementType() {
        Element element = ElementBuilder.createNew().id("el-id").type("http-trigger").build();
        assertFalse(binder.applicableTo(element));
    }

    @Test
    void applicableToReturnsFalseForAsyncApiTriggerWithNonKafkaProtocol() {
        Element element = ElementBuilder.createNew()
                .id("el-id")
                .type(ASYNC_API_TRIGGER_COMPONENT)
                .properties(new HashMap<>(Map.of(OPERATION_PROTOCOL_TYPE_PROP, "amqp")))
                .build();
        assertFalse(binder.applicableTo(element));
    }

    // ----- build: Kafka trigger -----

    @Test
    void shouldAddMaasClassifierInfoBeanForKafkaTriggerWhenClassifierPresent() throws Exception {
        Element element = kafkaTriggerElement();

        when(maasClassifierHelper.getMaasClassifierForKafkaElement(element)).thenReturn("orders-topic");

        binder.build(streamWriter, element, context);

        verify(maasClassifierHelper).addMaasClassifierInfoBean(
                eq(streamWriter),
                eq(element),
                eq(OPERATION_PROTOCOL_TYPE_KAFKA),
                eq("orders-topic"),
                isNull(),
                isNull(),
                eq("false")
        );
    }

    @Test
    void shouldNotAddMaasClassifierInfoBeanWhenClassifierBlank() throws Exception {
        Element element = kafkaTriggerElement();

        when(maasClassifierHelper.getMaasClassifierForKafkaElement(element)).thenReturn("");

        binder.build(streamWriter, element, context);

        verify(maasClassifierHelper, never()).addMaasClassifierInfoBean(
                eq(streamWriter),
                eq(element),
                eq(OPERATION_PROTOCOL_TYPE_KAFKA),
                eq(""),
                isNull(),
                isNull(),
                eq("false")
        );
    }

    @Test
    void shouldPassNativeTenantFieldsToMaasClassifierInfoBeanForKafkaElement() throws Exception {
        Element element = kafkaTriggerElementWithTenantProperties("kafka-ns", "kafka-tenant", "true");

        when(maasClassifierHelper.getMaasClassifierForKafkaElement(element)).thenReturn("orders-topic");

        binder.build(streamWriter, element, context);

        verify(maasClassifierHelper).addMaasClassifierInfoBean(
                eq(streamWriter),
                eq(element),
                eq(OPERATION_PROTOCOL_TYPE_KAFKA),
                eq("orders-topic"),
                eq("kafka-ns"),
                eq("kafka-tenant"),
                eq("true")
        );
    }

    // ----- build: AsyncAPI trigger -----

    @Test
    void shouldUseKafkaProtocolForAsyncApiTriggerMaasClassifierInfoBean() throws Exception {
        Element element = asyncApiKafkaElement(Map.of());

        when(maasClassifierHelper.getMaasClassifierForServiceCallOrAsyncApiElement(element, integrationServiceCatalog))
                .thenReturn("async-topic-classifier");

        binder.build(streamWriter, element, context);

        verify(maasClassifierHelper).addMaasClassifierInfoBean(
                eq(streamWriter),
                eq(element),
                eq(OPERATION_PROTOCOL_TYPE_KAFKA),
                eq("async-topic-classifier"),
                isNull(),
                isNull(),
                eq("false")
        );
    }

    @Test
    void shouldPassTenantFieldsFromAsyncApiTriggerPropertiesToMaasClassifierInfoBean() throws Exception {
        Map<String, Object> asyncProperties = Map.of(
                CamelNames.MAAS_CLASSIFIER_NAMESPACE_PROP, "orders-ns",
                CamelNames.MAAS_CLASSIFIER_TENANT_ID_CAMEL_NAME, "tenant-1",
                CamelNames.MAAS_CLASSIFIER_TENANT_ENABLED_CAMEL_NAME, "true"
        );
        Element element = asyncApiKafkaElement(asyncProperties);

        when(maasClassifierHelper.getMaasClassifierForServiceCallOrAsyncApiElement(element, integrationServiceCatalog))
                .thenReturn("async-topic-classifier");

        binder.build(streamWriter, element, context);

        verify(maasClassifierHelper).addMaasClassifierInfoBean(
                eq(streamWriter),
                eq(element),
                eq(OPERATION_PROTOCOL_TYPE_KAFKA),
                eq("async-topic-classifier"),
                eq("orders-ns"),
                eq("tenant-1"),
                eq("true")
        );
    }

    // ----- build: ServiceCall -----

    @Test
    void shouldAddMaasClassifierInfoBeanForServiceCallKafkaElementWhenClassifierPresent() throws Exception {
        Element element = serviceCallKafkaElement(Map.of());

        when(maasClassifierHelper.getMaasClassifierForServiceCallOrAsyncApiElement(element, integrationServiceCatalog))
                .thenReturn("service-call-topic");

        binder.build(streamWriter, element, context);

        verify(maasClassifierHelper).addMaasClassifierInfoBean(
                eq(streamWriter),
                eq(element),
                eq(OPERATION_PROTOCOL_TYPE_KAFKA),
                eq("service-call-topic"),
                isNull(),
                isNull(),
                eq("false")
        );
    }

    // ----- build: XML structure -----

    @Test
    void shouldWriteTwoKafkaClientFactoryBeansWithDistinctNames() throws Exception {
        Element element = kafkaTriggerElement();

        when(maasClassifierHelper.getMaasClassifierForKafkaElement(element)).thenReturn("");

        StringWriter result = new StringWriter();
        XMLStreamWriter2 realWriter = (XMLStreamWriter2) new WstxOutputFactory().createXMLStreamWriter(result);
        realWriter.writeStartDocument();
        realWriter.writeStartElement("beans");

        binder.build(realWriter, element, context);

        realWriter.writeEndElement();
        realWriter.writeEndDocument();
        realWriter.flush();

        String xml = result.toString();
        assertAll(
                () -> assertTrue(xml.contains("name=\"element-id\""), "first factory bean uses element id"),
                () -> assertTrue(xml.contains("name=\"element-id-v2\""), "second factory bean appends -v2"),
                () -> assertTrue(xml.contains("type=\"org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory\""), "factory bean type"),
                () -> assertTrue(xml.contains("builderClass=\"org.qubership.integration.platform.engine.util.builders.KafkaClientFactoryBuilder\""), "builderClass"),
                () -> assertTrue(xml.contains("key=\"chainId\" value=\"chain-id\""), "chainId property"),
                () -> assertTrue(xml.contains("key=\"chainName\" value=\"chain-name\""), "chainName property"),
                () -> assertTrue(xml.contains("key=\"elementId\" value=\"original-element-id\""), "elementId uses originalId"),
                () -> assertTrue(xml.contains("key=\"elementName\" value=\"kafka-trigger\""), "elementName property"),
                () -> assertFalse(xml.contains("key=\"maasClassifier\""), "no maasClassifier property when classifier is blank")
        );
    }

    @Test
    void shouldWriteMaasClassifierPropertyInFactoryBeansWhenClassifierPresent() throws Exception {
        Element element = kafkaTriggerElement();

        when(maasClassifierHelper.getMaasClassifierForKafkaElement(element)).thenReturn("orders-topic");

        StringWriter result = new StringWriter();
        XMLStreamWriter2 realWriter = (XMLStreamWriter2) new WstxOutputFactory().createXMLStreamWriter(result);
        realWriter.writeStartDocument();
        realWriter.writeStartElement("beans");

        binder.build(realWriter, element, context);

        realWriter.writeEndElement();
        realWriter.writeEndDocument();
        realWriter.flush();

        assertTrue(
                result.toString().contains("key=\"maasClassifier\" value=\"orders-topic\""),
                result.toString()
        );
    }

    // ----- helpers -----

    private static Element kafkaTriggerElement() {
        return elementWithSnapshot("element-id", "original-element-id", "kafka-trigger", KAFKA_TRIGGER_2_COMPONENT, new HashMap<>());
    }

    private static Element kafkaTriggerElementWithTenantProperties(
            String namespace, String tenantId, String tenantEnabled) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(MAAS_CLASSIFIER_NAMESPACE, namespace);
        properties.put(MAAS_CLASSIFIER_TENANT_ID, tenantId);
        properties.put(MAAS_CLASSIFIER_TENANT_ENABLED, tenantEnabled);
        return elementWithSnapshot("element-id", "original-element-id", "kafka-trigger", KAFKA_TRIGGER_2_COMPONENT, properties);
    }

    private static Element asyncApiKafkaElement(Map<String, Object> asyncProperties) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(OPERATION_PROTOCOL_TYPE_PROP, OPERATION_PROTOCOL_TYPE_KAFKA);
        if (!asyncProperties.isEmpty()) {
            properties.put(CamelNames.OPERATION_ASYNC_PROPERTIES, asyncProperties);
        }
        return elementWithSnapshot("async-element-id", "async-original-id", "async-api-trigger", ASYNC_API_TRIGGER_COMPONENT, properties);
    }

    private static Element serviceCallKafkaElement(Map<String, Object> asyncProperties) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(OPERATION_PROTOCOL_TYPE_PROP, OPERATION_PROTOCOL_TYPE_KAFKA);
        if (!asyncProperties.isEmpty()) {
            properties.put(CamelNames.OPERATION_ASYNC_PROPERTIES, asyncProperties);
        }
        return elementWithSnapshot("service-call-kafka-id", "service-call-kafka-original-id", "service-call-kafka", SERVICE_CALL_COMPONENT, properties);
    }

    /**
     * An element carrying the snapshot the binders read the chain from. The chain is stubbed
     * leniently because the {@code applicableTo} cases never reach it.
     */
    private static Element elementWithSnapshot(
            String id,
            String originalId,
            String name,
            String type,
            Map<String, Object> properties
    ) {
        Chain chain = mock(Chain.class);
        lenient().when(chain.getId()).thenReturn("chain-id");
        lenient().when(chain.getName()).thenReturn("chain-name");
        Snapshot snapshot = mock(Snapshot.class);
        lenient().when(snapshot.getChain()).thenReturn(chain);

        ElementImpl element = (ElementImpl) ElementBuilder.createNew()
                .id(id)
                .originalId(originalId)
                .name(name)
                .type(type)
                .properties(new HashMap<>(properties))
                .build();
        element.setSnapshot(snapshot);
        return element;
    }
}
