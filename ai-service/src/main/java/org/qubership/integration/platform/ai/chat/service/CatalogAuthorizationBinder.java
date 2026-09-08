package org.qubership.integration.platform.ai.chat.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.qubership.integration.platform.ai.a2a.transport.A2aClientCorrelationCarrier;
import org.qubership.integration.platform.ai.integration.catalog.auth.CatalogAuthorizationCapture;
import org.qubership.integration.platform.ai.integration.catalog.auth.CatalogAuthorizationContext;
import org.qubership.integration.platform.ai.integration.catalog.auth.CatalogAuthorizationSupport;
import org.qubership.integration.platform.ai.integration.catalog.auth.InboundCatalogAuthorization;

@ApplicationScoped
public class CatalogAuthorizationBinder {

  public enum RefreshResult {
    UPDATED,
    NOT_BOUND,
    INVALID
  }

  private final CatalogAuthorizationCapture capture;

  public CatalogAuthorizationBinder(CatalogAuthorizationCapture capture) {
    this.capture = capture;
  }

  public Optional<String> inboundAuthorization() {
    return inboundAuthorization(null);
  }

  public Optional<String> inboundAuthorization(String a2aRequestCorrelationId) {
    Optional<String> fromCarrier = A2aClientCorrelationCarrier.catalogAuthorization(a2aRequestCorrelationId);
    if (fromCarrier.isPresent()) {
      return fromCarrier;
    }
    Optional<String> fromRequest = capture.authorization();
    if (fromRequest.isPresent()) {
      return fromRequest;
    }
    return InboundCatalogAuthorization.current();
  }

  public void bindConversation(String conversationId) {
    bindConversation(conversationId, null);
  }

  /**
   * Binds catalog auth for the conversation when an inbound Bearer token is available.
   *
   * @return {@code true} when auth was bound
   */
  public boolean bindConversation(String conversationId, String a2aRequestCorrelationId) {
    Optional<String> authorization = inboundAuthorization(a2aRequestCorrelationId);
    if (authorization.isEmpty()) {
      return false;
    }
    CatalogAuthorizationContext.bind(conversationId, authorization.get());
    return true;
  }

  public RefreshResult refreshConversation(String conversationId, String authorizationHeader) {
    if (!CatalogAuthorizationContext.isBound(conversationId)) {
      return RefreshResult.NOT_BOUND;
    }
    Optional<String> authorization = CatalogAuthorizationSupport.normalizeAuthorization(authorizationHeader);
    if (authorization.isEmpty()) {
      return RefreshResult.INVALID;
    }
    CatalogAuthorizationContext.update(conversationId, authorization.get());
    return RefreshResult.UPDATED;
  }

  public void clearConversation(String conversationId) {
    CatalogAuthorizationContext.clear(conversationId);
  }
}
