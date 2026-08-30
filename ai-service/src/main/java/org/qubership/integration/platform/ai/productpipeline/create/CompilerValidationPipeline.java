package org.qubership.integration.platform.ai.productpipeline.create;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationPass;
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerQualityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.schema.ElementPatchDefaultsApplicator;
import org.qubership.integration.platform.ai.schema.ElementPatchValidationMessages;
import org.qubership.integration.platform.ai.schema.ElementPatchValidator;
import org.qubership.integration.platform.ai.schema.ElementPropertiesSchemaModelBuilder;
import org.qubership.integration.platform.ai.schema.SchemaRefResolver;
import org.qubership.integration.platform.ai.schema.SchemaResourceLoader;

/** Deterministic compiler pass pipeline over assembled graph candidates. */
@ApplicationScoped
public class CompilerValidationPipeline {

  private static final int SCHEMA_VERSION = 1;
  public static final String ELEMENT = "cip-element-validator";
  public static final String STRUCTURAL = "cip-structural-validator";
  public static final String CONFIGURATION = "cip-configuration-validator";
  public static final String SECURITY = "cip-security-validator";
  public static final String QUALITY = "cip-quality-validator";

  private final Function<ChainPlanGraph, ValidationResult> elementValidator;
  private final Function<ChainPlanGraph, ValidationResult> structuralValidator;
  private final Function<ChainPlanGraph, ValidationResult> configurationValidator;
  private final CompilerSecurityValidator compilerSecurityValidator;
  private final CompilerQualityValidator compilerQualityValidator;

  @Inject
  public CompilerValidationPipeline(
      SchemaResourceLoader schemaResourceLoader,
      SchemaRefResolver schemaRefResolver,
      ObjectMapper objectMapper,
      ChainPlanGraphValidator chainPlanGraphValidator,
      DeterministicElementSchemaService deterministicElementSchemaService,
      CompilerSecurityValidator compilerSecurityValidator,
      CompilerQualityValidator compilerQualityValidator) {
    this(
        graph ->
            validateElements(
                graph,
                schemaResourceLoader,
                schemaRefResolver,
                objectMapper,
                deterministicElementSchemaService),
        graph -> validateStructural(graph, chainPlanGraphValidator),
        graph -> validateConfiguration(graph, deterministicElementSchemaService, objectMapper),
        compilerSecurityValidator,
        compilerQualityValidator);
  }

  CompilerValidationPipeline(
      Function<ChainPlanGraph, ValidationResult> elementValidator,
      Function<ChainPlanGraph, ValidationResult> structuralValidator,
      Function<ChainPlanGraph, ValidationResult> configurationValidator,
      CompilerSecurityValidator compilerSecurityValidator,
      CompilerQualityValidator compilerQualityValidator) {
    this.elementValidator = Objects.requireNonNull(elementValidator, "elementValidator");
    this.structuralValidator = Objects.requireNonNull(structuralValidator, "structuralValidator");
    this.configurationValidator =
        Objects.requireNonNull(configurationValidator, "configurationValidator");
    this.compilerSecurityValidator =
        Objects.requireNonNull(compilerSecurityValidator, "compilerSecurityValidator");
    this.compilerQualityValidator =
        Objects.requireNonNull(compilerQualityValidator, "compilerQualityValidator");
  }

  public CompilerValidationBundle validate(
      String graphDigest, NamingManifest namingManifest, ChainPlanGraph graph) {
    List<CompilerValidationPass> passes = new ArrayList<>();
    for (String validatorSkillId : validatorSkillIds()) {
      passes.add(new CompilerValidationPass(validatorSkillId, validatePass(validatorSkillId, namingManifest, graph)));
    }
    return new CompilerValidationBundle(SCHEMA_VERSION, graphDigest, List.copyOf(passes));
  }

  public ValidationResult validatePass(
      String validatorSkillId, NamingManifest namingManifest, ChainPlanGraph graph) {
    return switch (validatorSkillId) {
      case ELEMENT -> elementValidator.apply(graph);
      case STRUCTURAL -> structuralValidator.apply(graph);
      case CONFIGURATION -> configurationValidator.apply(graph);
      case SECURITY -> compilerSecurityValidator.validate(graph);
      case QUALITY -> compilerQualityValidator.validate(namingManifest, graph);
      default -> throw new IllegalArgumentException("Unsupported validator skill id: " + validatorSkillId);
    };
  }

  public static List<String> validatorSkillIds() {
    return List.of(ELEMENT, STRUCTURAL, CONFIGURATION, SECURITY, QUALITY);
  }

