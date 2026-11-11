package org.qubership.integration.platform.ai.plan.workdocument.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.OperationDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.OperationSchemaMapsDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.SpecificationDto;
import org.qubership.integration.platform.ai.plan.workdocument.ResolvedWorkBinding;

class WorkContractMaterialTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void loadsPinnedRequestSuccessAndFailureSchemasWithNestedReferences() throws Exception {
    JsonNode request = schemaWithRef("OrderId");
    JsonNode success = schemaWithRef("TaskId");
    JsonNode failure = schemaWithRef("ErrorBody");
    AtomicInteger calls = new AtomicInteger();
    CatalogRestClient catalog =
        catalog(
            (name, args) -> {
              calls.incrementAndGet();
              return switch (name) {
                case "getOperation" ->
                    new OperationDto("op-create", "createTask", "POST", "/tasks", "model-create");
                case "getModel" ->
                    new SpecificationDto("spec-create", "create", "group-create", "sys-wfm", "2024.4");
                case "getOperationSchemas" -> maps(request, success, failure);
                case "getOperationRequestSchema" -> request;
                case "getOperationResponseSchema" ->
                    "200".equals(args[2]) ? success : "400".equals(args[2]) ? failure : null;
                default -> throw new UnsupportedOperationException(name);
              };
            });

    ContractMaterial loaded =
        new WorkContractMaterial(catalog)
            .load(
                new ResolvedWorkBinding(
                    "sys-wfm",
                    "2024.4",
                    "op-create",
                    "http",
                    "POST",
                    "/tasks",
                    List.of("spec-create"),
                    List.of("request", "success", "failure")));

