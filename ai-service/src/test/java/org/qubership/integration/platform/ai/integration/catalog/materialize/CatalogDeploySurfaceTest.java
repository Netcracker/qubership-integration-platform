package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ChainDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.CreateDeploymentRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.CurrentSnapshotDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DeploymentDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DomainDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.SnapshotDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateChainRequest;

class CatalogDeploySurfaceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final InMemoryCatalogRestClient catalog = new InMemoryCatalogRestClient(Map.of());

  @Test
  void deserializesCurrentSnapshotAndUnsavedChangesFromCatalogJson() throws Exception {
    String json =
        """
        {
          "id": "chain-1",
          "name": "demo",
          "description": "Demo",
          "currentSnapshot": { "id": "snap-1", "name": "V1" },
          "unsavedChanges": true,
          "businessDescription": "ignored"
        }
        """;

    ChainDto chain = objectMapper.readValue(json, ChainDto.class);

    assertEquals("chain-1", chain.id());
    assertEquals("demo", chain.name());
    assertEquals("Demo", chain.description());
    assertEquals(new CurrentSnapshotDto("snap-1", "V1"), chain.currentSnapshot());
    assertTrue(chain.unsavedChanges());
  }

  @Test
  void threeArgChainDtoLeavesCurrentSnapshotNullAndUnsavedChangesFalse() {
    ChainDto chain = new ChainDto("chain-1", "demo", "Demo");

    assertNull(chain.currentSnapshot());
    assertFalse(chain.unsavedChanges());
  }

  @Test
  void createSnapshotNamesV1ThenV2AndListsBoth() {
    ChainDto created = catalog.createChain(CatalogCreateChainRequest.of("demo", "Demo"));
    assertNull(created.currentSnapshot());
    assertFalse(created.unsavedChanges());

    SnapshotDto first = catalog.createSnapshot(created.id());
    assertEquals("V1", first.name());

    ChainDto afterFirst = catalog.getChain(created.id());
    assertEquals(first.id(), afterFirst.currentSnapshot().id());
    assertEquals("V1", afterFirst.currentSnapshot().name());
    assertFalse(afterFirst.unsavedChanges());

    SnapshotDto second = catalog.createSnapshot(created.id());
    assertEquals("V2", second.name());

    List<SnapshotDto> snapshots = catalog.listSnapshots(created.id());
    assertEquals(List.of(first, second), snapshots);
  }

  @Test
  void deserializesDeploymentRuntimeStatesAndIgnoresUnknownFields() throws Exception {
    String json =
        """
        {
          "id": "dep-1",
          "chainId": "chain-1",
          "snapshotId": "snap-1",
          "name": "V1",
          "domain": "default",
          "domainType": "CLASSIC",
          "createdWhen": 1,
          "suspended": false,
          "serviceName": "engine",
          "runtime": {
            "states": {
              "engine-0": { "status": "DEPLOYED", "error": null, "stacktrace": "ignored" },
              "engine-1": { "status": "PROCESSING", "error": "wait" }
            }
          }
        }
        """;

    DeploymentDto deployment = objectMapper.readValue(json, DeploymentDto.class);

    assertEquals("dep-1", deployment.id());
    assertEquals("chain-1", deployment.chainId());
    assertEquals("snap-1", deployment.snapshotId());
    assertEquals("V1", deployment.name());
    assertEquals("default", deployment.domain());
    assertEquals("DEPLOYED", deployment.runtime().states().get("engine-0").status());
    assertNull(deployment.runtime().states().get("engine-0").error());
    assertEquals("PROCESSING", deployment.runtime().states().get("engine-1").status());
    assertEquals("wait", deployment.runtime().states().get("engine-1").error());
  }

  @Test
  void createListDeleteDeploymentsAllowsDuplicateDomain() {
    ChainDto chain = catalog.createChain(CatalogCreateChainRequest.of("demo", "Demo"));
    SnapshotDto snapshot = catalog.createSnapshot(chain.id());
    CreateDeploymentRequest request = new CreateDeploymentRequest("default", snapshot.id());

    DeploymentDto first = catalog.createDeployment(chain.id(), request);
    DeploymentDto second = catalog.createDeployment(chain.id(), request);

    assertEquals(chain.id(), first.chainId());
    assertEquals(snapshot.id(), first.snapshotId());
    assertEquals("default", first.domain());
    assertEquals("default", second.domain());

    List<DeploymentDto> listed = catalog.listDeployments(chain.id());
    assertEquals(List.of(first, second), listed);

    catalog.deleteDeployment(chain.id(), first.id());
    assertEquals(List.of(second), catalog.listDeployments(chain.id()));
  }

  @Test
  void listDomainsIncludesDefaultClassicDomain() {
    List<DomainDto> domains = catalog.listDomains();

    assertTrue(
        domains.stream()
            .anyMatch(domain -> "default".equals(domain.name()) && "CLASSIC".equals(domain.type())));
  }
}
