package org.qubership.integration.platform.ai.plan.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.plan.mapping.schema.DefaultMappingBoundarySchemaResolver;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingSource;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingValue;

/** Resolves COPY origins before generation and derives retention across body replacement. */
public final class CopyMappingPlanner {
  private static final Pattern FIELD = Pattern.compile("\\$\\.[A-Za-z_][A-Za-z0-9_]*");

  private CopyMappingPlanner() {}

  public static Map<String, CopyMappingPlan> prepare(ChainSemanticRevision revision,
      List<MappingIntent> intents, DefaultMappingBoundarySchemaResolver resolver,
      List<ResolvedServiceCallBinding> bindings, Map<String, MappingSchemaSide> outputs) {
    return prepare(revision, intents, resolver, bindings, outputs, null);
  }

  public static Map<String, CopyMappingPlan> prepare(ChainSemanticRevision revision,
      List<MappingIntent> intents, DefaultMappingBoundarySchemaResolver resolver,
      List<ResolvedServiceCallBinding> bindings, Map<String, MappingSchemaSide> outputs,
      ChainPlanGraph graph) {
    Map<String, Integer> positions = linearPositions(revision);
    Map<String, List<CopyMappingPlan.Read>> reads = new LinkedHashMap<>();
    Map<String, List<CopyMappingPlan.Save>> saves = new LinkedHashMap<>();
    for (MappingIntent intent : intents) {
      if (graph != null) validateExecutionSite(revision, intent, graph);
      List<CopyMappingPlan.Read> intentReads = new ArrayList<>();
      MappingSchemaSide target = resolver.resolveOrigin(revision, bindings,
          intent.targetRef(), intent.targetPort(), outputs);
      String current = node(revision, intent.sourceRef(), intent.sourcePort());
      String destination = node(revision, intent.targetRef(), intent.targetPort());
      if (positions.get(current) >= positions.get(destination)) {
        throw rejected("Mapping placement must follow the linear execution order");
      }
      for (MappingIntentRule rule : intent.rules()) {
        requireUnconditional(rule);
        if (!(rule.value() instanceof MappingValue.Copy copy)
            || !(copy.source() instanceof MappingSource.Message origin)
            || origin.interactionId() == null || origin.interactionId().isBlank()
            || origin.port() == null || !"ERROR".equals(rule.missingSourcePolicy())) {
          throw rejected("COPY needs a confirmed origin and saved missing=ERROR policy; recapture and approve the mapping");
        }
        String sourceField = field(origin.path());
        String targetField = field(rule.targetPath());
        String producer = node(revision, origin.interactionId(), origin.port());
        if (positions.get(producer) > positions.get(current)
            || (producer.equals(current) && origin.port() != intent.sourcePort()
                && !(origin.port() == MappingPort.REQUEST && intent.sourcePort() == MappingPort.RESPONSE))) {
          throw rejected("Mapping source is not available at this execution site");
        }
        MappingSchemaSide source = resolver.resolveOrigin(revision, bindings,
            origin.interactionId(), origin.port(), outputs);
        requirePins(rule, source, target);
        Set<String> sourceTypes = validatedTypes(intent, origin, source, sourceField, target, targetField);
        boolean nullable = sourceTypes.contains("null");
        String contextId = null;
        if (!producer.equals(current) || origin.port() != intent.sourcePort()
            || validRetentionSite(revision, origin, graph)) {
          contextId = contextId(origin);
          SaveSite site = saveSite(revision, intents, origin, positions, current, graph);
          List<CopyMappingPlan.Save> atSite = saves.computeIfAbsent(site.intentId(), ignored -> new ArrayList<>());
          CopyMappingPlan.Save save = new CopyMappingPlan.Save(sourceField, contextId, site.fromOutput(), nullable);
          if (!atSite.contains(save)) atSite.add(save);
          var body = new CopyMappingPlan.Save("$", bodyContextId(origin), site.fromOutput(), false);
          if (!atSite.contains(body)) atSite.add(body);
        }
        intentReads.add(new CopyMappingPlan.Read(rule.id(), targetField, origin,
            source.sha256(), target.sha256(), contextId, nullable,
            source.isRuntimeJson() ? sourceTypes.stream().sorted().toList() : List.of()));
      }
      if (intentReads.isEmpty()) throw rejected("COPY artifact has no mapping operands");
      Set<String> covered = new HashSet<>();
      intentReads.forEach(read -> covered.add(read.targetField()));
      for (JsonNode required : target.schema().path("required")) {
        if (!covered.contains(required.asText())) throw rejected("Required target has no COPY rule: " + required.asText());
      }
      if (covered.size() != intentReads.size()) throw rejected("COPY target is assigned more than once");
      if (reads.putIfAbsent(intent.mappingIntentId(), intentReads) != null) {
        throw rejected("COPY artifact repeats a mapping intent identity");
      }
    }
    Map<String, CopyMappingPlan> result = new LinkedHashMap<>();
    reads.forEach((id, values) -> result.put(id,
        new CopyMappingPlan(values, saves.getOrDefault(id, List.of()))));
    saves.forEach((id, values) -> result.putIfAbsent(id, new CopyMappingPlan(List.of(), values)));
    return Map.copyOf(result);
  }