  @SuppressWarnings({"java:S3776", "java:S135"})
  private static ValidationResult validateElements(
      ChainPlanGraph graph,
      SchemaResourceLoader schemaResourceLoader,
      SchemaRefResolver schemaRefResolver,
      ObjectMapper objectMapper,
      DeterministicElementSchemaService deterministicElementSchemaService) {
    if (graph == null || graph.nodes() == null) {
      return new ValidationResult(true, List.of(), "element validation passed");
    }
    List<ValidationIssue> issues = new ArrayList<>();
    int counter = 1;
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null || node.type() == null || node.type().isBlank()) {
        continue;
      }
      String elementType = node.type().trim();
      if (!schemaResourceLoader.existsElementSchema(elementType)) {
        issues.add(
            new ValidationIssue(
                "element-" + counter++,
                ValidationSeverity.BLOCKER,
                "Unknown element schema for type '" + elementType + "'",
                ELEMENT,
                node.nodeId() == null ? List.of() : List.of(node.nodeId()),
                List.of(),
                "Use a known CIP element type"));
        continue;
      }
      String patchJson =
          toPropertiesPatchJson(
              elementType,
              node.properties(),
              objectMapper,
              deterministicElementSchemaService,
              schemaRefResolver);
      var model = ElementPropertiesSchemaModelBuilder.build(elementType, schemaRefResolver);
      JsonNode result = ElementPatchValidator.validate(patchJson, model, schemaRefResolver, objectMapper);
      if (!result.path("valid").asBoolean(true)) {
        String summary =
            ElementPatchValidationMessages.summarizeFailure(result.toString(), objectMapper);
        issues.add(
            new ValidationIssue(
                "element-" + counter++,
                ValidationSeverity.BLOCKER,
                summary,
                ELEMENT,
                node.nodeId() == null ? List.of() : List.of(node.nodeId()),
                List.of(),
                "Fix node properties according to schema"));
      }
    }
    return summarize(issues, "element validation");
  }

  private static ValidationResult validateStructural(
      ChainPlanGraph graph, ChainPlanGraphValidator chainPlanGraphValidator) {
    if (graph == null) {
      return new ValidationResult(false, List.of(), "structural validation failed");
    }
    List<String> findings = chainPlanGraphValidator.validate(graph);
    if (findings.isEmpty()) {
      return new ValidationResult(true, List.of(), "structural validation passed");
    }
    List<ValidationIssue> issues = new ArrayList<>();
    int counter = 1;
    for (String finding : findings) {
      issues.add(
          new ValidationIssue(
              "structural-" + counter++,
              ValidationSeverity.BLOCKER,
              finding,
              STRUCTURAL,
              List.of(),
              List.of(),
              "Fix structural graph violation"));
    }
    return summarize(issues, "structural validation");
  }

  @SuppressWarnings("java:S3776")
  private static ValidationResult validateConfiguration(
      ChainPlanGraph graph,
      DeterministicElementSchemaService deterministicElementSchemaService,
      ObjectMapper objectMapper) {
    if (graph == null || graph.nodes() == null) {
      return new ValidationResult(true, List.of(), "configuration validation passed");
    }
    List<ValidationIssue> issues = new ArrayList<>();
    int counter = 1;
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null || node.properties() == null || node.type() == null) {
        continue;
      }
      for (PlanProperty property : node.properties()) {
        if (property == null || property.key() == null) {
          continue;
        }
        Object coerced =
            deterministicElementSchemaService.coercePatchPropertyValue(
                node.type(), property.key(), property.value());
        JsonNode value = objectMapper.valueToTree(coerced);
        Optional<String> error =
            deterministicElementSchemaService.validateCapturePropertyValue(
                node.type(), property.key(), value);
        if (error.isPresent()) {
          issues.add(
              new ValidationIssue(
                  "configuration-" + counter++,
                  ValidationSeverity.BLOCKER,
                  "Invalid property '" + property.key() + "' for " + node.type() + ": " + error.get(),
                  CONFIGURATION,
                  node.nodeId() == null ? List.of() : List.of(node.nodeId()),
                  List.of(),
                  "Fix property according to schema"));
        }
      }
    }
    return summarize(issues, "configuration validation");
  }

  private static ValidationResult summarize(List<ValidationIssue> issues, String title) {
    boolean valid = issues.stream().noneMatch(issue -> issue.severity() == ValidationSeverity.BLOCKER);
    if (valid) {
      return new ValidationResult(true, List.copyOf(issues), title + " passed");
    }
    return new ValidationResult(
        false,
        List.copyOf(issues),
        title
            + " failed with "
            + issues.stream().filter(issue -> issue.severity() == ValidationSeverity.BLOCKER).count()
            + " blocker(s)");
  }

  private static String toPropertiesPatchJson(
      String elementType,
      List<PlanProperty> properties,
      ObjectMapper objectMapper,
      DeterministicElementSchemaService deterministicElementSchemaService,
      SchemaRefResolver schemaRefResolver) {
    try {
      var root = objectMapper.createObjectNode();
      var props = objectMapper.createObjectNode();
      if (properties != null) {
        for (PlanProperty property : properties) {
          if (property == null || property.key() == null || property.key().isBlank()) {
            continue;
          }
          Object coerced =
              deterministicElementSchemaService.coercePatchPropertyValue(
                  elementType, property.key(), property.value());
          props.set(property.key(), objectMapper.valueToTree(coerced));
        }
      }
      root.set("properties", props);
      if (elementType != null && !elementType.isBlank()) {
        var model = ElementPropertiesSchemaModelBuilder.build(elementType.trim(), schemaRefResolver);
        ElementPatchDefaultsApplicator.applyMissingPropertyDefaults(
            root, model, schemaRefResolver, objectMapper, null);
      }
      return objectMapper.writeValueAsString(root);
    } catch (Exception e) {
      return "{\"properties\":{}}";
    }
  }
}
