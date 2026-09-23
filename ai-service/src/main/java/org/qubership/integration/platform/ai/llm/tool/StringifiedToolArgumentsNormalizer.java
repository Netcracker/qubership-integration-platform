package org.qubership.integration.platform.ai.llm.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.service.tool.ToolExecutionResult;
import io.quarkiverse.langchain4j.runtime.ToolsRecorder;
import io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutor;
import io.quarkiverse.langchain4j.runtime.tool.ToolMethodCreateInfo;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCaptureTool;
import org.qubership.integration.platform.ai.plan.ProductRequirementBriefTool;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanCaptureTool;

/**
 * Removes one accidental JSON-string layer from object and array tool parameters before binding.
 * The executor factory applies lower-priority wrappers last, which places this check at the outer
 * request boundary.
 */
@Singleton
@Priority(Integer.MIN_VALUE)
public class StringifiedToolArgumentsNormalizer implements QuarkusToolExecutor.Wrapper {

  private static final Logger LOG = Logger.getLogger(StringifiedToolArgumentsNormalizer.class);

  private final ObjectMapper objectMapper;
  private final ProductRequirementBriefTool productBriefTool;
  private final DesignPlanCaptureTool designPlanTool;

  @Inject
  public StringifiedToolArgumentsNormalizer(
      ObjectMapper objectMapper, ProductRequirementBriefTool productBriefTool,
      DesignPlanCaptureTool designPlanTool) {
    this.objectMapper = objectMapper;
    this.productBriefTool = productBriefTool;
    this.designPlanTool = designPlanTool;
  }

  StringifiedToolArgumentsNormalizer(ObjectMapper objectMapper) {
    this(objectMapper, null, null);
  }

  @Override
  public ToolExecutionResult wrap(
      ToolExecutionRequest request,
      InvocationContext invocationContext,
      BiFunction<ToolExecutionRequest, InvocationContext, ToolExecutionResult> next,
      QuarkusToolExecutor executor) {
    ToolMethodCreateInfo method = executor.getMethodCreateInfo();
    if (method != null && ChainSemanticCaptureTool.TOOL_NAME.equals(method.methodName())) {
      StructuredCaptureArguments.Result inspected =
          StructuredCaptureArguments.inspect(request, method, objectMapper);
      if (inspected.issue() != null) {
        Object memoryId = invocationContext == null ? null : invocationContext.chatMemoryId();
        String conversationId = memoryId == null
            ? ToolSession.resolveConversationId() : memoryId.toString();
        String result = ChainSemanticCaptureTool.rejectArguments(
            conversationId, new ChainSemanticCaptureTool.CaptureIssue(
                inspected.issue().code(), inspected.issue().path(), inspected.issue().message()));
        return ToolExecutionResult.builder().result(result).resultText(result).build();
      }
      return next.apply(inspected.request(), invocationContext);
    }
    if (isProductBriefCapture(method)) {
      StructuredCaptureArguments.Result inspected =
          StructuredCaptureArguments.inspect(request, method, objectMapper);
      if (inspected.issue() != null) {
        Object memoryId = invocationContext == null ? null : invocationContext.chatMemoryId();
        String conversationId = memoryId == null
            ? ToolSession.resolveConversationId() : memoryId.toString();
        String result = productBriefTool.rejectArguments(conversationId, inspected.issue());
        return ToolExecutionResult.builder().result(result).resultText(result).build();
      }
      return next.apply(inspected.request(), invocationContext);
    }
    if (isDesignPlanCapture(method)) {
      StructuredCaptureArguments.Result inspected =
          StructuredCaptureArguments.inspect(request, method, objectMapper);
      if (inspected.issue() != null) {
        Object memoryId = invocationContext == null ? null : invocationContext.chatMemoryId();
        String conversationId = memoryId == null
            ? ToolSession.resolveConversationId() : memoryId.toString();
        String result = designPlanTool.rejectArguments(conversationId, inspected.issue());
        return ToolExecutionResult.builder().result(result).resultText(result).build();
      }
      return next.apply(inspected.request(), invocationContext);
    }
    return next.apply(normalize(request, executor.getMethodCreateInfo()), invocationContext);
  }

