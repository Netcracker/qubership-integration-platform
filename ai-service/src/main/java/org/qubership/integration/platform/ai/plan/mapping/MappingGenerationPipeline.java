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
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
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
    if (context != null) context = context.withBehaviorTasks(ScriptExecutionAuthority.behaviorSites(revision, context.requirementBrief()));
    try {
      ScriptExecutionAuthority.requireMappingBehaviorSites(context == null ? null : context.requirementBrief(), revision);
      ScriptExecutionAuthority.requireSupported(context == null ? null : context.requirementBrief(), graphOf(context),
          context == null ? Map.of() : context.behaviorTasks());
    } catch (IllegalArgumentException failure) {
      return Result.blocked(failure.getMessage(), context,
          List.of(new PlanValidationFinding("SCRIPT_AUTHORITY_UNSUPPORTED", failure.getMessage(), true)));
    }
    List<MappingIntent> intents = orderedIntents(intentsFor(skillId, revision, context), context);
    String behaviorContext = "";
    List<String> requiredBlanks = requiredBlankScriptNodeIds(skillId, revision, graphOf(context), context);
    if (SCRIPT_GENERATOR.equals(skillId) && !requiredBlanks.isEmpty()) {
      behaviorContext =
          contextBuilder.renderBehaviorScriptGenerationContext(
              context == null ? null : context.requirementBrief(),
              requiredBlanks,
              graphOf(context),
              revision);
    }
    if (intents.isEmpty()) {
      try {
        ScriptExecutionAuthority.requireRetentionOwners(graphOf(context), Map.of());
      } catch (IllegalArgumentException failure) {
        return Result.blocked(failure.getMessage(), context,
            List.of(new PlanValidationFinding("SCRIPT_AUTHORITY_UNSUPPORTED", failure.getMessage(), true)));
      }
      return readyWithScriptContext(context, behaviorContext, requiredBlanks);
    }
    DefaultMappingBoundarySchemaResolver resolver =
        new DefaultMappingBoundarySchemaResolver(artifacts, compilationId, Map.of(),
            context == null ? null : context.requirementBrief());
    JsonSchemaMessageSchemaFactory envelopeFactory =
        new JsonSchemaMessageSchemaFactory(objectMapper);
    Map<String, MappingSchemaSide> outputSchemasByTransformNodeId = new LinkedHashMap<>();
    List<Reference> envelopeRefs = new ArrayList<>();
    List<Reference> consumed =
        new ArrayList<>(context == null ? List.of() : context.consumedArtifacts());
    List<FrozenHop> hops = new ArrayList<>();
    Map<String, CopyMappingPlan> copyPlans;
    try {
      for (MappingIntent intent : intents) {
        MappingBoundarySchemas schemas = resolver.resolve(revision, bindings, intent, outputSchemasByTransformNodeId);
        MappingContractEvaluation evaluated = MappingContractGate.evaluate(intent,
            MappingContract.unknown(), JsonSchemaMappingContractFactory.from(schemas.target().schema()));
        if (evaluated.blocked()) {
          Reference brief = consumedBrief(context);
          return Result.blocked(evaluated.blockedMessage(), context,
              MappingContractGate.toPlanFindings(evaluated, schemas.source(), schemas.target(),
                  brief == null ? "" : brief.artifactId(), brief == null ? "" : brief.contentHash()));
        }
        indexOutputSchema(outputSchemasByTransformNodeId, intent, schemas.target(), context);
      }
      copyPlans = CopyMappingPlanner.prepare(revision, intents, resolver, bindings, outputSchemasByTransformNodeId, graphOf(context));
      ScriptExecutionAuthority.requireRetentionOwners(graphOf(context), copyPlans);
    } catch (IllegalArgumentException | IllegalStateException failure) {
      return Result.blocked(failure.getMessage(), context,
          List.of(new PlanValidationFinding("MAPPING_UNRESOLVED_SOURCE", failure.getMessage(), true)));
    }
    for (MappingIntent intent : intents) {
      MappingBoundarySchemas schemas =
          resolver.resolve(revision, bindings, intent, outputSchemasByTransformNodeId);
      MappingContractEvaluation evaluated = MappingContractGate.evaluate(intent,
          MappingContract.unknown(), JsonSchemaMappingContractFactory.from(schemas.target().schema()));
      if (evaluated.blocked()) {
        Reference brief = consumedBrief(context);
        List<PlanValidationFinding> findings =
            MappingContractGate.toPlanFindings(
                evaluated,
                schemas.source(),
                schemas.target(),
                brief == null ? "" : brief.artifactId(),
                brief == null ? "" : brief.contentHash());
        LOG.warnf(
            "Mapping contract blocked skillId=%s mappingIntentId=%s code=%s",
            skillId,
            intent.mappingIntentId(),
            findings.isEmpty() ? evaluated.blockedMessage() : findings.getFirst().code());
        return Result.blocked(evaluated.blockedMessage(), context, findings);
      }
      MappingEnvelope envelope =
          envelopeFactory
              .fromSides(schemas.source(), schemas.target())
              .withMappingIntentId(intent.mappingIntentId())
              .withVerifiedCopyPlan(copyPlans.get(intent.mappingIntentId()));
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
      indexOutputSchema(outputSchemasByTransformNodeId, intent, schemas.target(), context);
      hops.add(new FrozenHop(intent, schemas, envelope));
    }
    for (Map.Entry<String, CopyMappingPlan> entry : copyPlans.entrySet()) {
      if (!entry.getValue().reads().isEmpty()) continue;
      MappingEnvelope saveEnvelope = hops.getFirst().envelope().withMappingIntentId(entry.getKey())
          .withVerifiedCopyPlan(entry.getValue());
      Revision stored = artifacts.append(new AppendCommand(compilationId, Kind.MAPPING_ENVELOPE,
          SCHEMA_VERSION, skillId, PRODUCER_VERSION, saveEnvelope, List.of(), null));
      envelopeRefs.add(stored.reference());
      consumed.add(stored.reference());
    }
    Map<String, MappingContract> sourceContracts = new LinkedHashMap<>();
    for (FrozenHop hop : hops) {
      sourceContracts.put(
          hop.intent().mappingIntentId(),
          JsonSchemaMappingContractFactory.from(hop.schemas().source().schema()));
    }
    List<MappingIntent> revisionIntents =
        revision.mappingBodies(context == null ? null : context.requirementBrief());
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
   * Finds blank shells for typed mappings, compiler-derived retention, and approved script behavior.
   */
  public List<String> requiredBlankScriptNodeIds(
      String skillId, ChainSemanticRevision revision, ChainPlanGraph graph) {
    return requiredBlankScriptNodeIds(skillId, revision, graph, null);
  }

  public List<String> requiredBlankScriptNodeIds(
      String skillId,
      ChainSemanticRevision revision,
      ChainPlanGraph graph,
      GraphPatchExecutionContext context) {
    if (!SCRIPT_GENERATOR.equals(skillId) || graph == null || graph.nodes() == null) {
      return List.of();
    }
    Set<String> blanks = new LinkedHashSet<>();
    Set<String> mappingIntentIds = new LinkedHashSet<>();
    if (revision != null) {
      for (MappingIntent intent : intentsFor(skillId, revision, context)) {
        mappingIntentIds.add(intent.mappingIntentId());
      }
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null || !"script".equals(node.type())) {
        continue;
      }
      if ((CopyMappingPlanner.nativeRetentionSite(node)
          || context != null && ScriptExecutionAuthority.declaredGeneratedBehavior(
              context.requirementBrief(), node.nodeId(), context.behaviorTasks()).isPresent())
          && isBlankScriptNode(graph, node.nodeId())) {
        blanks.add(node.nodeId());
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

  private static List<MappingIntent> intentsFor(
      String skillId, ChainSemanticRevision revision, GraphPatchExecutionContext context) {
    List<MappingIntent> matched = new ArrayList<>();
    for (MappingIntent intent :
        revision.mappingBodies(context == null ? null : context.requirementBrief())) {
      List<ChainPlanNode> sites = context == null || context.inputGraph() == null ? List.of()
          : context.inputGraph().nodes().stream().filter(node ->
              intent.mappingIntentId().equals(MappingExecutionSite.mappingIntentId(node))).toList();
      boolean verbatim = sites.size() == 1
          && CopyMappingPlanner.hasVerbatimBody(context.requirementBrief(), sites.getFirst());
      if (belongsToSkill(intent, skillId) && !verbatim) {
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

  private static void indexOutputSchema(
      Map<String, MappingSchemaSide> outputSchemas,
      MappingIntent intent,
      MappingSchemaSide target,
      GraphPatchExecutionContext context) {
    String intentId = intent.mappingIntentId();
    outputSchemas.put(intentId, target);
    outputSchemas.put("transform-" + intentId, target);
    String siteId = siteNodeId(context, intentId);
    if (siteId != null && !siteId.isBlank()) {
      outputSchemas.put(siteId, target);
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

  private static Reference consumedBrief(GraphPatchExecutionContext context) {
    if (context == null || context.consumedArtifacts() == null) {
      return null;
    }
    Reference last = null;
    for (Reference ref : context.consumedArtifacts()) {
      if (ref != null && ref.kind() == Kind.REQUIREMENT_BRIEF) {
        last = ref;
      }
    }
    return last;
  }

  private record FrozenHop(
      MappingIntent intent, MappingBoundarySchemas schemas, MappingEnvelope envelope) {}

  public record Result(
      boolean blocked,
      String blockedMessage,
      GraphPatchExecutionContext context,
      List<Reference> envelopeRefs,
      String mappingGenerationContext,
      List<PlanValidationFinding> findings) {

    public Result {
      blockedMessage = blockedMessage == null ? "" : blockedMessage;
      envelopeRefs = envelopeRefs == null ? List.of() : List.copyOf(envelopeRefs);
      mappingGenerationContext =
          mappingGenerationContext == null ? "" : mappingGenerationContext;
      findings = findings == null ? List.of() : List.copyOf(findings);
    }

    static Result blocked(String message, GraphPatchExecutionContext context) {
      return blocked(message, context, List.of());
    }

    static Result blocked(
        String message, GraphPatchExecutionContext context, List<PlanValidationFinding> findings) {
      return new Result(true, message, context, List.of(), "", findings);
    }

    static Result ready(
        GraphPatchExecutionContext context,
        List<Reference> envelopeRefs,
        String mappingGenerationContext) {
      return new Result(false, "", context, envelopeRefs, mappingGenerationContext, List.of());
    }
  }
}
