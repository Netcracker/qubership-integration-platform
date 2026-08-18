package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/** Regression matrix proving CREATE and EDIT converge through {@link CatalogGraphMaterializer#apply}. */
class CatalogGraphCreateEditParityTest {

  private CatalogGraphMaterializerTestHarness harness;

  @BeforeEach
  void setUp() {
    harness = new CatalogGraphMaterializerTestHarness();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("contractMatrix")
  void createAndEditMaterializeTheSameGraph(
      String caseName,
      InMemoryCatalogRestClient.GeneratedChildDelivery delivery,
      ChainPlanGraph desired) {
    harness.catalog().setGeneratedChildDelivery(delivery);
    assertParity(desired);
  }

  static Stream<Arguments> contractMatrix() {
    return Stream.of(
            deliveryCase("condition-one-if-no-else-inline", InMemoryCatalogRestClient.GeneratedChildDelivery.INLINE,
                CatalogGraphParityScenarios.conditionOneIf(false)),
            deliveryCase("condition-one-if-with-else-inline", InMemoryCatalogRestClient.GeneratedChildDelivery.INLINE,
                CatalogGraphParityScenarios.conditionOneIf(true)),
            deliveryCase("condition-several-if-no-else-inline", InMemoryCatalogRestClient.GeneratedChildDelivery.INLINE,
                CatalogGraphParityScenarios.conditionSeveralIf(false)),
            deliveryCase("condition-several-if-with-else-inline", InMemoryCatalogRestClient.GeneratedChildDelivery.INLINE,
                CatalogGraphParityScenarios.conditionSeveralIf(true)),
            deliveryCase("try-catch-multi-catch-no-finally-inline", InMemoryCatalogRestClient.GeneratedChildDelivery.INLINE,
                CatalogGraphParityScenarios.tryCatchSeveralCatch(false)),
            deliveryCase("try-catch-multi-catch-with-finally-inline", InMemoryCatalogRestClient.GeneratedChildDelivery.INLINE,
                CatalogGraphParityScenarios.tryCatchSeveralCatch(true)),
            deliveryCase("split-async-two-branches-inline", InMemoryCatalogRestClient.GeneratedChildDelivery.INLINE,
                CatalogGraphParityScenarios.splitAsyncBranches(2)),
            deliveryCase("split-async-three-branches-inline", InMemoryCatalogRestClient.GeneratedChildDelivery.INLINE,
                CatalogGraphParityScenarios.splitAsyncBranches(3)),
            deliveryCase("split-2-without-main-inline", InMemoryCatalogRestClient.GeneratedChildDelivery.INLINE,
                CatalogGraphParityScenarios.split2Graph(false, 2)),
            deliveryCase("split-2-with-main-and-extra-inline", InMemoryCatalogRestClient.GeneratedChildDelivery.INLINE,
                CatalogGraphParityScenarios.split2Graph(true, 2)),
            deliveryCase("circuit-breaker-inline", InMemoryCatalogRestClient.GeneratedChildDelivery.INLINE,
                CatalogGraphParityScenarios.circuitBreaker()),
            deliveryCase("loop-2-inline", InMemoryCatalogRestClient.GeneratedChildDelivery.INLINE,
                CatalogGraphParityScenarios.loopWithBody()),
            deliveryCase("nested-condition-in-try-inline", InMemoryCatalogRestClient.GeneratedChildDelivery.INLINE,
                CatalogGraphParityScenarios.nestedConditionInTry()),
            deliveryCase("condition-one-if-read-back", InMemoryCatalogRestClient.GeneratedChildDelivery.READ_BACK,
                CatalogGraphParityScenarios.conditionOneIf(false)),
            deliveryCase("try-catch-read-back", InMemoryCatalogRestClient.GeneratedChildDelivery.READ_BACK,
                CatalogGraphParityScenarios.tryCatchSeveralCatch(false)),
            deliveryCase("split-async-read-back", InMemoryCatalogRestClient.GeneratedChildDelivery.READ_BACK,
                CatalogGraphParityScenarios.splitAsyncBranches(3)))
        .flatMap(arguments -> Stream.of(arguments));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("optionalChildDriftGuard")
  void optionalChildPruneAndExtraBranchCreateStayAligned(String caseName, ChainPlanGraph desired) {
    harness.catalog().setGeneratedChildDelivery(InMemoryCatalogRestClient.GeneratedChildDelivery.INLINE);
    assertParity(desired);
  }

  static Stream<Arguments> optionalChildDriftGuard() {
    return Stream.of(
        Arguments.of(
            "condition-without-else-prunes-catalog-default",
            CatalogGraphParityScenarios.conditionWithoutElseDriftGuard()),
        Arguments.of(
            "try-catch-without-finally-prunes-catalog-default",
            CatalogGraphParityScenarios.tryCatchSeveralCatch(false)),
        Arguments.of(
            "split-2-without-main-prunes-catalog-default",
            CatalogGraphParityScenarios.split2Graph(false, 2)));
  }

  private void assertParity(ChainPlanGraph desired) {
    ChainPlanGraph current = CatalogGraphParityScenarios.triggerOnlyCurrent();

    CatalogGraphMaterializeResult createResult = harness.create(desired);
    assertTrue(createResult.succeeded(), createResult.error());
    ImportedChainPlan createImport = harness.importCatalog(createResult.materializationMap());

    CatalogGraphMaterializeResult editResult = harness.edit(current, desired);
    assertTrue(editResult.succeeded(), editResult.error());
    ImportedChainPlan editImport = harness.importCatalog(editResult.materializationMap());

    CatalogGraphParityAssertions.assertCreateEditParity(
        desired,
        createResult.materializationMap(),
        createImport,
        editResult.materializationMap(),
        editImport);
  }

  private static Arguments deliveryCase(
      String caseName,
      InMemoryCatalogRestClient.GeneratedChildDelivery delivery,
      ChainPlanGraph desired) {
    return Arguments.of(caseName, delivery, desired);
  }
}
