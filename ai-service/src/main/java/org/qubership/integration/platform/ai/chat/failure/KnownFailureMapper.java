package org.qubership.integration.platform.ai.chat.failure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogNonRetryableResponseException;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogRestSupport;

/**
 * Maps catalog timeouts and non-retryable HTTP responses to sanitized chat copy. Unknown throwables
 * stay unmapped so the SSE stream can still fail.
 */
@ApplicationScoped
public class KnownFailureMapper {

  public static final String CATALOG_TIMEOUT_MESSAGE =
      "Couldn't finish this catalog request. The catalog did not respond in time. Try again.";

  private final ObjectMapper objectMapper = new ObjectMapper();

  public Optional<KnownFailure> tryMap(Throwable error, CatalogOperation operation) {
    Throwable unwrapped = unwrapOnce(error);
    TimeoutException timeout = findTimeout(unwrapped);
    if (timeout != null) {
      return Optional.of(new KnownFailure(CATALOG_TIMEOUT_MESSAGE, diagnostic(timeout)));
    }
    CatalogNonRetryableResponseException refused = findCatalogRefusal(unwrapped);
    if (refused != null) {
      return Optional.of(mapCatalogRefusal(refused, operation));
    }
    return Optional.empty();
  }

  private KnownFailure mapCatalogRefusal(
      CatalogNonRetryableResponseException refused, CatalogOperation operation) {
    String prefix = "Couldn't " + operation.verb();
    String body = CatalogRestSupport.readResponseBodySnippet(refused.getResponse());
    if (body == null || body.isBlank()) {
      return new KnownFailure(prefix + ".", diagnostic(refused));
    }
    try {
      JsonNode root = objectMapper.readTree(body);
      String errorMessage = root.path("errorMessage").asText("");
      JsonNode details = root.path("details");
      String elementName = details.path("elementName").asText("");
      String elementId = details.path("elementId").asText("");
      StringBuilder reply = new StringBuilder(prefix);
      if (!errorMessage.isBlank()) {
        reply.append(": ").append(errorMessage);
      }
      if (!elementName.isBlank() || !elementId.isBlank()) {
        reply.append(". Element");
        if (!elementName.isBlank()) {
          reply.append(" ").append(elementName);
        }
        if (!elementId.isBlank()) {
          reply.append(" (").append(elementId).append(")");
        }
      }
      reply.append(".");
      return new KnownFailure(reply.toString(), diagnostic(refused));
    } catch (Exception parseFailed) {
      return new KnownFailure(prefix + ".", diagnostic(refused));
    }
  }

  private static Throwable unwrapOnce(Throwable error) {
    if (error instanceof CompletionException && error.getCause() != null) {
      return error.getCause();
    }
    if (error != null && error.getClass() == RuntimeException.class && error.getCause() != null) {
      return error.getCause();
    }
    return error;
  }

  private static TimeoutException findTimeout(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof TimeoutException timeout) {
        return timeout;
      }
      current = current.getCause();
    }
    return null;
  }

  private static CatalogNonRetryableResponseException findCatalogRefusal(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof CatalogNonRetryableResponseException refused) {
        return refused;
      }
      current = current.getCause();
    }
    return null;
  }

  private static String diagnostic(Throwable error) {
    String message = error.getMessage();
    if (message == null || message.isBlank()) {
      return error.getClass().getName();
    }
    return error.getClass().getName() + ": " + message;
  }
}
