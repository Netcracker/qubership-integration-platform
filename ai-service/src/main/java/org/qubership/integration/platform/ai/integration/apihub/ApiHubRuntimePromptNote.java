package org.qubership.integration.platform.ai.integration.apihub;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/**
 * When APIHub MCP base URL is unset, prefixes user-facing LLM input so models skip APIHub tools.
 */
@ApplicationScoped
public class ApiHubRuntimePromptNote {

  private static final String PREFIX =
      "Runtime note: APIHub MCP is not configured (empty qip.ai.apihub.base-url). "
          + "Do not call searchRestApiOperations or getRestApiOperationSpecification. "
          + "Resolve service-call bindings using QIP catalog tools only "
          + "(searchCatalogSystems, getApiSpecifications, listCatalogOperations) "
          + "and the approved plan text.\n\n---\n\n";

  @Inject AppConfig appConfig;

  /**
   * @param userText text to send to the model (may be null)
   * @return prefixed text when APIHub is disabled, otherwise original
   */
  public String maybePrefix(String userText) {
    String base = appConfig.apihub().baseUrl();
    if (base == null || base.isBlank()) {
      return PREFIX + (userText != null ? userText : "");
    }
    return userText != null ? userText : "";
  }
}
