package org.qubership.integration.platform.ai.productpipeline.create;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import static java.util.Map.entry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphPatchArtifact;
import org.qubership.integration.platform.ai.productpipeline.artifact.PatchApplicability;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplyResult;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchArtifactFactory;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.ValidatedGraphPatchApplier;

/** Result of replaying one generator patch regression fixture. */
record GeneratorPatchRegressionResult(GraphPatchArtifact artifact, ChainPlanGraph graph) {}

/** Deterministic harness for promoted-generator graph-patch regression fixtures. */
final class GeneratorPatchRegressionHarness {

  private static final String FIXTURE_DIR =
      "product-pipeline/create/generator-regression";
  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
  private static final ObjectMapper JSON_MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  private final Map<String, GraphPatchOwnershipPolicy> pinnedOwnershipBySkill;
  private final CanonicalGraphDigest digest;
  private final ValidatedGraphPatchApplier validatedApplier;
  private final GraphPatchArtifactFactory artifactFactory;

  GeneratorPatchRegressionHarness() {
    this.pinnedOwnershipBySkill = pinnedOwnership();
    ObjectMapper mapper = new ObjectMapper();
    this.digest = new CanonicalGraphDigest(mapper);
    this.validatedApplier =
        new ValidatedGraphPatchApplier(new GraphPatchOwnershipValidator(), new GraphPatchApplier());
    this.artifactFactory = new GraphPatchArtifactFactory(digest);
  }

  CanonicalGraphDigest digest() {
    return digest;
  }

  GeneratorPatchRegressionResult run(GeneratorPatchRegressionCase fixture) {
    GraphPatchOwnershipPolicy ownership =
        Objects.requireNonNull(pinnedOwnershipBySkill.get(fixture.skillId()), fixture.skillId());
    GraphPatchExecutionContext context =
        new GraphPatchExecutionContext(
            fixture.caseId(),
            fixture.skillId(),
            "requirement-" + fixture.caseId(),
            digest.sha256(fixture.inputGraph()),
            "compiler-regression-v1",
            "24.4",
            fixture.requirementBrief(),
            fixture.consumedArtifacts(),
            fixture.inputGraph(),
            ownership,
            "");
    GraphPatchApplyResult applied = validatedApplier.apply(context, fixture.capturedPatch());
    if (!applied.validationResult().valid()) {
      throw new AssertionError(applied.validationResult().summary());
    }
    GraphPatchArtifact artifact =
        artifactFactory.create(context, fixture.capturedPatch(), applied.graph());
    return new GeneratorPatchRegressionResult(artifact, applied.graph());
  }

  static Stream<GeneratorPatchRegressionCase> loadCases() {
    GeneratorPatchRegressionHarness harness = new GeneratorPatchRegressionHarness();
    List<Path> fixturePaths = listFixturePaths();
    Set<String> seenCaseIds = new LinkedHashSet<>();
    List<GeneratorPatchRegressionCase> cases = new ArrayList<>();
    for (Path path : fixturePaths) {
      GeneratorPatchRegressionCase loaded = harness.loadOne(path, seenCaseIds);
      cases.add(loaded);
    }
    return cases.stream().sorted(Comparator.comparing(GeneratorPatchRegressionCase::caseId));
  }

