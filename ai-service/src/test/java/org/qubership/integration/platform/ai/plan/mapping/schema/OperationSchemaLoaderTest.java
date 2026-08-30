package org.qubership.integration.platform.ai.plan.mapping.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsReadCache;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsLookupService;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogToolSupport;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

class OperationSchemaLoaderTest {

  private static final String COMPILATION_ID = "comp-1";

  private CompilationArtifacts artifacts;
  private OperationSchemaLoader loader;

  @AfterEach
  void clearMdc() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
  }

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    artifacts =
        new CompilationArtifacts(
            new InMemoryArtifactBlobStore(),
            mapper,
            Clock.fixed(Instant.parse("2026-07-30T09:00:00Z"), ZoneOffset.UTC));
    RecordingCatalogRestClient catalog = RecordingCatalogRestClient.withJsonMaps();
    loader = new CatalogOperationSchemaLoader(catalog, artifacts, mapper);
  }

  @Test
  void loadCallsSchemasFullOnceAndDoesNotSearch() {
    RecordingCatalogRestClient catalog = RecordingCatalogRestClient.withJsonMaps();
    OperationSchemaLoader localLoader =
        new CatalogOperationSchemaLoader(catalog, artifacts, new ObjectMapper());

    OperationSchemaMaps maps = localLoader.load("op-1");
    localLoader.load("op-1");

    assertTrue(maps.requestByContentType().containsKey("application/json"));
    assertEquals(List.of("getOperationSchemas:op-1:full"), catalog.calls());
  }

  @Test
  void persistRequestWritesSha256AndProvenance() {
    MappingSchemaSide side =
        loader.persistRequest("comp-1", "call-1", "op-1", "application/json");

    assertEquals(MappingPort.REQUEST, side.direction());
    assertFalse(side.sha256().isBlank());
    assertTrue(side.provenance().contains("contentType=application/json"));
    assertTrue(artifacts.latest("comp-1", Kind.MAPPING_SCHEMA_SIDE).isPresent());
  }

  @Test
  void missingContentTypeFailsClosed() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> loader.persistRequest("comp-1", "call-1", "op-1", "text/xml"));

    assertTrue(ex.getMessage().contains("op-1"));
    assertTrue(ex.getMessage().contains("text/xml"));
  }

  @Test
  void missingResponseCodeFailsClosed() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                loader.persistResponse("comp-1", "call-1", "op-1", "application/json", "  "));

    assertTrue(ex.getMessage().contains("responseCode"));
  }

  @Test
  void missingResponseSchemaIncludesResponseCode() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> loader.persistResponse("comp-1", "call-1", "op-1", "text/xml", "201"));

    assertTrue(ex.getMessage().contains("op-1"));
    assertTrue(ex.getMessage().contains("text/xml"));
    assertTrue(ex.getMessage().contains("responseCode=201"));
  }

  @Test
  void flatAsyncResponseStatusSkipsInnerContentTypeMap() {
    RecordingCatalogRestClient catalog = RecordingCatalogRestClient.withFlatAsyncResponseSchema();
    OperationSchemaLoader localLoader =
        new CatalogOperationSchemaLoader(catalog, artifacts, new ObjectMapper());

    OperationSchemaMaps maps = localLoader.load("op-1");

    assertTrue(maps.requestByContentType().containsKey("application/json"));
    assertTrue(maps.responseByStatusThenContentType().get("message").isEmpty());
  }

  @Test
  void searchAndListToolsDoNotCallSchemaEndpoints() throws Exception {
    RecordingCatalogRestClient catalog = RecordingCatalogRestClient.withJsonMaps();
    CatalogToolSupport support = new CatalogToolSupport();
    Field mapperField = CatalogToolSupport.class.getDeclaredField("objectMapper");
    mapperField.setAccessible(true);
    mapperField.set(support, new ObjectMapper());
    ConversationCatalogCache cache = new ConversationCatalogCache(new CatalogOperationsReadCache(catalog));
    CatalogSystemReadTool readTool =
        new CatalogSystemReadTool(
            catalog, new CatalogOperationsLookupService(cache), support);
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-schema-tools");

    readTool.searchCatalogSystemsJson("Petstore Ext");
    readTool.listCatalogOperationsJson("spec-1", "sys-1", null);

    assertTrue(
        catalog.calls().stream().noneMatch(call -> call.startsWith("getOperationSchemas")),
        catalog.calls().toString());
    assertTrue(
        catalog.calls().stream().noneMatch(call -> call.startsWith("getOperationRequestSchema")),
        catalog.calls().toString());
    assertTrue(
        catalog.calls().stream().noneMatch(call -> call.startsWith("getOperationResponseSchema")),
        catalog.calls().toString());
  }
}