  private static boolean isProductBriefCapture(ToolMethodCreateInfo method) {
    if (method == null || !"captureRequirementBrief".equals(method.methodName())) {
      return false;
    }
    List<ToolMethodCreateInfo> productMethods =
        ToolsRecorder.getMetadata().get(ProductRequirementBriefTool.class.getName());
    return productMethods != null && productMethods.stream()
        .anyMatch(product -> product.invokerClassName().equals(method.invokerClassName()));
  }

  private static boolean isDesignPlanCapture(ToolMethodCreateInfo method) {
    if (method == null || !"captureDesignPlan".equals(method.methodName())) {
      return false;
    }
    List<ToolMethodCreateInfo> methods =
        ToolsRecorder.getMetadata().get(DesignPlanCaptureTool.class.getName());
    return methods != null && methods.stream()
        .anyMatch(plan -> plan.invokerClassName().equals(method.invokerClassName()));
  }

  ToolExecutionRequest normalize(ToolExecutionRequest request, ToolMethodCreateInfo method) {
    if (request == null || method == null || request.arguments() == null) {
      return request;
    }

    JsonObjectSchema parameters = method.toolSpecification().parameters();
    if (parameters == null || parameters.properties() == null) {
      return request;
    }

    try {
      JsonNode parsedArguments = objectMapper.readTree(request.arguments());
      if (!(parsedArguments instanceof ObjectNode arguments)) {
        return request;
      }

      List<String> normalizedParameters = new ArrayList<>();
      for (Map.Entry<String, JsonSchemaElement> property : parameters.properties().entrySet()) {
        JsonNode value = arguments.get(property.getKey());
        if (value == null || !value.isTextual()) {
          continue;
        }

        JsonNode decoded = parse(value.textValue());
        if (matchesStructuredSchema(decoded, property.getValue(), parameters.definitions())) {
          arguments.set(property.getKey(), decoded);
          normalizedParameters.add(property.getKey());
        }
      }

      if (normalizedParameters.isEmpty()) {
        return request;
      }
      LOG.debugf(
          "Decoded stringified JSON tool arguments: tool=%s, parameters=%s",
          request.name(), normalizedParameters);
      return request.toBuilder().arguments(objectMapper.writeValueAsString(arguments)).build();
    } catch (JsonProcessingException e) {
      return request;
    }
  }

  private JsonNode parse(String value) {
    try {
      return objectMapper.readTree(value);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private static boolean matchesStructuredSchema(
      JsonNode value,
      JsonSchemaElement schema,
      Map<String, JsonSchemaElement> definitions) {
    if (value == null || schema == null) {
      return false;
    }
    if (schema instanceof JsonObjectSchema) {
      return value.isObject();
    }
    if (schema instanceof JsonArraySchema) {
      return value.isArray();
    }
    if (schema instanceof JsonAnyOfSchema anyOf) {
      List<JsonSchemaElement> nonNull =
          anyOf.anyOf().stream().filter(candidate -> !(candidate instanceof JsonNullSchema)).toList();
      return !nonNull.isEmpty()
          && nonNull.stream().allMatch(candidate -> isStructuredSchema(candidate, definitions))
          && nonNull.stream()
              .anyMatch(candidate -> matchesStructuredSchema(value, candidate, definitions));
    }
    if (schema instanceof JsonReferenceSchema reference && definitions != null) {
      return matchesStructuredSchema(value, definitions.get(reference.reference()), definitions);
    }
    return false;
  }

  private static boolean isStructuredSchema(
      JsonSchemaElement schema, Map<String, JsonSchemaElement> definitions) {
    if (schema instanceof JsonObjectSchema || schema instanceof JsonArraySchema) {
      return true;
    }
    if (schema instanceof JsonReferenceSchema reference && definitions != null) {
      return isStructuredSchema(definitions.get(reference.reference()), definitions);
    }
    if (schema instanceof JsonAnyOfSchema anyOf) {
      return anyOf.anyOf().stream()
          .filter(candidate -> !(candidate instanceof JsonNullSchema))
          .allMatch(candidate -> isStructuredSchema(candidate, definitions));
    }
    return false;
  }
}