  public static void validateFields(MappingIntent intent,
      Map<MappingSource.Message, MappingSchemaSide> origins, MappingSchemaSide target) {
    for (MappingIntentRule rule : intent.rules()) {
      requireUnconditional(rule);
      if (!(rule.value() instanceof MappingValue.Copy copy)
          || !(copy.source() instanceof MappingSource.Message origin)
          || origin.interactionId() == null || origin.interactionId().isBlank()
          || origin.port() == null || !"ERROR".equals(rule.missingSourcePolicy())) {
        throw rejected("COPY needs a confirmed origin and approved missing=ERROR policy");
      }
      MappingSchemaSide side = origins.get(origin);
      if (side == null) throw rejected("No schema evidence for COPY origin");
      requirePins(rule, side, target);
      validatedTypes(intent, origin, side, field(origin.path()), target, field(rule.targetPath()));
    }
  }

  private static Set<String> validatedTypes(MappingIntent intent, MappingSource.Message origin,
      MappingSchemaSide source, String sourceField, MappingSchemaSide target, String targetField) {
    Set<String> targetTypes = types(target.schema(), targetField);
    if (source.isRuntimeJson()) {
      if (origin.port() != MappingPort.OUTPUT || intent.targetPort() != MappingPort.REQUEST
          || target.operationId() == null) {
        throw rejected("COPY without an input schema needs a catalog request target");
      }
      requireTargetConstraints(null, sourceField, target.schema(), targetField, origin.interactionId());
      return targetTypes;
    }
    Set<String> sourceTypes = types(source.schema(), sourceField);
    requireTargetConstraints(source.schema(), sourceField, target.schema(), targetField, null);
    if (!compatibleTypes(sourceTypes, targetTypes)) throw rejected("COPY source and target types are incompatible");
    return sourceTypes;
  }

  static void requireUnconditional(MappingIntentRule rule) {
    if (rule.condition().when()
        != org.qubership.integration.platform.ai.qipknowledge.artifact.MappingCondition.When.ALWAYS) {
      throw rejected("Native COPY supports only ALWAYS conditions; retain the conditional requirement and recapture a supported mapping");
    }
  }

  private static void requirePins(MappingIntentRule rule, MappingSchemaSide source, MappingSchemaSide target) {
    if (rule.schemaPins() == null || source == null || target == null
        || source.sha256() == null || source.sha256().isBlank()
        || target.sha256() == null || target.sha256().isBlank()
        || !source.sha256().equals(rule.schemaPins().get("source"))
        || !target.sha256().equals(rule.schemaPins().get("target"))) {
      throw rejected("Schema evidence changed or is not pinned to this approved mapping");
    }
  }

