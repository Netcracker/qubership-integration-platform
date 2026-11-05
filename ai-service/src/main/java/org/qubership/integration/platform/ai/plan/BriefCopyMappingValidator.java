package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps;
import org.qubership.integration.platform.ai.plan.mapping.CopyMappingPlanner;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaLoader;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingSource;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingValue;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;

/** Pins catalog schema evidence and validates COPY origins before brief approval. */
@ApplicationScoped
public class BriefCopyMappingValidator {
  private final OperationSchemaLoader schemas;
  private final ObjectMapper mapper;

  @Inject
  public BriefCopyMappingValidator(OperationSchemaLoader schemas, ObjectMapper mapper) {
    this.schemas = schemas;
    this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  }

  public record Result(RequirementBrief brief, List<ProductRequirementBriefTool.Issue> issues) {}

  public Result validateAndPin(RequirementBrief brief) {
    return validateAndPin(brief, schemas::load);
  }

  public List<RequirementCaptureProjection.PendingWork> pending(RequirementDraft draft) {
    if (draft == null || !draft.readyForPlan()) return List.of();
    List<MappingIntent> intents = RequirementBriefProjector.authoredMappings(draft).stream()
        .filter(intent -> intent.rules().stream().anyMatch(rule -> rule.value() instanceof MappingValue.Copy))
        .toList();
    if (intents.isEmpty()) return List.of();
    var brief = new RequirementBrief("", List.of(), List.of(), List.of(), List.of(), "",
        null, "", draft.facts(), intents).withFlow(draft.flow()).withCatalogBindings(draft.catalogBindings());
    return validateAndPin(RequirementBriefProjector.canonicalizeMappingIntents(brief)).issues().stream()
        .map(issue -> new RequirementCaptureProjection.PendingWork(issue.code(), null, null,
            issue.path() + " " + issue.message()))
        .toList();
  }

  private Result validateAndPin(RequirementBrief brief, Function<String, OperationSchemaMaps> load) {
    Map<String, OperationSchemaMaps> operationSnapshots = new HashMap<>();
    Function<String, OperationSchemaMaps> snapshot = id -> operationSnapshots.computeIfAbsent(id, load);
    List<ProductRequirementBriefTool.Issue> issues = new ArrayList<>();
    List<MappingIntent> intents = new ArrayList<>();
    Map<String, MappingSchemaSide> resolved = new HashMap<>();
    for (MappingIntent intent : brief.mappingIntents()) {
      if (intent.rules().stream().noneMatch(rule -> rule.value() != null)) {
        intents.add(intent);
        continue;
      }
      try {
        Map<String, Integer> order = linearOrder(brief.flow());
        Integer placement = order.get(intent.sourceRef());
        Integer targetPosition = order.get(intent.targetRef());
        if (placement == null || targetPosition == null || targetPosition != placement + 1) {
          throw new IllegalArgumentException("COPY placement needs a direct linear transition");
        }
        MappingSchemaSide target = side(brief, intent.targetRef(), intent.targetPort(), resolved, snapshot);
        Map<MappingSource.Message, MappingSchemaSide> origins = new LinkedHashMap<>();
        List<MappingIntentRule> rules = new ArrayList<>();
        for (MappingIntentRule rule : intent.rules()) {
          if (!(rule.value() instanceof MappingValue.Copy copy)
              || !(copy.source() instanceof MappingSource.Message origin)) {
            throw new IllegalArgumentException("The verified COPY slice needs explicit message origins for every rule");
          }
          Integer sourcePosition = order.get(origin.interactionId());
          if (sourcePosition == null || sourcePosition > placement
              || (sourcePosition.equals(placement) && origin.port() != intent.sourcePort())) {
            throw new IllegalArgumentException("COPY origin is not available at its execution placement");
          }
          MappingSchemaSide source = side(brief, origin.interactionId(), origin.port(), resolved, snapshot);
          origins.put(origin, source);
          rules.add(rule.withSchemaPins(Map.of("source", source.sha256(), "target", target.sha256())));
        }
        MappingIntent pinned = intent.withRules(rules);
        CopyMappingPlanner.validateFields(pinned, origins, target);
        intents.add(pinned);
      } catch (IllegalArgumentException | IllegalStateException failure) {
        issues.add(new ProductRequirementBriefTool.Issue("MAPPING_SOURCE_UNRESOLVED",
            "mappingIntents[" + intent.mappingIntentId() + "]", failure.getMessage()));
        intents.add(intent);
      }
    }
    return new Result(brief.withMappingIntents(intents), List.copyOf(issues));
  }

