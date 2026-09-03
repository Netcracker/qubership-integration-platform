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

package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element.helpers;

import com.ctc.wstx.stax.WstxOutputFactory;
import org.codehaus.stax2.XMLStreamWriter2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.impl.ElementBuilder;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.chain.model.ServiceEnvironment;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.library.constants.CamelNames.MAAS_CLASSIFIER_NAME_PROP;
import static org.qubership.integration.platform.library.constants.CamelNames.OPERATION_ASYNC_PROPERTIES;
import static org.qubership.integration.platform.library.constants.CamelOptions.CONNECTION_SOURCE_TYPE_PROP;
import static org.qubership.integration.platform.library.constants.CamelOptions.DEFAULT_VHOST_CLASSIFIER_NAME;
import static org.qubership.integration.platform.library.constants.CamelOptions.MAAS_TOPICS_CLASSIFIER_NAME_PROP;
import static org.qubership.integration.platform.library.constants.CamelOptions.MAAS_VHOST_CLASSIFIER_NAME_PROP;
import static org.qubership.integration.platform.library.constants.CamelOptions.SYSTEM_ID;

@ExtendWith(MockitoExtension.class)
class MaasClassifierHelperTest {

    private final MaasClassifierHelper helper = new MaasClassifierHelper();

    @Mock
    private IntegrationServiceCatalog integrationServiceCatalog;

    @Mock
    private IntegrationService system;

    @Mock
    private ServiceEnvironment environment;

    // ----- getMaasClassifierForKafkaElement -----

    @Test
    void shouldResolveKafkaClassifierWhenConnectionSourceTypeIsLowerCaseMaas() {
        Element element = elementWith(Map.of(
                CONNECTION_SOURCE_TYPE_PROP, "maas",
                MAAS_TOPICS_CLASSIFIER_NAME_PROP, "orders-topic"));

        assertEquals("orders-topic", helper.getMaasClassifierForKafkaElement(element));
    }

    @Test
    void shouldResolveKafkaClassifierWhenConnectionSourceTypeIsMaasByClassifier() {
        Element element = elementWith(Map.of(
                CONNECTION_SOURCE_TYPE_PROP, "maas_by_classifier",
                MAAS_TOPICS_CLASSIFIER_NAME_PROP, "orders-topic"));

        assertEquals("orders-topic", helper.getMaasClassifierForKafkaElement(element));
    }

    @Test
    void shouldNotResolveKafkaClassifierWhenConnectionSourceTypeIsManual() {
        Element element = elementWith(Map.of(CONNECTION_SOURCE_TYPE_PROP, "manual"));

        assertEquals("", helper.getMaasClassifierForKafkaElement(element));
    }

    @Test
    void shouldReturnEmptyStringForKafkaClassifierWhenClassifierNameIsNull() {
        Element element = elementWith(Map.of(CONNECTION_SOURCE_TYPE_PROP, "maas"));

        assertEquals("", helper.getMaasClassifierForKafkaElement(element));
    }

    // ----- getMaasClassifierForAmpqElement -----

    @Test
    void shouldResolveAmpqClassifierWhenConnectionSourceTypeIsLowerCaseMaas() {
        Element element = elementWith(Map.of(
                CONNECTION_SOURCE_TYPE_PROP, "maas",
                MAAS_VHOST_CLASSIFIER_NAME_PROP, "custom-vhost"));

        assertEquals("custom-vhost", helper.getMaasClassifierForAmpqElement(element));
    }

    @Test
    void shouldUseDefaultVhostClassifierWhenAmpqClassifierNameMissing() {
        Element element = elementWith(Map.of(CONNECTION_SOURCE_TYPE_PROP, "MAAS"));

        assertEquals(DEFAULT_VHOST_CLASSIFIER_NAME, helper.getMaasClassifierForAmpqElement(element));
    }

    @Test
    void shouldNotResolveAmpqClassifierWhenConnectionSourceTypeIsManual() {
        Element element = elementWith(Map.of(CONNECTION_SOURCE_TYPE_PROP, "manual"));

        assertEquals("", helper.getMaasClassifierForAmpqElement(element));
    }

    // ----- getMaasClassifierForServiceCallOrAsyncApiElement -----

    @Test
    void shouldReadClassifierFromIntegrationOperationAsyncProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(OPERATION_ASYNC_PROPERTIES, Map.of(MAAS_CLASSIFIER_NAME_PROP, "async-classifier"));
        Element element = elementWith(properties);

