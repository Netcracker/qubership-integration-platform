package org.qubership.integration.platform.ai.plan.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.CompilerSkillContextBuilder;
import org.qubership.integration.platform.ai.compiler.ScriptBodyPromptRedaction;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.plan.mapping.envelope.JsonSchemaMessageSchemaFactory;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.plan.mapping.schema.DefaultMappingBoundarySchemaResolver;
import org.qubership.integration.platform.ai.plan.mapping.schema.JsonSchemaMappingContractFactory;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingBoundarySchemas;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;

/**
 * Resolves persisted boundary schemas, gates the mapping contract, and freezes envelopes before a
 * mapping generator skill starts.
 */
@ApplicationScoped
public class MappingGenerationPipeline {

  public static final String TRANSFORMATION_GENERATOR = "cip-transformation-generator";
  public static final String SCRIPT_GENERATOR = "cip-script-generator";
  private static final String SCHEMA_VERSION = "1";
  private static final String PRODUCER_VERSION = "1";
  private static final Logger LOG = Logger.getLogger(MappingGenerationPipeline.class);

  private final CompilationArtifacts artifacts;
  private final ObjectMapper objectMapper;
  private final CompilerSkillContextBuilder contextBuilder;

  @Inject
  public MappingGenerationPipeline(
      CompilationArtifacts artifacts,
      ObjectMapper objectMapper,
      CompilerSkillContextBuilder contextBuilder) {
    this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
  }

  public boolean isMappingGenerator(String skillId) {
    if (SCRIPT_GENERATOR.equals(skillId)) {
      return true;
    }
    return TRANSFORMATION_GENERATOR.equals(skillId)
        && MappingMechanismSelector.transformationGeneratorAllowed();
  }

  public Result prepare(
      String compilationId,
      String skillId,
      ChainSemanticRevision revision,
      List<ResolvedServiceCallBinding> bindings,
      GraphPatchExecutionContext context) {
    if (!isMappingGenerator(skillId) || revision == null || compilationId == null) {
      return Result.ready(context, List.of(), "");
    }
    List<MappingIntent> intents = orderedIntents(intentsFor(skillId, revision), context);
    String behaviorContext = "";
    List<String> requiredBlanks = requiredBlankScriptNodeIds(skillId, revision, graphOf(context));
    if (SCRIPT_GENERATOR.equals(skillId) && !requiredBlanks.isEmpty()) {
      behaviorContext =
          contextBuilder.renderBehaviorScriptGenerationContext(
              context == null ? null : context.requirementBrief(),
              requiredBlanks,
              graphOf(context),
              revision);
    }
    if (intents.isEmpty()) {
      return readyWithScriptContext(context, behaviorContext, requiredBlanks);
    }
    DefaultMappingBoundarySchemaResolver resolver =
        new DefaultMappingBoundarySchemaResolver(artifacts, compilationId, objectMapper);
    JsonSchemaMessageSchemaFactory envelopeFactory =
        new JsonSchemaMessageSchemaFactory(objectMapper);
    Map<String, MappingEnvelope> envelopesByTransformNodeId = new LinkedHashMap<>();
    List<Reference> envelopeRefs = new ArrayList<>();
    List<Reference> consumed =
        new ArrayList<>(context == null ? List.of() : context.consumedArtifacts());
    List<FrozenHop> hops = new ArrayList<>();
    for (MappingIntent intent : intents) {
      MappingBoundarySchemas schemas =
          resolver.resolve(revision, bindings, intent, envelopesByTransformNodeId);
      Optional<String> blocked = MappingContractGate.blockedMessage(intent, schemas);
      if (blocked.isPresent()) {
        LOG.warnf(
            "Mapping contract blocked skillId=%s mappingIntentId=%s message=%s",
            skillId, intent.mappingIntentId(), blocked.get());
        return Result.blocked(blocked.get(), context);
      }
      MappingEnvelope envelope =
          envelopeFactory
              .fromSides(schemas.source(), schemas.target())
              .withMappingIntentId(intent.mappingIntentId());
      Revision stored =
          artifacts.append(
              new AppendCommand(
                  compilationId,
                  Kind.MAPPING_ENVELOPE,
                  SCHEMA_VERSION,
                  skillId,
                  PRODUCER_VERSION,
                  envelope,
                  List.of(),
                  null));
      envelopeRefs.add(stored.reference());
      consumed.add(stored.reference());
      indexEnvelope(envelopesByTransformNodeId, intent, envelope, context);
      hops.add(new FrozenHop(intent, schemas, envelope));
    }
    Map<String, MappingContract> sourceContracts = new LinkedHashMap<>();
    for (FrozenHop hop : hops) {
      sourceContracts.put(
          hop.intent().mappingIntentId(),
          JsonSchemaMappingContractFactory.from(hop.schemas().source().schema()));
    }
    List<MappingIntent> revisionIntents = revision.mappingIntents();
    StringBuilder rendered = new StringBuilder();
    for (FrozenHop hop : hops) {
      if (!rendered.isEmpty()) {
        rendered.append("\n\n");
      }
      rendered.append(
          contextBuilder.renderMappingGenerationContext(
              hop.intent(),
              hop.envelope(),
              hop.schemas().source(),
              hop.schemas().target(),
              revisionIntents,
              sourceContracts));
    }
    String mappingContext = rendered.toString();
    if (!behaviorContext.isBlank()) {
      if (!mappingContext.isBlank()) {
        mappingContext = mappingContext + "\n\n" + behaviorContext;
      } else {
        mappingContext = behaviorContext;
      }
    }
    GraphPatchExecutionContext updated = context;
    if (context != null) {
      updated =
          bindScriptTargets(
              context.withConsumedArtifacts(consumed).withMappingGenerationContext(mappingContext),
              requiredBlanks);
    }
    return Result.ready(updated, envelopeRefs, mappingContext);
  }

