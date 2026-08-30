package org.qubership.integration.platform.ai.plan.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.CompilerSkillContextBuilder;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.plan.mapping.envelope.JsonSchemaMessageSchemaFactory;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.plan.mapping.schema.DefaultMappingBoundarySchemaResolver;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingBoundarySchemas;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
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
    return TRANSFORMATION_GENERATOR.equals(skillId) || SCRIPT_GENERATOR.equals(skillId);
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
    if (intents.isEmpty()) {
      return Result.ready(context, List.of(), "");
    }
    DefaultMappingBoundarySchemaResolver resolver =
        new DefaultMappingBoundarySchemaResolver(artifacts, compilationId, objectMapper);
    JsonSchemaMessageSchemaFactory envelopeFactory =
        new JsonSchemaMessageSchemaFactory(objectMapper);
    Map<String, MappingEnvelope> envelopesByTransformNodeId = new LinkedHashMap<>();
    List<Reference> envelopeRefs = new ArrayList<>();
    List<Reference> consumed =
        new ArrayList<>(context == null ? List.of() : context.consumedArtifacts());
    StringBuilder rendered = new StringBuilder();
    for (MappingIntent intent : intents) {
      MappingBoundarySchemas schemas =
          resolver.resolve(revision, bindings, intent, envelopesByTransformNodeId);
      Optional<String> blocked = MappingContractGate.blockedMessage(intent, schemas);
      if (blocked.isPresent()) {
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
      if (!rendered.isEmpty()) {
        rendered.append("\n\n");
      }
      rendered.append(
          contextBuilder.renderMappingGenerationContext(
              intent, envelope, schemas.source(), schemas.target()));
    }
    String mappingContext = rendered.toString();
    GraphPatchExecutionContext updated = context;
    if (context != null) {
      updated =
          context.withConsumedArtifacts(consumed).withMappingGenerationContext(mappingContext);
    }
    return Result.ready(updated, envelopeRefs, mappingContext);
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
