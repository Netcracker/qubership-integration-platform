package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.configuration.AppConfig;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Routes element PATCH validation to legacy or networknt engine based on configuration. */
@ApplicationScoped
public class ElementPatchValidationRouter {

  private final AppConfig appConfig;
  private final NetworkntElementPatchValidator networkntElementPatchValidator;
  private final ObjectMapper objectMapper;

  @Inject
  public ElementPatchValidationRouter(
      AppConfig appConfig,
      NetworkntElementPatchValidator networkntElementPatchValidator,
      ObjectMapper objectMapper) {
    this.appConfig = appConfig;
    this.networkntElementPatchValidator = networkntElementPatchValidator;
    this.objectMapper = objectMapper;
  }

  public ObjectNode validate(
      String elementType,
      String patchJson,
      ElementPropertiesSchemaModel model,
      SchemaRefResolver schemaRefResolver) {
    if (useNetworknt(elementType)) {
      return networkntElementPatchValidator.validate(
          elementType, patchJson, model, schemaRefResolver);
    }
    return ElementPatchValidator.validate(patchJson, model, schemaRefResolver, objectMapper);
  }

  boolean useNetworknt(String elementType) {
    if (elementType == null || elementType.isBlank()) {
      return false;
    }
    ElementPatchValidationEngine engine =
        ElementPatchValidationEngine.fromConfig(appConfig.schema().validation().engine());
    if (engine != ElementPatchValidationEngine.NETWORKNT) {
      return false;
    }
    return pilotElementTypes().contains(elementType.trim().toLowerCase(Locale.ROOT));
  }

  private Set<String> pilotElementTypes() {
    String raw = appConfig.schema().validation().networkntElementTypes();
    if (raw == null || raw.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(s -> s.toLowerCase(Locale.ROOT))
        .collect(Collectors.toSet());
  }
}