  public List<ProductRequirementBriefTool.Issue> validateApproved(RequirementBrief brief) {
    Result checked = validateAndPin(brief, schemas::loadFresh);
    if (!checked.issues().isEmpty()) return checked.issues();
    if (!brief.mappingIntents().equals(checked.brief().mappingIntents())) {
      return List.of(new ProductRequirementBriefTool.Issue("MAPPING_SCHEMA_CHANGED", "mappingIntents",
          "COPY schema evidence differs from the approved brief. Recapture and approve the mapping."));
    }
    return List.of();
  }

  private MappingSchemaSide side(RequirementBrief brief, String interactionId, MappingPort port,
      Map<String, MappingSchemaSide> resolved, Function<String, OperationSchemaMaps> snapshot) {
    String key = interactionId + "/" + port;
    if (resolved.containsKey(key)) return resolved.get(key);
    var interaction = brief.flow().interaction(interactionId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown COPY interaction: " + interactionId));
    boolean inbound = interaction.direction() == RequirementFlow.Direction.INBOUND;
    if (port == null || (inbound && port != MappingPort.OUTPUT)
        || (!inbound && port == MappingPort.OUTPUT)) {
      throw new IllegalArgumentException("COPY port does not match its interaction role");
    }
    List<CatalogBindingHint> bindings = brief.catalogBindings().stream()
        .filter(binding -> interactionId.equals(binding.interactionId())).toList();
    if (bindings.isEmpty() && inbound
        && RequirementFlowValidator.catalogLookupAction(interaction, brief.facts())
            == RequirementFlowValidator.LookupAction.SKIP) {
      MappingSchemaSide side = MessageSchemaContract.requestSchema(brief.facts(), interactionId)
          .orElseThrow(() -> new IllegalArgumentException(
              "COPY needs a confirmed request body JSON Schema for " + interactionId));
      resolved.put(key, side);
      return side;
    }
    if (bindings.size() != 1) {
      throw new IllegalArgumentException("COPY needs one confirmed catalog binding for " + interactionId);
    }
    String operationId = bindings.getFirst().integrationOperationId();
    var maps = snapshot.apply(operationId);
    Map<String, JsonNode> candidates = new TreeMap<>();
    if (port == MappingPort.RESPONSE) {
      maps.responseByStatusThenContentType().forEach((status, byMedia) -> {
        if (status.matches("2[0-9][0-9]")) {
          byMedia.forEach((media, schema) -> {
            if (jsonMedia(media) && schema != null && !schema.isNull()) candidates.put(status + "/" + media, schema);
          });
        }
      });
    } else {
      maps.requestByContentType().forEach((media, schema) -> {
        if (jsonMedia(media) && schema != null && !schema.isNull()) candidates.put(media, schema);
      });
    }
    if (candidates.isEmpty() || candidates.values().stream().distinct().count() != 1) {
      throw new IllegalArgumentException("COPY needs an unambiguous JSON schema for " + key);
    }
    JsonNode schema = candidates.values().iterator().next();
    String digest;
    try {
      digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(schema)));
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot pin COPY schema evidence", failure);
    }
    MappingSchemaSide side = new MappingSchemaSide("1", interactionId, operationId, port,
        "application/json", null, digest, bindings.getFirst().evidenceRef(), schema);
    resolved.put(key, side);
    return side;
  }

  private static boolean jsonMedia(String media) {
    if (media == null) return false;
    String base = media.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
    return "application/json".equals(base) || base.endsWith("+json");
  }

  private static Map<String, Integer> linearOrder(RequirementFlow flow) {
    Map<String, String> next = new HashMap<>();
    Map<String, Integer> incoming = new HashMap<>();
    for (var interaction : flow.interactions()) incoming.put(interaction.interactionId(), 0);
    for (var edge : flow.transitions()) {
      if (!incoming.containsKey(edge.sourceInteractionId()) || !incoming.containsKey(edge.targetInteractionId())
          || next.putIfAbsent(edge.sourceInteractionId(), edge.targetInteractionId()) != null
          || incoming.compute(edge.targetInteractionId(), (id, count) -> count + 1) != 1) {
        throw new IllegalArgumentException("COPY supports a connected linear flow only");
      }
    }
    List<String> starts = incoming.entrySet().stream().filter(entry -> entry.getValue() == 0)
        .map(Map.Entry::getKey).toList();
    if (starts.size() != 1) throw new IllegalArgumentException("COPY supports one linear entry only");
    Map<String, Integer> order = new HashMap<>();
    String id = starts.getFirst();
    while (id != null) {
      if (order.putIfAbsent(id, order.size()) != null) throw new IllegalArgumentException("COPY does not support cycles");
      id = next.get(id);
    }
    if (order.size() != incoming.size()) throw new IllegalArgumentException("COPY does not support disconnected paths");
    return order;
  }
}