  private static SaveSite saveSite(ChainSemanticRevision revision, List<MappingIntent> intents,
      MappingSource.Message origin, Map<String, Integer> positions, String consumer, ChainPlanGraph graph) {
    if (validRetentionSite(revision, origin, graph)) return new SaveSite(retentionSiteId(origin), false);
    if (graph != null) throw rejected("Earlier source needs its compiler-owned retention site");
    String producer = node(revision, origin.interactionId(), origin.port());
    for (MappingIntent candidate : intents) {
      String source = node(revision, candidate.sourceRef(), candidate.sourcePort());
      String target = node(revision, candidate.targetRef(), candidate.targetPort());
      if (positions.get(target) > positions.get(consumer)) continue;
      if (producer.equals(source) && origin.port() == candidate.sourcePort()) {
        return new SaveSite(candidate.mappingIntentId(), false);
      }
      if (producer.equals(target) && origin.port() == candidate.targetPort()) {
        return new SaveSite(candidate.mappingIntentId(), true);
      }
    }
    throw rejected("Earlier source has no writable retention site before body replacement");
  }

  private record SaveSite(String intentId, boolean fromOutput) {}

  public static void validateExecutionSite(ChainSemanticRevision revision, MappingIntent intent,
      ChainPlanGraph graph) {
    List<ChainPlanNode> sites = graph.nodes().stream().filter(item ->
        intent.mappingIntentId().equals(MappingExecutionSite.mappingIntentId(item))).toList();
    if (sites.size() != 1 || !"script".equals(sites.getFirst().type())
        || (sites.getFirst().parentNodeId() != null && !sites.getFirst().parentNodeId().isBlank())) {
      throw rejected("COPY execution needs one owned script site");
    }
    String source = actualNode(revision, intent.sourceRef(), intent.sourcePort(), graph);
    String target = actualNode(revision, intent.targetRef(), intent.targetPort(), graph);
    String site = sites.getFirst().nodeId();
    String cursor = source;
    Set<String> seen = new HashSet<>();
    boolean visitedSite = false;
    while (!target.equals(cursor)) {
      if (!seen.add(cursor)) throw rejected("COPY execution site is in a cycle");
      String at = cursor;
      var outgoing = graph.edges().stream().filter(edge -> at.equals(edge.fromNodeId())).toList();
      if (outgoing.size() != 1 || (outgoing.getFirst().scopeNodeId() != null
          && !outgoing.getFirst().scopeNodeId().isBlank())) {
        throw rejected("COPY execution site is not on one root linear boundary");
      }
      cursor = outgoing.getFirst().toNodeId();
      if (target.equals(cursor)) {
        if (site.equals(cursor)) visitedSite = true;
        break;
      }
      ChainPlanNode node = graph.nodes().stream().filter(item -> item.nodeId().equals(outgoing.getFirst().toNodeId()))
          .findFirst().orElseThrow(() -> rejected("COPY graph edge has no target node"));
      if (site.equals(cursor)) {
        if (visitedSite) throw rejected("COPY execution site is repeated");
        visitedSite = true;
      } else if (!nativeRetentionSite(node)) {
        boolean frozenInputs = intent.rules().stream().allMatch(rule ->
            rule.value() instanceof MappingValue.Copy copy && copy.source() instanceof MappingSource.Message origin
                && validRetentionSite(revision, origin, graph));
        if (visitedSite || !"script".equals(node.type()) || MappingExecutionSite.mappingIntentId(node) != null
            || !frozenInputs) {
          throw rejected("COPY execution site crosses another body-producing node");
        }
      }
    }
    if (!visitedSite) throw rejected("COPY script is outside its approved execution placement");
  }

  private static String actualNode(ChainSemanticRevision revision, String ref, MappingPort port,
      ChainPlanGraph graph) {
    String semanticId = node(revision, ref, port);
    var matches = graph.nodes().stream().filter(item -> semanticId.equals(item.nodeId())
        || semanticId.equals(item.semanticNodeId().orElse(null))
        || ref.equals(item.serviceCallId().orElse(null))).map(ChainPlanNode::nodeId).toList();
    if (matches.size() != 1) throw rejected("COPY interaction does not resolve uniquely in the actual graph");
    return matches.getFirst();
  }

