package org.qubership.integration.platform.ai.integration.catalog.pipeline;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.function.Supplier;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportService;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogChainPublicationService;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphMaterializeResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/**
 * Worker-thread entry point for sync catalog mutations from IMPLEMENT_CHAIN {@code PipelineStep}s.
 *
 * <p>Chain graph writes go through {@link CatalogGraphMaterializer}; this gateway does not
 * orchestrate skeleton, property, or connection specialists directly.
 */
@ApplicationScoped
public class CatalogMutationGateway {

  private final CatalogGraphMaterializer graphMaterializer;
  private final ApiHubSpecificationImportService apiHubSpecificationImportService;
  private final CatalogChainPublicationService chainPublicationService;

  @Inject
  public CatalogMutationGateway(
      CatalogGraphMaterializer graphMaterializer,
      ApiHubSpecificationImportService apiHubSpecificationImportService,
      CatalogChainPublicationService chainPublicationService) {
    this.graphMaterializer = graphMaterializer;
    this.apiHubSpecificationImportService = apiHubSpecificationImportService;
    this.chainPublicationService = chainPublicationService;
  }

  public Uni<String> resolveOrCreateChain(
      String pipelineId, String chainName, String chainDescription) {
    return onWorker(
        () -> chainPublicationService.resolveOrCreate(pipelineId, chainName, chainDescription));
  }

  public Uni<CatalogGraphMaterializeResult> applyGraph(
      ChainPlanGraph currentGraph, ChainPlanGraph desiredGraph, MaterializationMap map) {
    return onWorker(() -> graphMaterializer.apply(map.chainId(), currentGraph, desiredGraph, map));
  }

  public Uni<ApiHubSpecificationImportResult> importApiHubSpecification(
      String conversationId, ApiHubRequirementRefs refs) {
    return onWorker(() -> apiHubSpecificationImportService.importFromRefs(conversationId, refs));
  }

  private static <T> Uni<T> onWorker(Supplier<T> work) {
    return Uni.createFrom().item(work).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
  }
}
