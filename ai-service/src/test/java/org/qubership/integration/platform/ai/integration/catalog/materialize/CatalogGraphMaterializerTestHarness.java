package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.mockito.Mockito;
import org.qubership.integration.platform.ai.chain.imports.ChainPlanGraphImporter;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/** Wires real catalog graph materializers against {@link InMemoryCatalogRestClient}. */
final class CatalogGraphMaterializerTestHarness {

  private static final String CHAIN_ID = "parity-chain";

  private final InMemoryCatalogRestClient catalog;
  private final CatalogGraphMaterializer materializer;
  private final ChainCatalogFactsService factsService;
  private final ChainPlanGraphImporter graphImporter;

  CatalogGraphMaterializerTestHarness() {
    this(ModernContainerDescriptorFixtures.LIBRARY);
  }

  CatalogGraphMaterializerTestHarness(Map<String, ?> library) {
    @SuppressWarnings("unchecked")
    Map<String, org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorDto>
        descriptors =
            (Map<
                    String,
                    org.qubership.integration.platform.ai.integration.catalog.descriptor
                        .CatalogElementDescriptorDto>)
                library;
    this.catalog = new InMemoryCatalogRestClient(descriptors);
    this.catalog.ensureChain(CHAIN_ID, "parity-chain");
    ObjectMapper objectMapper = new ObjectMapper();
    DeterministicElementSchemaService schemaService = Mockito.mock(DeterministicElementSchemaService.class);
    Mockito.lenient()
        .when(schemaService.coercePatchPropertyValue(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    Mockito.lenient()
        .when(schemaService.allowedPatchPropertyKeys(Mockito.anyString()))
        .thenReturn(Set.of());
    Mockito.lenient()
        .when(schemaService.validateElementPatch(Mockito.anyString(), Mockito.anyString()))
        .thenReturn("{\"valid\":true}");
    ChainPlanPropertiesMaterializer propertiesMaterializer =
        new ChainPlanPropertiesMaterializer(catalog, schemaService, objectMapper);
    CatalogElementDescriptorLoader descriptorLoader = new CatalogElementDescriptorLoader(catalog);
    ChainPlanSkeletonMaterializer skeletonMaterializer =
        new ChainPlanSkeletonMaterializer(catalog, descriptorLoader);
    ChainPlanConnectionsMaterializer connectionsMaterializer =
        new ChainPlanConnectionsMaterializer(catalog);
    ChainPlanRemovalsMaterializer removalsMaterializer = new ChainPlanRemovalsMaterializer(catalog);
    this.factsService = new ChainCatalogFactsService(catalog);
    this.graphImporter =
        new ChainPlanGraphImporter(objectMapper, new CanonicalGraphDigest(objectMapper));
    CatalogGraphReadBackVerifier readBackVerifier =
        new CatalogGraphReadBackVerifier(factsService, graphImporter);
    this.materializer =
        new CatalogGraphMaterializer(
            propertiesMaterializer,
            skeletonMaterializer,
            connectionsMaterializer,
            removalsMaterializer,
            catalog,
            descriptorLoader,
            readBackVerifier);
  }

  InMemoryCatalogRestClient catalog() {
    return catalog;
  }

  CatalogGraphMaterializer materializer() {
    return materializer;
  }

  String chainId() {
    return CHAIN_ID;
  }

  CatalogGraphMaterializeResult create(ChainPlanGraph desired) {
    resetCatalog();
    return materializer.apply(
        CHAIN_ID,
        CatalogGraphMaterializer.emptyCurrent(desired),
        desired,
        new MaterializationMap(CHAIN_ID, Map.of(), Map.of(), Map.of()));
  }

  CatalogGraphMaterializeResult edit(ChainPlanGraph current, ChainPlanGraph desired) {
    resetCatalog();
    MaterializationMap identityMap = seedCurrentGraph(current);
    return materializer.apply(CHAIN_ID, current, desired, identityMap);
  }

  ImportedChainPlan importCatalog(MaterializationMap map) {
    return graphImporter.importChain(factsService.load(CHAIN_ID));
  }

  private void resetCatalog() {
    catalog.reset();
    catalog.ensureChain(CHAIN_ID, "parity-chain");
  }

  private MaterializationMap seedCurrentGraph(ChainPlanGraph current) {
    Map<String, String> seededIds = new LinkedHashMap<>();
    for (ChainPlanNode node : parentFirst(current.nodes())) {
      String parentCatalogId =
          node.parentNodeId() == null ? null : seededIds.get(node.parentNodeId());
      String catalogId =
          catalog.createSeededElement(CHAIN_ID, node.type(), parentCatalogId, node.label());
      seededIds.put(node.nodeId(), catalogId);
    }
    return new MaterializationMap(CHAIN_ID, Map.copyOf(seededIds), Map.of(), Map.of());
  }

  private static List<ChainPlanNode> parentFirst(List<ChainPlanNode> nodes) {
    List<ChainPlanNode> ordered = new java.util.ArrayList<>();
    java.util.Set<String> placed = new java.util.LinkedHashSet<>();
    for (int round = 0; round < nodes.size() && placed.size() < nodes.size(); round++) {
      for (ChainPlanNode node : nodes) {
        if (placed.contains(node.nodeId())) {
          continue;
        }
        String parentId = node.parentNodeId();
        if (parentId != null && !placed.contains(parentId)) {
          continue;
        }
        ordered.add(node);
        placed.add(node.nodeId());
      }
    }
    for (ChainPlanNode node : nodes) {
      if (placed.add(node.nodeId())) {
        ordered.add(node);
      }
    }
    return ordered;
  }
}