  public static String bodyContextId(MappingSource.Message origin) {
    return contextId(new MappingSource.Message(origin.interactionId(), origin.port(), "$"));
  }

  public static String retentionSiteId(MappingSource.Message origin) {
    return "retain-" + bodyContextId(origin)
        .substring("qip.mapping.".length());
  }

  private static boolean validRetentionSite(ChainSemanticRevision revision,
      MappingSource.Message origin, ChainPlanGraph graph) {
    if (graph == null) return false;
    String siteId = retentionSiteId(origin);
    ChainPlanNode site = graph.nodes().stream().filter(item -> siteId.equals(item.nodeId())).findFirst().orElse(null);
    if (site == null) return false;
    if (!"script".equals(site.type()) || !nativeRetentionSite(site)) {
      throw rejected("Retention site identity is occupied by an unowned node");
    }
    String semanticId = node(revision, origin.interactionId(), origin.port());
    List<String> producers = graph.nodes().stream()
        .filter(item -> semanticId.equals(item.nodeId())
            || semanticId.equals(item.semanticNodeId().orElse(null))
            || origin.interactionId().equals(item.serviceCallId().orElse(null)))
        .map(ChainPlanNode::nodeId).toList();
    if (producers.size() != 1) throw rejected("Retention producer does not resolve uniquely in the graph");
    String producer = producers.getFirst();
    boolean adjacent = graph.edges().stream().anyMatch(edge -> origin.port() == MappingPort.REQUEST
        ? siteId.equals(edge.fromNodeId()) && producer.equals(edge.toNodeId())
        : producer.equals(edge.fromNodeId()) && siteId.equals(edge.toNodeId()));
    if (!adjacent) throw rejected("Retention save site is not at its confirmed producer boundary");
    return true;
  }

  public static boolean hasVerbatimBody(
      org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief brief, ChainPlanNode node) {
    return ScriptExecutionAuthority.hasVerbatimBody(brief, node);
  }

  public static boolean nativeRetentionSite(ChainPlanNode node) {
    return node != null && node.properties() != null && node.properties().stream().anyMatch(property ->
        "nativeRetentionSite".equals(property.key()) && "true".equals(property.value()));
  }