  private GeneratorPatchRegressionCase loadOne(Path path, Set<String> seenCaseIds) {
    try (InputStream in = Files.newInputStream(path)) {
      JsonNode root = YAML_MAPPER.readTree(in);
      String caseId = text(root, "caseId");
      if (!seenCaseIds.add(caseId)) {
        throw new IllegalStateException("Duplicate caseId: " + caseId);
      }
      String skillId = text(root, "skillId");
      if (!pinnedOwnershipBySkill.containsKey(skillId)) {
        throw new IllegalStateException(
            "skillId '" + skillId + "' does not match pinned ownership for case " + caseId);
      }
      PatchApplicability expectedApplicability =
          PatchApplicability.valueOf(text(root, "expectedApplicability"));
      RequirementBrief requirementBrief =
          YAML_MAPPER.convertValue(root.get("requirementBrief"), RequirementBrief.class);
      Map<CompilationArtifacts.Kind, JsonNode> upstreamArtifacts =
          parseUpstreamArtifacts(root.get("upstreamArtifacts"), caseId);
      ChainPlanGraph inputGraph =
          YAML_MAPPER.convertValue(root.get("inputGraph"), ChainPlanGraph.class);
      GraphPatch capturedPatch = parseCapturedPatch(root.get("capturedPatch"), caseId);
      ChainPlanGraph expectedGraph =
          YAML_MAPPER.convertValue(root.get("expectedGraph"), ChainPlanGraph.class);

      List<CompilationArtifacts.Reference> consumedArtifacts =
          appendUpstreamArtifacts(caseId, skillId, upstreamArtifacts);

      GeneratorPatchRegressionCase fixture =
          new GeneratorPatchRegressionCase(
              caseId,
              skillId,
              requirementBrief,
              upstreamArtifacts,
              consumedArtifacts,
              inputGraph,
              capturedPatch,
              expectedApplicability,
              expectedGraph);

      GeneratorPatchRegressionResult replay = run(fixture);
      String expectedDigest = digest.sha256(expectedGraph);
      String replayDigest = digest.sha256(replay.graph());
      if (!expectedDigest.equals(replayDigest)) {
        throw new IllegalStateException(
            "Fixture "
                + caseId
                + " expected graph digest disagrees with deterministic replay:"
                + " expected="
                + expectedDigest
                + " replay="
                + replayDigest);
      }
      if (replay.artifact().applicability() != expectedApplicability) {
        throw new IllegalStateException(
            "Fixture "
                + caseId
                + " expectedApplicability "
                + expectedApplicability
                + " disagrees with replay "
                + replay.artifact().applicability());
      }
      return fixture;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load fixture " + path, e);
    }
  }

  private List<CompilationArtifacts.Reference> appendUpstreamArtifacts(
      String caseId,
      String skillId,
      Map<CompilationArtifacts.Kind, JsonNode> upstreamArtifacts) {
    CompilationArtifacts store =
        new CompilationArtifacts(new InMemoryArtifactBlobStore(), JSON_MAPPER, Clock.systemUTC());
    String compilationId = "regression-" + caseId;
    List<CompilationArtifacts.Reference> references = new ArrayList<>();
    for (Map.Entry<CompilationArtifacts.Kind, JsonNode> entry : upstreamArtifacts.entrySet()) {
      CompilationArtifacts.Revision revision =
          store.append(
              new CompilationArtifacts.AppendCommand(
                  compilationId,
                  entry.getKey(),
                  "1",
                  skillId,
                  "1",
                  entry.getValue(),
                  List.of(),
                  null));
      references.add(revision.reference());
    }
    return List.copyOf(references);
  }

  private static Map<CompilationArtifacts.Kind, JsonNode> parseUpstreamArtifacts(
      JsonNode node, String caseId) {
    if (node == null || node.isNull()) {
      return Map.of();
    }
    if (!node.isObject()) {
      throw new IllegalStateException("upstreamArtifacts must be an object for case " + caseId);
    }
    Map<CompilationArtifacts.Kind, JsonNode> artifacts = new EnumMap<>(CompilationArtifacts.Kind.class);
    node.properties()
        .forEach(
            entry -> {
              CompilationArtifacts.Kind kind;
              try {
                kind = CompilationArtifacts.Kind.valueOf(entry.getKey());
              } catch (IllegalArgumentException ex) {
                throw new IllegalStateException(
                    "Unknown artifact kind '"
                        + entry.getKey()
                        + "' in case "
                        + caseId,
                    ex);
              }
              artifacts.put(kind, entry.getValue());
            });
    return Map.copyOf(artifacts);
  }

  private static GraphPatch parseCapturedPatch(JsonNode raw, String caseId) {
    if (raw == null || raw.isNull()) {
      throw new IllegalStateException("capturedPatch is required for case " + caseId);
    }
    ObjectNode normalized = raw.deepCopy();
    normalizePropertyPatches(normalized);
    normalizeChainPatches(normalized);
    if (!normalized.has("patchId") || normalized.get("patchId").isNull()) {
      normalized.put("patchId", caseId);
    }
    if (!normalized.hasNonNull("usedKnowledgeRefs")) {
      normalized.set("usedKnowledgeRefs", JSON_MAPPER.createArrayNode());
    }
    if (!normalized.hasNonNull("chainPatches")) {
      normalized.set("chainPatches", JSON_MAPPER.createArrayNode());
    }
    if (!normalized.hasNonNull("rationale")) {
      normalized.put("rationale", "");
    }
    return YAML_MAPPER.convertValue(normalized, GraphPatch.class);
  }

