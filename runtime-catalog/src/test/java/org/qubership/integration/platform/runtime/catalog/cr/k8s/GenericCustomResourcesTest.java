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

package org.qubership.integration.platform.runtime.catalog.cr.k8s;

import io.kubernetes.client.util.ModelMapper;
import io.kubernetes.client.util.Yaml;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.options.DBaaSOperatorType;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericCustomResourcesTest {

    private GenericCustomResources genericCustomResources(String activeProfile) {
        GenericCustomResources genericCustomResources = new GenericCustomResources();
        ReflectionTestUtils.setField(genericCustomResources, "activeProfile", activeProfile);
        return genericCustomResources;
    }

    private GenericCustomResources genericCustomResources(boolean dbaasEnabled, DBaaSOperatorType operatorType) {
        GenericCustomResources genericCustomResources = genericCustomResources("default");
        ReflectionTestUtils.setField(genericCustomResources, "dbaasEnabled", dbaasEnabled);
        ReflectionTestUtils.setField(genericCustomResources, "dbaasOperatorType", operatorType);
        return genericCustomResources;
    }

    @Test
    void testGetCustomResourceDefinitionsIncludesInternalDatabaseForDbaasOperator() {
        GenericCustomResources.CustomResourceDefinition internalDatabase =
                genericCustomResources(true, DBaaSOperatorType.DBAAS).getCustomResourceDefinitions().get("InternalDatabase");

        assertEquals("dbaas.netcracker.com", internalDatabase.group());
        assertEquals("v1", internalDatabase.version());
        assertEquals("internaldatabases", internalDatabase.plural());
        assertFalse(internalDatabase.updateIfExists());
    }

    @Test
    void testGetCustomResourceDefinitionsOmitsInternalDatabaseWhenNotGenerated() {
        assertFalse(genericCustomResources(false, DBaaSOperatorType.DBAAS)
                .getCustomResourceDefinitions().containsKey("InternalDatabase"));
        assertFalse(genericCustomResources(true, DBaaSOperatorType.CORE)
                .getCustomResourceDefinitions().containsKey("InternalDatabase"));
    }

    // MicroDomainService.deploy() parses the built YAML with Yaml.loadAll(). Before the kind was
    // registered, it parsed InternalDatabase as a DynamicKubernetesObject and failed with
    // "Unable to find property 'spec'".
    @Test
    void testRegisterModelMapsLetsYamlLoadInternalDatabase() throws IOException {
        genericCustomResources(true, DBaaSOperatorType.DBAAS).registerModelMaps();

        Object resource = Yaml.load("""
                apiVersion: dbaas.netcracker.com/v1
                kind: InternalDatabase
                metadata:
                  name: domain-configs
                spec:
                  type: postgresql
                """);

        assertInstanceOf(KubeCustomObject.class, resource);
    }

    @Test
    void testGetCustomResourceDefinitionsReturnsKnownKinds() {
        Map<String, GenericCustomResources.CustomResourceDefinition> definitions =
                genericCustomResources("default").getCustomResourceDefinitions();

        assertEquals(3, definitions.size());

        GenericCustomResources.CustomResourceDefinition facadeService = definitions.get("FacadeService");
        assertEquals("netcracker.com", facadeService.group());
        assertEquals("v1alpha", facadeService.version());
        assertEquals("facadeservices", facadeService.plural());
        assertFalse(facadeService.updateIfExists());

        GenericCustomResources.CustomResourceDefinition mesh = definitions.get("Mesh");
        assertEquals("core.netcracker.com", mesh.group());
        assertEquals("v1", mesh.version());
        assertEquals("meshes", mesh.plural());
        assertTrue(mesh.updateIfExists());

        GenericCustomResources.CustomResourceDefinition dbaas = definitions.get("DBaaS");
        assertEquals("core.netcracker.com", dbaas.group());
        assertEquals("v1", dbaas.version());
        assertEquals("dbaases", dbaas.plural());
        assertFalse(dbaas.updateIfExists());
    }

    @Test
    void testGetCustomResourceDefinitionsReturnsUnmodifiableMap() {
        Map<String, GenericCustomResources.CustomResourceDefinition> definitions =
                genericCustomResources("default").getCustomResourceDefinitions();

        assertThrows(UnsupportedOperationException.class, definitions::clear);
    }

    @Test
    void testGetCustomResourceDefinitionsReturnsEmptyMapForLocalDevProfile() {
        Map<String, GenericCustomResources.CustomResourceDefinition> definitions =
                genericCustomResources("localdev").getCustomResourceDefinitions();

        assertTrue(definitions.isEmpty());
    }

    @Test
    void testApiVersionCombinesGroupAndVersionWhenGroupPresent() {
        var definition = new GenericCustomResources.CustomResourceDefinition(
                "core.netcracker.com", "v1", "Mesh", "meshes", true);

        assertEquals("core.netcracker.com/v1", definition.apiVersion());
    }

    @Test
    void testApiVersionIsJustVersionWhenGroupEmpty() {
        var definition = new GenericCustomResources.CustomResourceDefinition(
                "", "v1", "Mesh", "meshes", true);

        assertEquals("v1", definition.apiVersion());
    }

    @Test
    void testDefinitionForReturnsMatchingDefinition() {
        GenericCustomResources.CustomResourceDefinition definition =
                genericCustomResources("default").definitionFor("DBaaS");

        assertEquals("DBaaS", definition.kind());
        assertEquals("dbaases", definition.plural());
    }

    @Test
    void testDefinitionForThrowsForUnknownKind() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> genericCustomResources("default").definitionFor("Unknown"));

        assertTrue(exception.getMessage().contains("Unknown"));
    }

    @Test
    void testDefinitionForThrowsWhenLocalDevProfileHasNoDefinitions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> genericCustomResources("localdev").definitionFor("Mesh"));
    }

    @Test
    void testRegisterModelMapsRegistersEachDefinitionWithModelMapper() {
        GenericCustomResources genericCustomResources = genericCustomResources("default");

        genericCustomResources.registerModelMaps();

        for (GenericCustomResources.CustomResourceDefinition definition
                : genericCustomResources.getCustomResourceDefinitions().values()) {
            assertEquals(
                    KubeCustomObject.class,
                    ModelMapper.getApiTypeClass(definition.group(), definition.version(), definition.kind()));
        }
    }
}