    ContractMaterial.Ready ready = assertInstanceOf(ContractMaterial.Ready.class, loaded);
    assertEquals("spec-create", ready.contractReference());
    assertEquals("op-create", ready.operationId());
    assertEquals("2024.4", ready.version());
    assertEquals(List.of("request", "success", "failure"), ready.ports().stream().map(PortSchemaMaterial::port).toList());
    PortSchemaMaterial requestPort = ready.ports().get(0);
    assertEquals("spec-create", requestPort.contractReference());
    assertEquals("op-create", requestPort.operationId());
    assertEquals("2024.4", requestPort.version());
    assertEquals(sha256(request), requestPort.contentHash());
    assertEquals("#/definitions/OrderId", requestPort.schema().at("/properties/body/$ref").asText());
    assertEquals("string", requestPort.schema().at("/properties/nested/properties/name/type").asText());
    assertEquals("object", ready.ports().get(1).schema().at("/definitions/TaskId/type").asText());
    assertEquals("#/definitions/ErrorBody", ready.ports().get(2).schema().at("/properties/body/$ref").asText());
    assertFalse(ready.ports().get(1).schema().equals(ready.ports().get(2).schema()));
    assertEquals(sha256(failure), ready.ports().get(2).contentHash());
    assertTrue(calls.get() >= 5);
    assertFalse(requestPort.schema().isEmpty());
  }

  @Test
  void missingFailureSchemaIsNotReplacedWithAnEmptySchema() throws Exception {
    JsonNode request = schemaWithRef("OrderId");
    JsonNode success = schemaWithRef("TaskId");
    CatalogRestClient catalog =
        catalog(
            (name, args) ->
                switch (name) {
                  case "getOperation" ->
                      new OperationDto("op-create", "createTask", "POST", "/tasks", "model-create");
                  case "getModel" ->
                      new SpecificationDto("spec-create", "create", "group-create", "sys-wfm", "2024.4");
                  case "getOperationSchemas" -> maps(request, success, JSON.createObjectNode());
                  case "getOperationRequestSchema" -> request;
                  case "getOperationResponseSchema" -> "200".equals(args[2]) ? success : JSON.createObjectNode();
                  default -> throw new UnsupportedOperationException(name);
                });

    ContractMaterial loaded = new WorkContractMaterial(catalog).load(serviceBinding("spec-create", "2024.4", "op-create"));

    ContractMaterial.MissingSchema missing = assertInstanceOf(ContractMaterial.MissingSchema.class, loaded);
    assertEquals("failure", missing.port());
    assertEquals("op-create", missing.operationId());
    assertEquals("2024.4", missing.version());
    assertFalse(missing.reason().isBlank());
  }

  @Test
  void pinnedVersionMismatchDoesNotLoadTheOtherSpecification() throws Exception {
    JsonNode request = schemaWithRef("OrderId");
    AtomicInteger schemaReads = new AtomicInteger();
    CatalogRestClient catalog =
        catalog(
            (name, args) -> {
              if ("getOperationSchemas".equals(name) || name.startsWith("getOperationR")) {
                schemaReads.incrementAndGet();
              }
              return switch (name) {
                case "getOperation" ->
                    new OperationDto("op-create", "createTask", "POST", "/tasks", "model-create");
                case "getModel" ->
                    new SpecificationDto("spec-create", "create", "group-create", "sys-wfm", "2025.1");
                case "getOperationSchemas" -> maps(request, request, request);
                case "getOperationRequestSchema", "getOperationResponseSchema" -> request;
                default -> throw new UnsupportedOperationException(name);
              };
            });

    ContractMaterial loaded = new WorkContractMaterial(catalog).load(serviceBinding("spec-create", "2024.4", "op-create"));

    ContractMaterial.Incompatible incompatible = assertInstanceOf(ContractMaterial.Incompatible.class, loaded);
    assertEquals("2024.4", incompatible.version());
    assertEquals("op-create", incompatible.operationId());
    assertEquals(0, schemaReads.get());
    assertTrue(incompatible.reason().contains("2024.4"));
    assertTrue(incompatible.reason().contains("2025.1"));
  }

  @Test
  void matchingVersionWithADifferentSpecificationIdIsIncompatible() throws Exception {
    JsonNode request = schemaWithRef("OrderId");
    AtomicInteger schemaReads = new AtomicInteger();
    CatalogRestClient catalog =
        catalog(
            (name, args) -> {
              if ("getOperationSchemas".equals(name) || name.startsWith("getOperationR")) {
                schemaReads.incrementAndGet();
              }
              return switch (name) {
                case "getOperation" ->
                    new OperationDto("op-create", "createTask", "POST", "/tasks", "model-other");
                case "getModel" ->
                    new SpecificationDto("spec-other", "other", "group-other", "sys-wfm", "2024.4");
                case "getOperationSchemas" -> maps(request, request, request);
                case "getOperationRequestSchema", "getOperationResponseSchema" -> request;
                default -> throw new UnsupportedOperationException(name);
              };
            });

    ContractMaterial loaded = new WorkContractMaterial(catalog).load(serviceBinding("spec-create", "2024.4", "op-create"));

    ContractMaterial.Incompatible incompatible = assertInstanceOf(ContractMaterial.Incompatible.class, loaded);
    assertEquals("spec-create", incompatible.contractReference());
    assertEquals("op-create", incompatible.operationId());
    assertEquals("2024.4", incompatible.version());
    assertEquals(0, schemaReads.get());
    assertTrue(incompatible.reason().contains("spec-create"));
    assertTrue(incompatible.reason().contains("spec-other"));
  }

  @Test
  void twoSuccessCodesAreANamedGap() throws Exception {
    JsonNode first = schemaWithRef("Created");
    JsonNode second = schemaWithRef("Accepted");
    Map<String, JsonNode> responses = new LinkedHashMap<>();
    responses.put("200", first);
    responses.put("201", second);
    CatalogRestClient catalog = catalog(statusCatalog(responses));

    ContractMaterial loaded =
        new WorkContractMaterial(catalog).load(portBinding("spec-create", "op-create", "success"));

    ContractMaterial.Gap gap = assertInstanceOf(ContractMaterial.Gap.class, loaded);
    assertEquals("success", gap.port());
    assertEquals(List.of("200", "201"), gap.codes());
    assertTrue(gap.reason().contains("200"));
    assertTrue(gap.reason().contains("201"));
    assertFalse(loaded instanceof ContractMaterial.Ready);
  }

  @Test
  void successCode204IsLoaded() throws Exception {
    JsonNode body = schemaWithRef("NoContent");
    CatalogRestClient catalog = catalog(statusCatalog(Map.of("204", body)));

    ContractMaterial loaded =
        new WorkContractMaterial(catalog).load(portBinding("spec-create", "op-create", "success"));

    ContractMaterial.Ready ready = assertInstanceOf(ContractMaterial.Ready.class, loaded);
    assertEquals("#/definitions/NoContent", ready.ports().get(0).schema().at("/properties/body/$ref").asText());
    assertEquals(sha256(body), ready.ports().get(0).contentHash());
  }

  @Test
  void defaultAloneIsTheSuccessBodyAndNotAFailureSchema() throws Exception {
    JsonNode body = schemaWithRef("Payload");
    CatalogRestClient catalog = catalog(statusCatalog(Map.of("default", body)));
    WorkContractMaterial material = new WorkContractMaterial(catalog);

    ContractMaterial.Ready success =
        assertInstanceOf(
            ContractMaterial.Ready.class, material.load(portBinding("spec-create", "op-create", "success")));
    assertEquals("#/definitions/Payload", success.ports().get(0).schema().at("/properties/body/$ref").asText());

    ContractMaterial failure = material.load(portBinding("spec-create", "op-create", "failure"));
    ContractMaterial.MissingSchema missing = assertInstanceOf(ContractMaterial.MissingSchema.class, failure);
    assertEquals("failure", missing.port());
    assertFalse(failure instanceof ContractMaterial.Ready);
  }

  @Test
  void asyncApiTriggerBodyLoadsFromResponseSchemas() throws Exception {
    JsonNode message = schemaWithRef("ProcessEvent");
    CatalogRestClient catalog =
        catalog(
            (name, args) ->
                switch (name) {
                  case "getOperation" ->
                      new OperationDto("op-trigger", "onEvent", "subscribe", "process", "model-trigger");
                  case "getModel" ->
                      new SpecificationDto("spec-trigger", "events", "group", "sys", "2024.4");
                  case "getOperationSchemas" ->
                      new OperationSchemaMapsDto("op-trigger", Map.of(), Map.of("processEvent", message));
                  case "getOperationResponseSchema" -> message;
                  case "getOperationRequestSchema" -> JSON.createObjectNode();
                  default -> throw new UnsupportedOperationException(name);
                });

    ContractMaterial loaded =
        new WorkContractMaterial(catalog)
            .load(
                new ResolvedWorkBinding(
                    "sys",
                    "2024.4",
                    "op-trigger",
                    "async",
                    "subscribe",
                    "process",
                    List.of("spec-trigger"),
                    List.of("payload")));

    ContractMaterial.Ready ready = assertInstanceOf(ContractMaterial.Ready.class, loaded);
    assertEquals("payload", ready.ports().get(0).port());
    assertEquals("spec-trigger", ready.contractReference());
    assertEquals("#/definitions/ProcessEvent", ready.ports().get(0).schema().at("/properties/body/$ref").asText());
    assertEquals(sha256(message), ready.ports().get(0).contentHash());
  }

  @Test
  void composedSchemaIsPresentAndAnEmptyBodyIsMissing() throws Exception {
    JsonNode composed =
        JSON.readTree(
            """
            { "allOf": [ { "$ref": "#/definitions/Order" } ], "definitions": { "Order": { "type": "object" } } }
            """);
    CatalogRestClient composedCatalog =
        catalog(
            (name, args) ->
                switch (name) {
                  case "getOperation" ->
                      new OperationDto("op-create", "createTask", "POST", "/tasks", "model-create");
                  case "getModel" ->
                      new SpecificationDto("spec-create", "create", "group-create", "sys-wfm", "2024.4");
                  case "getOperationSchemas" -> {
                    Map<String, JsonNode> request = new LinkedHashMap<>();
                    request.put("application/json", composed);
                    yield new OperationSchemaMapsDto("op-create", request, Map.of());
                  }
                  case "getOperationRequestSchema" -> composed;
                  default -> throw new UnsupportedOperationException(name);
                });

    ContractMaterial.Ready ready =
        assertInstanceOf(
            ContractMaterial.Ready.class,
            new WorkContractMaterial(composedCatalog).load(portBinding("spec-create", "op-create", "request")));
    assertEquals("#/definitions/Order", ready.ports().get(0).schema().at("/allOf/0/$ref").asText());

    JsonNode empty = JSON.createObjectNode();
    CatalogRestClient emptyCatalog =
        catalog(
            (name, args) ->
                switch (name) {
                  case "getOperation" ->
                      new OperationDto("op-create", "createTask", "POST", "/tasks", "model-create");
                  case "getModel" ->
                      new SpecificationDto("spec-create", "create", "group-create", "sys-wfm", "2024.4");
                  case "getOperationSchemas" -> {
                    Map<String, JsonNode> request = new LinkedHashMap<>();
                    request.put("application/json", empty);
                    yield new OperationSchemaMapsDto("op-create", request, Map.of());
                  }
                  case "getOperationRequestSchema" -> empty;
                  default -> throw new UnsupportedOperationException(name);
                });

    ContractMaterial.MissingSchema missing =
        assertInstanceOf(
            ContractMaterial.MissingSchema.class,
            new WorkContractMaterial(emptyCatalog).load(portBinding("spec-create", "op-create", "request")));
    assertEquals("request", missing.port());
  }

  @Test
  void catalogOperationIdMismatchDoesNotReplaceTheSelectedOperation() throws Exception {
    JsonNode request = schemaWithRef("OrderId");
    AtomicInteger schemaReads = new AtomicInteger();
    CatalogRestClient catalog =
        catalog(
            (name, args) -> {
              if ("getOperationSchemas".equals(name) || name.startsWith("getOperationR")) {
                schemaReads.incrementAndGet();
              }
              return switch (name) {
                case "getOperation" ->
                    new OperationDto("op-other", "other", "POST", "/other", "model-other");
                case "getModel" ->
                    new SpecificationDto("spec-other", "other", "group-other", "sys-wfm", "2024.4");
                case "getOperationSchemas" -> maps(request, request, request);
                case "getOperationRequestSchema", "getOperationResponseSchema" -> request;
                default -> throw new UnsupportedOperationException(name);
              };
            });

    ContractMaterial loaded = new WorkContractMaterial(catalog).load(serviceBinding("spec-create", "2024.4", "op-create"));

    ContractMaterial.Incompatible incompatible = assertInstanceOf(ContractMaterial.Incompatible.class, loaded);
    assertEquals("op-create", incompatible.operationId());
    assertEquals(0, schemaReads.get());
    assertTrue(incompatible.reason().contains("op-create"));
    assertTrue(incompatible.reason().contains("op-other"));
  }

  @Test
  void apiHubContractWithoutACatalogSourceIsUnavailable() {
    AtomicInteger calls = new AtomicInteger();
    CatalogRestClient catalog =
        catalog(
            (name, args) -> {
              calls.incrementAndGet();
              throw new IllegalStateException("catalog must not be called");
            });

    ContractMaterial loaded =
        new WorkContractMaterial(catalog)
            .load(
                new ResolvedWorkBinding(
                    "pkg.wfm",
                    "2024.4",
                    "op-hub",
                    "http",
                    "POST",
                    "/tasks",
                    List.of("apihub:pkg.wfm@2024.4"),
                    List.of("request", "success", "failure")));

    ContractMaterial.Unavailable unavailable = assertInstanceOf(ContractMaterial.Unavailable.class, loaded);
    assertEquals("apihub:pkg.wfm@2024.4", unavailable.contractReference());
    assertEquals("op-hub", unavailable.operationId());
    assertEquals("2024.4", unavailable.version());
    assertEquals(0, calls.get());
    assertTrue(unavailable.reason().contains("apihub:pkg.wfm@2024.4"));
  }

  @Test
  void catalogReadFailureStaysAReadFailure() {
    CatalogRestClient catalog =
        catalog(
            (name, args) -> {
              throw new IllegalStateException("catalog returned HTTP 503");
            });

    ContractMaterial loaded = new WorkContractMaterial(catalog).load(serviceBinding("spec-create", "2024.4", "op-create"));

    ContractMaterial.ReadFailed failed = assertInstanceOf(ContractMaterial.ReadFailed.class, loaded);
    assertEquals("op-create", failed.operationId());
    assertTrue(failed.reason().contains("HTTP 503"));
  }

  @Test
  void triggerPayloadAndReplyRequestKeepSeparateContracts() throws Exception {
    JsonNode trigger = schemaWithRef("Process");
    JsonNode reply = schemaWithRef("ReplyBody");
    CatalogRestClient catalog =
        catalog(
            (name, args) -> {
              String operationId = String.valueOf(args[0]);
              if ("getModel".equals(name) && operationId.startsWith("model-")) {
                operationId = operationId.substring("model-".length());
              }
              JsonNode body = "op-trigger".equals(operationId) ? trigger : reply;
              String selected = operationId;
              return switch (name) {
                case "getOperation" ->
                    new OperationDto(selected, selected, "POST", "/" + selected, "model-" + selected);
                case "getModel" ->
                    new SpecificationDto("spec-" + selected, selected, "group", "sys", "2024.4");
                case "getOperationSchemas" -> mapsFor(operationId, body, body, body);
                case "getOperationRequestSchema" -> body;
                case "getOperationResponseSchema" -> body;
                default -> throw new UnsupportedOperationException(name);
              };
            });
    WorkContractMaterial material = new WorkContractMaterial(catalog);

    ContractMaterial.Ready triggerReady =
        assertInstanceOf(
            ContractMaterial.Ready.class,
            material.load(
                new ResolvedWorkBinding(
                    "sys", "2024.4", "op-trigger", "http", "POST", "/in", List.of("spec-op-trigger"), List.of("payload"))));
    ContractMaterial.Ready replyReady =
        assertInstanceOf(
            ContractMaterial.Ready.class,
            material.load(
                new ResolvedWorkBinding(
                    "sys", "2024.4", "op-reply", "http", "POST", "/out", List.of("spec-op-reply"), List.of("request"))));

    assertEquals("payload", triggerReady.ports().get(0).port());
    assertEquals("spec-op-trigger", triggerReady.contractReference());
    assertEquals("#/definitions/Process", triggerReady.ports().get(0).schema().at("/properties/body/$ref").asText());
    assertEquals("request", replyReady.ports().get(0).port());
    assertEquals("spec-op-reply", replyReady.contractReference());
    assertEquals("#/definitions/ReplyBody", replyReady.ports().get(0).schema().at("/properties/body/$ref").asText());
    assertFalse(triggerReady.ports().get(0).contentHash().equals(replyReady.ports().get(0).contentHash()));
  }

  @Test
  void schemaReadPathsUseTheCatalogOperationMethods() {
    assertEquals("/v1/operations/op-create", WorkContractMaterial.catalogPath("getOperation", "op-create"));
    assertEquals("/v1/models/model-create", WorkContractMaterial.catalogPath("getModel", "model-create"));
    assertEquals(
        "/v1/operations/op-create/schemas?mode=full",
        WorkContractMaterial.catalogPath("getOperationSchemas", "op-create", "full"));
    assertEquals(
        "/v1/operations/op-create/schemas/request?contentType=application%2Fjson",
        WorkContractMaterial.catalogPath("getOperationRequestSchema", "op-create", "application/json"));
    assertEquals(
        "/v1/operations/op-create/schemas/response?contentType=application%2Fjson&responseCode=400",
        WorkContractMaterial.catalogPath("getOperationResponseSchema", "op-create", "application/json", "400"));
  }

  @Test
  void resolutionSeamLoadsSchemasFromTheSameCatalogClient() throws Exception {
    JsonNode request = schemaWithRef("OrderId");
    JsonNode success = schemaWithRef("TaskId");
    JsonNode failure = schemaWithRef("ErrorBody");
    CatalogRestClient catalog =
        catalog(
            (name, args) ->
                switch (name) {
                  case "getOperation" ->
                      new OperationDto("op-create", "createTask", "POST", "/tasks", "model-create");
                  case "getModel" ->
                      new SpecificationDto("spec-create", "create", "group-create", "sys-wfm", "2024.4");
                  case "getOperationSchemas" -> maps(request, success, failure);
                  case "getOperationRequestSchema" -> request;
                  case "getOperationResponseSchema" -> "200".equals(args[2]) ? success : failure;
                  default -> throw new UnsupportedOperationException(name);
                });
    CatalogResolution resolution =
        new ResolveApiOperationSeam(
            org.mockito.Mockito.mock(
                org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationLookup.class),
            org.mockito.Mockito.mock(
                org.qubership.integration.platform.ai.plan.CatalogFirstApiHubDiscoveryTool.class),
            catalog);

    ContractMaterial.Ready ready =
        assertInstanceOf(ContractMaterial.Ready.class, resolution.loadContract(serviceBinding("spec-create", "2024.4", "op-create")));

    assertEquals(3, ready.ports().size());
    assertEquals("op-create", ready.operationId());
  }

  private static JsonNode schemaWithRef(String definitionName) throws Exception {
    return JSON.readTree(
        """
        {
          "type": "object",
          "properties": {
            "body": { "$ref": "#/definitions/%s" },
            "nested": { "type": "object", "properties": { "name": { "type": "string" } } }
          },
          "definitions": {
            "%s": { "type": "object", "properties": { "id": { "type": "string" } }, "required": ["id"] }
          }
        }
        """
            .formatted(definitionName, definitionName));
  }

  private static Call statusCatalog(Map<String, JsonNode> responses) {
    return (name, args) ->
        switch (name) {
          case "getOperation" ->
              new OperationDto("op-create", "createTask", "POST", "/tasks", "model-create");
          case "getModel" ->
              new SpecificationDto("spec-create", "create", "group-create", "sys-wfm", "2024.4");
          case "getOperationSchemas" -> new OperationSchemaMapsDto("op-create", Map.of(), wrapResponses(responses));
          case "getOperationResponseSchema" -> responses.get(String.valueOf(args[2]));
          case "getOperationRequestSchema" -> JSON.createObjectNode();
          default -> throw new UnsupportedOperationException(name);
        };
  }

  private static Map<String, JsonNode> wrapResponses(Map<String, JsonNode> responses) {
    Map<String, JsonNode> wrapped = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : responses.entrySet()) {
      ObjectNode node = JSON.createObjectNode();
      node.set("application/json", entry.getValue());
      wrapped.put(entry.getKey(), node);
    }
    return wrapped;
  }

  private static ResolvedWorkBinding portBinding(String reference, String operationId, String port) {
    return new ResolvedWorkBinding(
        "sys-wfm", "2024.4", operationId, "http", "POST", "/tasks", List.of(reference), List.of(port));
  }

  private static ResolvedWorkBinding serviceBinding(String reference, String version, String operationId) {
    return new ResolvedWorkBinding(
        "sys-wfm",
        version,
        operationId,
        "http",
        "POST",
        "/tasks",
        List.of(reference),
        List.of("request", "success", "failure"));
  }

  private static OperationSchemaMapsDto maps(JsonNode request, JsonNode success, JsonNode failure) {
    return mapsFor("op-create", request, success, failure);
  }

  private static OperationSchemaMapsDto mapsFor(
      String operationId, JsonNode request, JsonNode success, JsonNode failure) {
    Map<String, JsonNode> requestMap = new LinkedHashMap<>();
    requestMap.put("application/json", request);
    ObjectNode successWrap = JSON.createObjectNode();
    successWrap.set("application/json", success);
    ObjectNode failureWrap = JSON.createObjectNode();
    failureWrap.set("application/json", failure);
    Map<String, JsonNode> responses = new LinkedHashMap<>();
    responses.put("200", successWrap);
    responses.put("400", failureWrap);
    return new OperationSchemaMapsDto(operationId, requestMap, responses);
  }

  private static String sha256(JsonNode schema) throws Exception {
    byte[] bytes = JSON.writeValueAsBytes(schema);
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  @FunctionalInterface
  private interface Call {
    Object invoke(String name, Object[] args) throws Exception;
  }

  private static CatalogRestClient catalog(Call call) {
    return (CatalogRestClient)
        Proxy.newProxyInstance(
            CatalogRestClient.class.getClassLoader(),
            new Class<?>[] {CatalogRestClient.class},
            (proxy, method, args) -> {
              if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                  case "toString" -> "catalog-fake";
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == args[0];
                  default -> null;
                };
              }
              return call.invoke(method.getName(), args == null ? new Object[0] : args);
            });
  }
}