  private static void normalizePropertyPatches(ObjectNode patch) {
    JsonNode propertyPatches = patch.get("propertyPatches");
    if (propertyPatches == null || !propertyPatches.isArray()) {
      return;
    }
    ArrayNode normalized = JSON_MAPPER.createArrayNode();
    for (JsonNode item : propertyPatches) {
      if (item == null || item.isNull()) {
        continue;
      }
      ObjectNode entry = item.deepCopy();
      if (!entry.has("property") && entry.has("key")) {
        ObjectNode property = JSON_MAPPER.createObjectNode();
        property.put("key", entry.get("key").asText());
        property.put("value", jsonValueAsString(entry.get("value")));
        entry.set("property", property);
        entry.remove("key");
        entry.remove("value");
      } else if (entry.has("property") && entry.get("property").isObject()) {
        ObjectNode property = (ObjectNode) entry.get("property");
        if (property.has("value") && !property.get("value").isTextual()) {
          property.put("value", jsonValueAsString(property.get("value")));
        }
      }
      normalized.add(entry);
    }
    patch.set("propertyPatches", normalized);
  }

  private static void normalizeChainPatches(ObjectNode patch) {
    JsonNode chainPatches = patch.get("chainPatches");
    if (chainPatches == null || !chainPatches.isArray()) {
      return;
    }
    ArrayNode normalized = JSON_MAPPER.createArrayNode();
    for (JsonNode item : chainPatches) {
      if (item == null || item.isNull()) {
        continue;
      }
      ObjectNode entry = item.deepCopy();
      if (!entry.has("property") && entry.has("key")) {
        ObjectNode property = JSON_MAPPER.createObjectNode();
        property.put("key", entry.get("key").asText());
        property.put("value", jsonValueAsString(entry.get("value")));
        entry.set("property", property);
        entry.remove("key");
        entry.remove("value");
      }
      normalized.add(entry);
    }
    patch.set("chainPatches", normalized);
  }

