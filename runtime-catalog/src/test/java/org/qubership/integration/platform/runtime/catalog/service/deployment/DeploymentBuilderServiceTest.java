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

package org.qubership.integration.platform.runtime.catalog.service.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.io.writers.camel.xml.CompositeTriggerHelper;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.library.constants.ConfigurationPropertiesConstants;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.MaskedField;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentRouteMapper;
import org.qubership.integration.platform.runtime.catalog.service.SnapshotService;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilderFactory;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;

import java.sql.Timestamp;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the two public assemblers of {@link DeploymentBuilderService}: the stop list, which only
 * carries the deployment info through, and the update list, which resolves the chain and snapshot,
 * fills the deployment info, collects masked fields, builds element properties, and substitutes the
 * deployment-id and domain placeholders in the snapshot XML.
 */
@ExtendWith(MockitoExtension.class)
class DeploymentBuilderServiceTest {

    @Mock
    private ChainFinderService chainFinderService;
    @Mock
    private SnapshotService snapshotService;
    @Mock
    private ElementPropertiesBuilderFactory elementPropertiesBuilderFactory;
    @Mock
    private LibraryElementsService libraryService;
    @Mock
    private DeploymentRouteMapper deploymentRouteMapper;
    @Mock
    private CompositeTriggerHelper compositeTriggerHelper;

    private DeploymentBuilderService service;

    @BeforeEach
    void setUp() {
        service = new DeploymentBuilderService(
                chainFinderService,
                snapshotService,
                elementPropertiesBuilderFactory,
                libraryService,
                deploymentRouteMapper,
                compositeTriggerHelper);
    }

    @Test
    void buildDeploymentsStopWrapsEachInfoWithoutConfiguration() {
        DeploymentInfo first = DeploymentInfo.builder().deploymentId("d1").build();
        DeploymentInfo second = DeploymentInfo.builder().deploymentId("d2").build();

        List<DeploymentUpdate> result = service.buildDeploymentsStop(List.of(first, second));

        assertThat(result).extracting(DeploymentUpdate::getDeploymentInfo).containsExactly(first, second);
        assertThat(result).allSatisfy(update -> assertThat(update.getConfiguration()).isNull());
    }

    @Test
    void buildDeploymentsUpdateFillsInfoMaskedFieldsAndSubstitutesPlaceholders() {
        Deployment deployment = deploymentFixture(Map.of(ConfigurationPropertiesConstants.ELEMENT_TYPE, CamelNames.CHECKPOINT));
        stubLookups();

        List<DeploymentUpdate> result = service.buildDeploymentsUpdate(List.of(deployment));

        assertThat(result).hasSize(1);
        DeploymentUpdate update = result.get(0);

        DeploymentInfo info = update.getDeploymentInfo();
        assertThat(info.getDeploymentId()).isEqualTo("dep-1");
        assertThat(info.getChainId()).isEqualTo("chain-1");
        assertThat(info.getChainName()).isEqualTo("chain-name");
        assertThat(info.getSnapshotId()).isEqualTo("snap-1");
        assertThat(info.getSnapshotName()).isEqualTo("snap-name");
        assertThat(info.getCreatedWhen()).isEqualTo(1000L);
        assertThat(info.getContainsCheckpointElements()).isTrue();
        assertThat(info.getContainsSchedulerElements()).isFalse();

        assertThat(update.getMaskedFields()).containsExactly("password");

        assertThat(update.getConfiguration().getXml())
                .contains("deploymentId=dep-1")
                .contains("domain=mydomain")
                .doesNotContain("placeholder");
        assertThat(update.getConfiguration().getProperties())
                .singleElement()
                .satisfies(properties -> {
                    assertThat(properties.getElementId()).isEqualTo("elem-1");
                    assertThat(properties.getProperties())
                            .containsEntry(ConfigurationPropertiesConstants.ELEMENT_TYPE, CamelNames.CHECKPOINT);
                });
    }

    @Test
    void buildDeploymentsUpdateFlagsSchedulerElements() {
        Deployment deployment = deploymentFixture(Map.of(ConfigurationPropertiesConstants.ELEMENT_TYPE, CamelNames.SCHEDULER));
        stubLookups();

        DeploymentInfo info = service.buildDeploymentsUpdate(List.of(deployment)).get(0).getDeploymentInfo();

        assertThat(info.getContainsSchedulerElements()).isTrue();
        assertThat(info.getContainsCheckpointElements()).isFalse();
    }

    private Deployment deploymentFixture(Map<String, String> elementProperties) {
        ChainElement element = ChainElement.builder().type("http-trigger").build();
        element.setId("elem-1");

        Snapshot snapshot = Snapshot.builder()
                .xmlDefinition("deploymentId=%%{deployment-id-placeholder};domain=%%{domain-placeholder}")
                .elements(new LinkedList<>(List.of(element)))
                .build();
        snapshot.setId("snap-1");
        snapshot.setName("snap-name");

        MaskedField maskedField = new MaskedField();
        maskedField.setName("password");
        Chain chain = Chain.builder().build();
        chain.setId("chain-1");
        chain.setName("chain-name");
        chain.setMaskedFields(Set.of(maskedField));

        Deployment deployment = new Deployment();
        deployment.setId("dep-1");
        deployment.setChain(chain);
        deployment.setSnapshot(snapshot);
        deployment.setDomain("MyDomain");
        deployment.setCreatedWhen(new Timestamp(1000L));
        deployment.setDeploymentRoutes(List.of());

        when(chainFinderService.findById("chain-1")).thenReturn(chain);
        when(snapshotService.findById("snap-1")).thenReturn(snapshot);

        ElementPropertiesBuilder builder = mock(ElementPropertiesBuilder.class);
        when(builder.build(any())).thenReturn(elementProperties);
        when(elementPropertiesBuilderFactory.getElementPropertiesBuilder(any())).thenReturn(builder);
        return deployment;
    }

    private void stubLookups() {
        when(libraryService.lookupElementDescriptor(anyString())).thenReturn(Optional.empty());
        when(compositeTriggerHelper.splitCompositeTriggers(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(deploymentRouteMapper.asUpdates(any())).thenReturn(List.of());
    }
}