  public static String contextId(MappingSource.Message origin) {
    try {
      String key = origin.interactionId() + "\n" + origin.port() + "\n" + origin.path();
      return "qip.mapping." + java.util.HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String field(String path) {
    if (path == null || !FIELD.matcher(path).matches()) {
      throw rejected("COPY supports confirmed top-level JSON body paths only");
    }
    return path.substring(2);
  }

  public static JsonNode verifiedFieldSchema(MappingSchemaSide source, String path) {
    if (source == null || source.isRuntimeJson()) {
      throw rejected("Local output needs a verified source schema");
    }
    String name = field(path);
    types(source.schema(), name);
    JsonNode root = dereference(source.schema(), source.schema(), new HashSet<>());
    return dereference(source.schema(), root.path("properties").get(name), new HashSet<>()).deepCopy();
  }

  private static Set<String> types(JsonNode schema, String name) {
    JsonNode root = dereference(schema, schema, new HashSet<>());
    if (root == null || !root.isObject() || complex(root)) throw rejected("Mapping schema is unknown or unsupported");
    JsonNode property = root.path("properties").get(name);
    if (property == null) {
      if (root.path("additionalProperties").isBoolean() && !root.path("additionalProperties").asBoolean()) {
        throw rejected("Mapping path is not permitted by the closed schema: $." + name);
      }
      throw rejected("Mapping path is permitted but its type is unknown: $." + name);
    }
    property = dereference(schema, property, new HashSet<>());
    if (property == null || complex(property)) throw rejected("Mapping field schema is unknown or unsupported");
    Set<String> result = new LinkedHashSet<>();
    JsonNode type = property.path("type");
    if (type.isTextual()) result.add(type.asText());
    else if (type.isArray()) type.forEach(item -> result.add(item.asText()));
    if (result.isEmpty() || !Set.of("string", "number", "integer", "boolean", "null").containsAll(result)) {
      throw rejected("COPY requires known scalar field types");
    }
    return Set.copyOf(result);
  }

  private static boolean compatibleTypes(Set<String> source, Set<String> target) {
    Set<String> permitted = new HashSet<>(target);
    if (permitted.contains("number")) permitted.add("integer");
    return permitted.containsAll(source);
  }

  private static void requireTargetConstraints(JsonNode sourceRoot, String sourceField,
      JsonNode targetRoot, String targetField, String runtimeSource) {
    JsonNode source = sourceRoot == null ? null : dereference(sourceRoot,
        dereference(sourceRoot, sourceRoot, new HashSet<>()).path("properties").get(sourceField), new HashSet<>());
    JsonNode target = dereference(targetRoot,
        dereference(targetRoot, targetRoot, new HashSet<>()).path("properties").get(targetField), new HashSet<>());
    Set<String> descriptive = Set.of("type", "title", "description", "example", "examples", "default", "$comment",
        "readOnly", "writeOnly", "deprecated");
    var fields = target.fields();
    while (fields.hasNext()) {
      var constraint = fields.next();
      if (!descriptive.contains(constraint.getKey())
          && (source == null || !constraint.getValue().equals(source.get(constraint.getKey())))) {
        String message = "COPY target constraints are not established by its source: " + constraint.getKey();
        if (runtimeSource != null) throw new InputSchemaRequired(runtimeSource, message);
        throw rejected(message);
      }
    }
  }

  public static final class InputSchemaRequired extends IllegalArgumentException {
    private final String interactionId;

    InputSchemaRequired(String interactionId, String message) {
      super(message);
      this.interactionId = interactionId;
    }

    public String interactionId() {
      return interactionId;
    }
  }

  private static boolean complex(JsonNode node) {
    return node.has("allOf") || node.has("oneOf") || node.has("anyOf") || node.has("not");
  }

  private static JsonNode dereference(JsonNode root, JsonNode node, Set<String> visiting) {
    if (node == null || !node.has("$ref")) return node;
    String ref = node.path("$ref").asText();
    if (!ref.startsWith("#/") || !visiting.add(ref)) return null;
    if (node.size() != 1) return null;
    JsonNode resolved = root.at(ref.substring(1));
    return resolved.isMissingNode() ? null : dereference(root, resolved, visiting);
  }

  private static String node(ChainSemanticRevision revision, String ref, MappingPort port) {
    return DefaultMappingBoundarySchemaResolver.semanticNodeId(revision, ref, port);
  }

  private static Map<String, Integer> linearPositions(ChainSemanticRevision revision) {
    Map<String, String> next = new HashMap<>();
    Set<String> incoming = new HashSet<>();
    for (SemanticExecutionEdge edge : revision.executionEdges()) {
      if (edge.route() != null && !(edge.route() instanceof
          org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRoute.Sequence)) {
        throw rejected("COPY retention does not support conditional or exceptional routes");
      }
      if (next.putIfAbsent(edge.sourceNodeId(), edge.targetNodeId()) != null
          || !incoming.add(edge.targetNodeId())) {
        throw rejected("COPY retention supports a linear execution flow only");
      }
    }
    List<String> roots = revision.nodes().stream().map(node -> node.nodeId())
        .filter(id -> !incoming.contains(id)).toList();
    if (roots.size() != 1) throw rejected("COPY flow must have one linear root");
    Map<String, Integer> positions = new HashMap<>();
    String cursor = roots.getFirst();
    while (cursor != null) {
      if (positions.putIfAbsent(cursor, positions.size()) != null) throw rejected("COPY flow cannot contain a cycle");
      cursor = next.get(cursor);
    }
    if (positions.size() != revision.nodes().size()) throw rejected("COPY flow is disconnected or cyclic");
    return positions;
  }

  private static IllegalArgumentException rejected(String message) {
    return new IllegalArgumentException("Mapping origin is unresolved: " + message);
  }
}
