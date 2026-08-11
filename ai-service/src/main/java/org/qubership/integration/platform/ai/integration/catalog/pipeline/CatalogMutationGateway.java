package org.qubership.integration.platform.ai.integration.catalog.pipeline;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.function.Supplier;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportService;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogChainPublicationService;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanSkeletonMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/**
 * Worker-thread entry point for sync catalog mutations from IMPLEMENT_CHAIN {@code PipelineStep}s.
 *
 * <p>The implement pipeline runs on the Vert.x event loop via Mutiny/SSE; MicroProfile REST client
 * calls must not run there. {@code PipelineStep} implementations must call this gateway (not
 * materializers or {@link org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient}
 * directly). LangChain4j catalog {@code @Tool} beans use a separate path.
 */
@ApplicationScoped
public class CatalogMutationGateway {

  private final ChainPlanSkeletonMaterializer skeletonMaterializer;
  private final ChainPlanPropertiesMaterializer propertiesMaterializer;
  private final ChainPlanConnectionsMaterializer connectionsMaterializer;
  private final ApiHubSpecificationImportService apiHubSpecificationImportService;
  private final CatalogChainPublicationService chainPublicationService;

  @Inject
  public CatalogMutationGateway(
      ChainPlanSkeletonMaterializer skeletonMaterializer,
      ChainPlanPropertiesMaterializer propertiesMaterializer,
      ChainPlanConnectionsMaterializer connectionsMaterializer,
      ApiHubSpecificationImportService apiHubSpecificationImportService,
      CatalogChainPublicationService chainPublicationService) {
    this.skeletonMaterializer = skeletonMaterializer;
    this.propertiesMaterializer = propertiesMaterializer;
    this.connectionsMaterializer = connectionsMaterializer;
    this.apiHubSpecificationImportService = apiHubSpecificationImportService;
    this.chainPublicationService = chainPublicationService;
  }

  public Uni<String> resolveOrCreateChain(
      String pipelineId, String chainName, String chainDescription) {
    return onWorker(
        () -> chainPublicationService.resolveOrCreate(pipelineId, chainName, chainDescription));
  }

  public Uni<MaterializationMap> materializeSkeletonElements(ChainPlanGraph graph, String chainId) {
    return onWorker(() -> skeletonMaterializer.materializeElements(graph, chainId));
  }

  public Uni<String> materializeSkeletonElement(
      ChainPlanGraph graph, ChainPlanNode node, String chainId, MaterializationMap currentMap) {
    return onWorker(() -> skeletonMaterializer.materializeElement(graph, node, chainId, currentMap));
  }

  public Uni<List<CatalogElementResponseDto>> listElements(String chainId) {
    return onWorker(() -> skeletonMaterializer.listElements(chainId));
  }

  public Uni<ChainPlanPropertiesMaterializer.PropertiesApplyResult> applyProperties(
      ChainPlanGraph plan, MaterializationMap map) {
    return onWorker(() -> propertiesMaterializer.apply(plan, map));
  }

  public Uni<ChainPlanConnectionsMaterializer.ConnectionsApplyResult> applyConnections(
      ChainPlanGraph plan, MaterializationMap map) {
    return onWorker(() -> connectionsMaterializer.apply(plan, map));
  }

  public Uni<ApiHubSpecificationImportResult> importApiHubSpecification(
      String conversationId, ApiHubRequirementRefs refs) {
    return onWorker(() -> apiHubSpecificationImportService.importFromRefs(conversationId, refs));
  }

  private static <T> Uni<T> onWorker(Supplier<T> work) {
    return Uni.createFrom().item(work).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
  }
}