  /**
   * Blank script shells this skill must fill: mapping-owned sites for its intents, plus ticket-01
   * behavior-owned scripts. Empty mapping intents do not skip the behavior-owned set.
   */
  public List<String> requiredBlankScriptNodeIds(
      String skillId, ChainSemanticRevision revision, ChainPlanGraph graph) {
    if (!SCRIPT_GENERATOR.equals(skillId) || graph == null || graph.nodes() == null) {
      return List.of();
    }
    Set<String> blanks = new LinkedHashSet<>();
    Set<String> mappingIntentIds = new LinkedHashSet<>();
    if (revision != null) {
      for (MappingIntent intent : intentsFor(skillId, revision)) {
        mappingIntentIds.add(intent.mappingIntentId());
      }
      for (String nodeId :
          DefaultChainSemanticRevisionValidator.behaviorOwnedScriptNodeIds(revision)) {
        if (isBlankScriptNode(graph, nodeId)) {
          blanks.add(nodeId);
        }
      }
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null || !"script".equals(node.type())) {
        continue;
      }
      String intentId = MappingExecutionSite.mappingIntentId(node);
      if (intentId != null
          && mappingIntentIds.contains(intentId)
          && isBlankScriptNode(graph, node.nodeId())) {
        blanks.add(node.nodeId());
      }
    }
    return List.copyOf(blanks);
  }

  public static String missingScriptBodiesMessage(List<String> nodeIds) {
    return "Script generator completed without script bodies for nodes: "
        + String.join(", ", nodeIds);
  }

  private static Result readyWithScriptContext(
      GraphPatchExecutionContext context, String behaviorContext, List<String> requiredBlanks) {
    if (behaviorContext == null || behaviorContext.isBlank()) {
      return Result.ready(context, List.of(), "");
    }
    GraphPatchExecutionContext updated = context;
    if (context != null) {
      updated =
          bindScriptTargets(context.withMappingGenerationContext(behaviorContext), requiredBlanks);
    }
    return Result.ready(updated, List.of(), behaviorContext);
  }

  private static GraphPatchExecutionContext bindScriptTargets(
      GraphPatchExecutionContext context, List<String> requiredBlanks) {
    if (context == null
        || requiredBlanks == null
        || requiredBlanks.isEmpty()
        || !context.editTargetNodeIds().isEmpty()) {
      return context;
    }
    return context.withEditTargetNodeIds(requiredBlanks);
  }

  private static ChainPlanGraph graphOf(GraphPatchExecutionContext context) {
    return context == null ? null : context.inputGraph();
  }

  private static boolean isBlankScriptNode(ChainPlanGraph graph, String nodeId) {
    if (graph == null || graph.nodes() == null || nodeId == null || nodeId.isBlank()) {
      return false;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null || !nodeId.equals(node.nodeId()) || !"script".equals(node.type())) {
        continue;
      }
      if (node.properties() == null) {
        return true;
      }
      for (PlanProperty property : node.properties()) {
        if (property != null && "script".equals(property.key())) {
          return !ScriptBodyPromptRedaction.isPresentScriptBody(property.value());
        }
      }
      return true;
    }
    return false;
  }

  private static List<MappingIntent> intentsFor(String skillId, ChainSemanticRevision revision) {
    List<MappingIntent> matched = new ArrayList<>();
    for (MappingIntent intent : revision.mappingIntents()) {
      if (belongsToSkill(intent, skillId)) {
        matched.add(intent);
      }
    }
    return List.copyOf(matched);
  }

  private static List<MappingIntent> orderedIntents(
      List<MappingIntent> intents, GraphPatchExecutionContext context) {
    if (context == null || context.inputGraph() == null || intents.isEmpty()) {
      return intents;
    }
    Map<String, MappingIntent> remaining = new LinkedHashMap<>();
    for (MappingIntent intent : intents) {
      remaining.put(intent.mappingIntentId(), intent);
    }
    List<MappingIntent> ordered = new ArrayList<>();
    ChainPlanGraph graph = context.inputGraph();
    if (graph.nodes() != null) {
      for (ChainPlanNode node : graph.nodes()) {
        String intentId = MappingExecutionSite.mappingIntentId(node);
        MappingIntent intent = intentId == null ? null : remaining.remove(intentId);
        if (intent != null) {
          ordered.add(intent);
        }
      }
    }
    ordered.addAll(remaining.values());
    return List.copyOf(ordered);
  }

  private static void indexEnvelope(
      Map<String, MappingEnvelope> envelopes,
      MappingIntent intent,
      MappingEnvelope envelope,
      GraphPatchExecutionContext context) {
    String intentId = intent.mappingIntentId();
    envelopes.put(intentId, envelope);
    envelopes.put("transform-" + intentId, envelope);
    String siteId = siteNodeId(context, intentId);
    if (siteId != null && !siteId.isBlank()) {
      envelopes.put(siteId, envelope);
    }
  }

  private static String siteNodeId(GraphPatchExecutionContext context, String mappingIntentId) {
    if (context == null || context.inputGraph() == null || context.inputGraph().nodes() == null) {
      return null;
    }
    for (ChainPlanNode node : context.inputGraph().nodes()) {
      if (mappingIntentId.equals(MappingExecutionSite.mappingIntentId(node))) {
        return node.nodeId();
      }
    }
    return null;
  }

  private static boolean belongsToSkill(MappingIntent intent, String skillId) {
    Optional<MappingMechanism> selected = MappingMechanismSelector.select(intent);
    if (selected.isEmpty()) {
      return TRANSFORMATION_GENERATOR.equals(skillId) || SCRIPT_GENERATOR.equals(skillId);
    }
    if (TRANSFORMATION_GENERATOR.equals(skillId)) {
      return selected.get() == MappingMechanism.MAPPER_2;
    }
    if (SCRIPT_GENERATOR.equals(skillId)) {
      return selected.get() == MappingMechanism.SCRIPT;
    }
    return false;
  }

  private record FrozenHop(
      MappingIntent intent, MappingBoundarySchemas schemas, MappingEnvelope envelope) {}

  public record Result(
      boolean blocked,
      String blockedMessage,
      GraphPatchExecutionContext context,
      List<Reference> envelopeRefs,
      String mappingGenerationContext) {

    public Result {
      blockedMessage = blockedMessage == null ? "" : blockedMessage;
      envelopeRefs = envelopeRefs == null ? List.of() : List.copyOf(envelopeRefs);
      mappingGenerationContext =
          mappingGenerationContext == null ? "" : mappingGenerationContext;
    }

    static Result blocked(String message, GraphPatchExecutionContext context) {
      return new Result(true, message, context, List.of(), "");
    }

    static Result ready(
        GraphPatchExecutionContext context,
        List<Reference> envelopeRefs,
        String mappingGenerationContext) {
      return new Result(false, "", context, envelopeRefs, mappingGenerationContext);
    }
  }
}