  private static String jsonValueAsString(JsonNode value) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isTextual()) {
      return value.asText();
    }
    try {
      return JSON_MAPPER.writeValueAsString(value);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String text(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || value.isNull() || value.asText().isBlank()) {
      throw new IllegalStateException("Missing required field: " + field);
    }
    return value.asText();
  }

  private static List<Path> listFixturePaths() {
    try {
      Path dir =
          Path.of(
              Objects.requireNonNull(
                      GeneratorPatchRegressionHarness.class
                          .getClassLoader()
                          .getResource(FIXTURE_DIR),
                      "Missing classpath directory " + FIXTURE_DIR)
                  .toURI());
      List<Path> paths = new ArrayList<>();
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.yaml")) {
        for (Path path : stream) {
          paths.add(path);
        }
      }
      if (paths.isEmpty()) {
        throw new IllegalStateException("No fixtures found under " + FIXTURE_DIR);
      }
      paths.sort(Comparator.comparing(path -> path.getFileName().toString()));
      return paths;
    } catch (Exception e) {
      throw new IllegalStateException("Cannot list fixtures under " + FIXTURE_DIR, e);
    }
  }

  private static Map<String, GraphPatchOwnershipPolicy> pinnedOwnership() {
    Map<String, GraphPatchOwnershipPolicy> ownership = new LinkedHashMap<>();
    ownership.put(
        "cip-auth-generator",
        new GraphPatchOwnershipPolicy(
            false,
            false,
            Set.of(),
            Set.of(),
            Map.of(
                "service-call", Set.of("authorizationConfiguration", "systemType"),
                "http-sender", Set.of("authorizationConfiguration"),
                "kafka-sender-2", Set.of("authorizationConfiguration"),
                "graphql-sender", Set.of("authorizationConfiguration"),
                "rabbitmq-sender-2", Set.of("authorizationConfiguration"),
                "scs-sender", Set.of("authorizationConfiguration"))));
    ownership.put(
        "cip-composition-generator",
        new GraphPatchOwnershipPolicy(
            true,
            true,
            Set.of("chain-call-2", "reuse", "reuse-reference"),
            Set.of(),
            Map.of(
                "chain-call-2", Set.of("elementId"),
                "reuse", Set.of("reference"),
                "reuse-reference", Set.of("elementId"))));
    ownership.put(
        "cip-error-handling-generator",
        new GraphPatchOwnershipPolicy(
            true,
            true,
            Set.of("try-catch-finally-2", "try-2", "catch-2", "finally-2"),
            Set.of(),
            Map.of(
                "catch-2", Set.of("exception", "priority"),
                "http-trigger", Set.of("chainFailureHandler"))));
    ownership.put(
        "cip-loop-generator",
        new GraphPatchOwnershipPolicy(
            true,
            true,
            Set.of("loop-2"),
            Set.of(),
            Map.of("loop-2", Set.of("expression", "doWhile", "copy"))));
    ownership.put(
        "cip-monitoring-generator",
        new GraphPatchOwnershipPolicy(
            true,
            true,
            Set.of("log-record"),
            Set.of(),
            Map.of(
                "log-record", Set.of("message", "level"),
                "service-call", Set.of("propagateContext"))));
    ownership.put(
        "cip-parallel-generator",
        new GraphPatchOwnershipPolicy(
            true,
            true,
            Set.of(
                "split-async-2",
                "async-split-element-2",
                "split-2",
                "split-element-2",
                "main-split-element-2"),
            Set.of(),
            Map.of(
                "split-async-2", Set.of("timeout"),
                "split-2", Set.of("timeout"),
                "split-element-2", Set.of("priority"),
                "async-split-element-2", Set.of("priority"),
                "main-split-element-2", Set.of())));
    ownership.put(
        "cip-retry-generator",
        new GraphPatchOwnershipPolicy(
            false,
            false,
            Set.of(),
            Set.of(),
            Map.of(
                "service-call", Set.of("retryCount", "retryDelay"),
                "http-sender", Set.of("retryCount", "retryDelay"),
                "kafka-sender-2", Set.of("retryCount", "retryDelay"),
                "graphql-sender", Set.of("retryCount", "retryDelay"),
                "rabbitmq-sender-2", Set.of("retryCount", "retryDelay"),
                "scs-sender", Set.of("retryCount", "retryDelay"))));
    ownership.put(
        "cip-routing-generator",
        new GraphPatchOwnershipPolicy(
            true,
            true,
            Set.of("condition", "if", "else", "choice", "when", "otherwise"),
            Set.of(),
            Map.of(
                "condition", Set.of("condition"),
                "if", Set.of("condition", "priority"),
                "else", Set.of(),
                "choice", Set.of(),
                "when", Set.of("condition", "priority"),
                "otherwise", Set.of())));
    ownership.put(
        "cip-script-generator",
        new GraphPatchOwnershipPolicy(
            true,
            true,
            Set.of("script"),
            Set.of(),
            Map.of("script", Set.of("script", "mappingCoverage"))));
    ownership.put(
        "cip-security-generator",
        new GraphPatchOwnershipPolicy(
            false,
            false,
            Set.of(),
            Set.of(),
            Map.ofEntries(
                entry("http-trigger", Set.of("accessControlType", "roles", "abacParameters")),
                entry("service-call", Set.of("password", "authorizationConfiguration")),
                entry("kafka-sender-2", Set.of("sslProtocol", "saslJaasConfig")),
                entry("kafka-trigger-2", Set.of("sslProtocol", "saslJaasConfig")),
                entry("jms-sender", Set.of("password")),
                entry("jms-trigger", Set.of("password")),
                entry("sftp-download", Set.of("password")),
                entry("sftp-upload", Set.of("password")),
                entry("sftp-trigger-2", Set.of("password")),
                entry("rabbitmq-sender-2", Set.of("password")),
                entry("rabbitmq-trigger-2", Set.of("password")),
                entry("mail-sender", Set.of("password")))));
    ownership.put(
        "cip-service-call-generator",
        new GraphPatchOwnershipPolicy(
            false,
            false,
            Set.of(
                "service-call",
                "http-sender",
                "kafka-sender-2",
                "graphql-sender",
                "rabbitmq-sender-2",
                "scs-sender",
                "dbaas",
                "mapper-2",
                "header-modification"),
            Set.of(),
            Map.ofEntries(
                entry(
                    "service-call",
                    Set.of(
                        "systemType",
                        "integrationSystemId",
                        "integrationSpecificationGroupId",
                        "integrationSpecificationId",
                        "integrationOperationId",
                        "integrationOperationProtocolType",
                        "integrationOperationMethod",
                        "integrationOperationPath",
                        "propagateContext",
                        "errorThrowing",
                        "before",
                        "after")),
                entry(
                    "http-trigger",
                    Set.of(
                        "systemType",
                        "integrationSystemId",
                        "integrationSpecificationGroupId",
                        "integrationSpecificationId",
                        "integrationOperationId",
                        "integrationOperationPath")),
                entry(
                    "async-api-trigger",
                    Set.of(
                        "systemType",
                        "integrationSystemId",
                        "integrationSpecificationGroupId",
                        "integrationSpecificationId",
                        "integrationOperationId",
                        "integrationOperationPath",
                        "integrationOperationProtocolType",
                        "integrationOperationMethod")),
                entry("http-sender", Set.of("path", "method")),
                entry("kafka-sender-2", Set.of("topic")),
                entry("graphql-sender", Set.of("operationName")),
                entry("rabbitmq-sender-2", Set.of("exchange", "routingKey")),
                entry("scs-sender", Set.of("bindingName")),
                entry("dbaas", Set.of("query")),
                entry("mapper-2", Set.of("mapping")),
                entry("header-modification", Set.of("headers")))));
    ownership.put(
        "cip-timeout-generator",
        new GraphPatchOwnershipPolicy(
            false,
            false,
            Set.of(),
            Set.of(),
            Map.of(
                "http-trigger", Set.of("connectTimeout"),
                "chain-call-2", Set.of("timeout"))));
    ownership.put(
        "cip-http-trigger-endpoint-generator",
        new GraphPatchOwnershipPolicy(
            false,
            false,
            Set.of(),
            Set.of(),
            Map.of(
                "http-trigger",
                Set.of("contextPath", "httpMethodRestrict", "externalRoute", "privateRoute"))));
    ownership.put(
        "cip-messaging-generator",
        new GraphPatchOwnershipPolicy(
            false,
            false,
            Set.of(
                "jms-trigger",
                "jms-sender",
                "pubsub-trigger",
                "pubsub-sender",
                "kafka-trigger-2",
                "rabbitmq-trigger-2"),
            Set.of(),
            Map.ofEntries(
                entry(
                    "jms-trigger",
                    Set.of(
                        "initialContextFactory",
                        "providerUrl",
                        "connectionFactoryName",
                        "destinationName",
                        "destinationType",
                        "acknowledgmentMode")),
                entry(
                    "jms-sender",
                    Set.of(
                        "initialContextFactory",
                        "providerUrl",
                        "connectionFactoryName",
                        "destinationName",
                        "destinationType",
                        "jmsMessageType")),
                entry(
                    "pubsub-trigger",
                    Set.of("projectId", "destinationName", "serviceAccountKey", "ackMode")),
                entry(
                    "pubsub-sender",
                    Set.of(
                        "projectId",
                        "destinationName",
                        "serviceAccountKey",
                        "messageOrderingEnabled")),
                entry(
                    "kafka-trigger-2",
                    Set.of(
                        "connectionSourceType",
                        "brokers",
                        "topics",
                        "groupId",
                        "topicsClassifierName",
                        "maasClassifierNamespace",
                        "maasClassifierTenantEnabled",
                        "maasClassifierTenantId")),
                entry(
                    "rabbitmq-trigger-2",
                    Set.of(
                        "connectionSourceType",
                        "addresses",
                        "exchange",
                        "routingKey",
                        "queues",
                        "username",
                        "vhostClassifierName",
                        "maasClassifierNamespace")))));
    return Map.copyOf(ownership);
  }
}
