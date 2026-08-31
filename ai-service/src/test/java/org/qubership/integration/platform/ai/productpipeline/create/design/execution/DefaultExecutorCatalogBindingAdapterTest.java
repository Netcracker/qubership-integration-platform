package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsLookupService;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsReadCache;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogToolSupport;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.plan.mapping.schema.RecordingCatalogRestClient;
import org.qubership.integration.platform.ai.plan.mapping.schema.SchemaLoaderTestSupport;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

class DefaultExecutorCatalogBindingAdapterTest {

  private static final String CONVERSATION_ID = "conv-schema-bind";
  private static final Instant FIXED = Instant.parse("2026-07-30T09:00:00Z");

  private CompilationArtifacts artifacts;
  private RecordingCatalogRestClient catalog;
  private DefaultExecutorCatalogBindingAdapter adapter;

  @BeforeEach
  void setUp() throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    artifacts =
        new CompilationArtifacts(
            new InMemoryArtifactBlobStore(),
            mapper,
            Clock.fixed(FIXED, ZoneOffset.UTC));
    catalog = RecordingCatalogRestClient.withJsonMaps();
    CatalogToolSupport support = new CatalogToolSupport();
    var mapperField = CatalogToolSupport.class.getDeclaredField("objectMapper");
    mapperField.setAccessible(true);
    mapperField.set(support, mapper);
    CatalogSystemReadTool readTool =
        new CatalogSystemReadTool(
            catalog,
            new CatalogOperationsLookupService(
                new ConversationCatalogCache(new CatalogOperationsReadCache(catalog))),
            support);
    adapter =
        new DefaultExecutorCatalogBindingAdapter(
            readTool, SchemaLoaderTestSupport.catalogLoader(catalog, artifacts, mapper));
  }

  @Test
  void resolvedBindingLoadsSchemasOnceAndPersistsBothSides() {
    List<BindingResolutionResult> results =
        adapter.resolve(
            CONVERSATION_ID,
            sampleOneCall(),
            List.of(v2Hint("call-1", "fact-1", "GET /pets", "sys-1", "op-1")),
            approved());

    assertInstanceOf(BindingResolutionResult.Resolved.class, results.getFirst());
    assertEquals(
        1,
        catalog.calls().stream().filter(call -> call.startsWith("getOperationSchemas")).count());
    List<CompilationArtifacts.Revision> sides =
        artifacts.history(CONVERSATION_ID, Kind.MAPPING_SCHEMA_SIDE);
    assertEquals(2, sides.size());
    MappingSchemaSide request =
        artifacts.payload(sides.get(0), MappingSchemaSide.class);
    MappingSchemaSide response =
        artifacts.payload(sides.get(1), MappingSchemaSide.class);
    assertEquals(MappingPort.REQUEST, request.direction());
    assertEquals(MappingPort.RESPONSE, response.direction());
    assertEquals("application/json", request.contentType());
    assertEquals("application/json", response.contentType());
    assertEquals("201", response.responseCode());
  }

  @Test
  void resolvedBindingDoesNotCallSearchSchemaEndpoints() {
    adapter.resolve(
        CONVERSATION_ID,
        sampleOneCall(),
        List.of(v2Hint("call-1", "fact-1", "GET /pets", "sys-1", "op-1")),
        approved());

    assertTrue(
        catalog.calls().stream().noneMatch(call -> call.startsWith("getOperationRequestSchema")));
    assertTrue(
        catalog.calls().stream().noneMatch(call -> call.startsWith("getOperationResponseSchema")));
  }

  @Test
  void httpSuccessPlusErrorPersistsRequestAndUnique2xx() throws Exception {
    catalog = RecordingCatalogRestClient.withJsonMapsAndErrorStatus();
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    CatalogToolSupport support = new CatalogToolSupport();
    var mapperField = CatalogToolSupport.class.getDeclaredField("objectMapper");
    mapperField.setAccessible(true);
    mapperField.set(support, mapper);
    CatalogSystemReadTool readTool =
        new CatalogSystemReadTool(
            catalog,
            new CatalogOperationsLookupService(
                new ConversationCatalogCache(new CatalogOperationsReadCache(catalog))),
            support);
    adapter =
        new DefaultExecutorCatalogBindingAdapter(
            readTool, SchemaLoaderTestSupport.catalogLoader(catalog, artifacts, mapper));

    adapter.resolve(
        CONVERSATION_ID,
        sampleOneCall(),
        List.of(v2Hint("call-1", "fact-1", "GET /pets", "sys-1", "op-1")),
        approved());

    List<CompilationArtifacts.Revision> sides =
        artifacts.history(CONVERSATION_ID, Kind.MAPPING_SCHEMA_SIDE);
    assertEquals(2, sides.size());
    MappingSchemaSide request = artifacts.payload(sides.get(0), MappingSchemaSide.class);
    MappingSchemaSide response = artifacts.payload(sides.get(1), MappingSchemaSide.class);
    assertEquals(MappingPort.REQUEST, request.direction());
    assertEquals(MappingPort.RESPONSE, response.direction());
    assertEquals("201", response.responseCode());
  }

  @Test
  void asyncMessagesPersistAsRequestForTriggerOutput() throws Exception {
    catalog = RecordingCatalogRestClient.withAsyncMessageSchemas();
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    CatalogToolSupport support = new CatalogToolSupport();
    var mapperField = CatalogToolSupport.class.getDeclaredField("objectMapper");
    mapperField.setAccessible(true);
    mapperField.set(support, mapper);
    CatalogSystemReadTool readTool =
        new CatalogSystemReadTool(
            catalog,
            new CatalogOperationsLookupService(
                new ConversationCatalogCache(new CatalogOperationsReadCache(catalog))),
            support);
    adapter =
        new DefaultExecutorCatalogBindingAdapter(
            readTool, SchemaLoaderTestSupport.catalogLoader(catalog, artifacts, mapper));

    adapter.resolve(
        CONVERSATION_ID,
        sampleOneCall(),
        List.of(v2Hint("call-1", "fact-1", "GET /pets", "sys-1", "op-1")),
        approved());

    List<CompilationArtifacts.Revision> sides =
        artifacts.history(CONVERSATION_ID, Kind.MAPPING_SCHEMA_SIDE);
    assertEquals(1, sides.size());
    MappingSchemaSide request =
        artifacts.payload(sides.getFirst(), MappingSchemaSide.class);
    assertEquals(MappingPort.REQUEST, request.direction());
    assertTrue(request.schema().has("oneOf"), request.schema().toString());
  }

  @Test
  void flatAsyncResponseSchemaSkipsResponsePersistAtBind() throws Exception {
    catalog = RecordingCatalogRestClient.withFlatAsyncResponseSchema();
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    CatalogToolSupport support = new CatalogToolSupport();
    var mapperField = CatalogToolSupport.class.getDeclaredField("objectMapper");
    mapperField.setAccessible(true);
    mapperField.set(support, mapper);
    CatalogSystemReadTool readTool =
        new CatalogSystemReadTool(
            catalog,
            new CatalogOperationsLookupService(
                new ConversationCatalogCache(new CatalogOperationsReadCache(catalog))),
            support);
    adapter =
        new DefaultExecutorCatalogBindingAdapter(
            readTool, SchemaLoaderTestSupport.catalogLoader(catalog, artifacts, mapper));

    adapter.resolve(
        CONVERSATION_ID,
        sampleOneCall(),
        List.of(v2Hint("call-1", "fact-1", "GET /pets", "sys-1", "op-1")),
        approved());

    List<CompilationArtifacts.Revision> sides =
        artifacts.history(CONVERSATION_ID, Kind.MAPPING_SCHEMA_SIDE);
    assertEquals(1, sides.size());
    assertEquals(
        MappingPort.REQUEST, artifacts.payload(sides.getFirst(), MappingSchemaSide.class).direction());
  }

  private static ApprovalRecordV2 approved() {
    return new ApprovalRecordV2(
        new CompilationArtifacts.Reference(
            CompilationArtifacts.Kind.IMPLEMENTATION_PLAN, "plan-1", "hash-plan"),
        "hash-plan",
        List.of(),
        "tester",
        "approved",
        FIXED,
        ApprovalPolicy.CATALOG_FIRST_V1,
        ApprovalPolicy.CATALOG_FIRST_V1_HASH,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static CatalogBindingHint v2Hint(
      String serviceCallId,
      String sourceFactId,
      String operationQuery,
      String systemId,
      String integrationOperationId) {
    return new CatalogBindingHint(
        "3",
        serviceCallId,
        sourceFactId,
        operationQuery,
        systemId,
        "sg-1",
        "spec-1",
        integrationOperationId,
        "http",
        "POST",
        "/tasks",
        "2024.4",
        FIXED,
        "evidence-" + serviceCallId);
  }

  private static ChainSemanticRevision sampleOneCall() {
    return SemanticFixtures.linear(
        "Pets",
        "revision-pets",
        "trigger-http",
        "node-call",
        "call-1",
        "GET /pets",
        "Petstore Ext",
        List.of(),
        List.of());
  }
}
