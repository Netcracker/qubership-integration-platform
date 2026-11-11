package org.qubership.integration.platform.ai.integration.catalog.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogRestSupport;

/** A catalog client-response failure that must not be retried. */
public final class CatalogNonRetryableResponseException extends WebApplicationException {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final String bodySnippet;

  public CatalogNonRetryableResponseException(Response response) {
    this(response, CatalogRestSupport.readResponseBodySnippet(response));
  }

  public CatalogNonRetryableResponseException(Response response, String bodySnippet) {
    super(response);
    this.bodySnippet = bodySnippet;
  }

  public String bodySnippet() {
    return bodySnippet;
  }

  /** Parsed {@code errorMessage} when the body is JSON; otherwise an empty string. */
  public String catalogErrorMessage() {
    if (bodySnippet == null || bodySnippet.isBlank()) {
      return "";
    }
    try {
      JsonNode root = OBJECT_MAPPER.readTree(bodySnippet);
      String errorMessage = root.path("errorMessage").asText("");
      return errorMessage.isBlank() ? "" : errorMessage;
    } catch (Exception parseFailed) {
      return "";
    }
  }
}