        assertEquals(
                "async-classifier",
                helper.getMaasClassifierForServiceCallOrAsyncApiElement(element, integrationServiceCatalog));
    }

    @Test
    void shouldFallBackToActiveEnvironmentWhenClassifierNotInAsyncProperties() {
        Element element = elementWith(Map.of(SYSTEM_ID, "system-id"));

        when(integrationServiceCatalog.findById("system-id")).thenReturn(Optional.of(system));
        when(system.getActiveEnvironment()).thenReturn(Optional.of(environment));
        when(environment.getProperties()).thenReturn(Map.of(MAAS_CLASSIFIER_NAME_PROP, "env-classifier"));

        assertEquals(
                "env-classifier",
                helper.getMaasClassifierForServiceCallOrAsyncApiElement(element, integrationServiceCatalog));
    }

    @Test
    void shouldReturnEmptyStringWhenNoAsyncClassifierAndNoSystemId() {
        Element element = elementWith(new HashMap<>());

        assertEquals(
                "",
                helper.getMaasClassifierForServiceCallOrAsyncApiElement(element, integrationServiceCatalog));
    }

    // ----- addMaasClassifierInfoBean -----

    @Test
    void shouldWriteAllPropertiesInMaasClassifierInfoBean() throws Exception {
        Element element = ElementBuilder.createNew()
                .id("el-id")
                .originalId("original-el-id")
                .properties(new HashMap<>())
                .build();

        String xml = writeBean(element, "kafka", "orders-classifier", "my-namespace", "tenant-42", "true");

        assertAll(
                () -> assertTrue(xml.contains("name=\"MaasClassifierInfo-el-id\""), "bean name includes element id"),
                () -> assertTrue(xml.contains("type=\"org.qubership.integration.platform.engine.metadata.MaasClassifierInfo\""), "bean type"),
                () -> assertTrue(xml.contains("key=\"elementId\" value=\"original-el-id\""), "elementId uses originalId"),
                () -> assertTrue(xml.contains("key=\"protocol\" value=\"kafka\""), "protocol"),
                () -> assertTrue(xml.contains("key=\"classifier\" value=\"orders-classifier\""), "classifier"),
                () -> assertTrue(xml.contains("key=\"namespace\" value=\"my-namespace\""), "namespace"),
                () -> assertTrue(xml.contains("key=\"tenantId\" value=\"tenant-42\""), "tenantId"),
                () -> assertTrue(xml.contains("key=\"tenantEnabled\" value=\"true\""), "tenantEnabled")
        );
    }

    @Test
    void shouldWriteEmptyStringForNullPropertiesInMaasClassifierInfoBean() throws Exception {
        Element element = ElementBuilder.createNew()
                .id("element-id")
                .originalId("original-element-id")
                .properties(new HashMap<>())
                .build();

        String xml = writeBean(element, "kafka", "orders-topic", null, null, null);

        assertAll(
                () -> assertTrue(xml.contains("key=\"namespace\" value=\"\""), "null namespace becomes empty string"),
                () -> assertTrue(xml.contains("key=\"tenantId\" value=\"\""), "null tenantId becomes empty string"),
                () -> assertTrue(xml.contains("key=\"tenantEnabled\" value=\"\""), "null tenantEnabled becomes empty string")
        );
    }

    @Test
    void shouldFallBackToElementIdWhenOriginalIdIsMissing() throws Exception {
        Element element = ElementBuilder.createNew().id("el-id").properties(new HashMap<>()).build();

        String xml = writeBean(element, "kafka", "orders-classifier", "ns", "tenant", "true");

        assertTrue(xml.contains("key=\"elementId\" value=\"el-id\""));
    }

    private String writeBean(
            Element element,
            String protocol,
            String classifier,
            String namespace,
            String tenantId,
            String tenantEnabled
    ) throws Exception {
        StringWriter result = new StringWriter();
        XMLStreamWriter2 streamWriter = (XMLStreamWriter2) new WstxOutputFactory().createXMLStreamWriter(result);
        streamWriter.writeStartDocument();
        helper.addMaasClassifierInfoBean(
                streamWriter, element, protocol, classifier, namespace, tenantId, tenantEnabled);
        streamWriter.writeEndDocument();
        streamWriter.flush();
        return result.toString();
    }

    private static Element elementWith(Map<String, Object> properties) {
        return ElementBuilder.createNew().id("el-id").properties(new HashMap<>(properties)).build();
    }
}
