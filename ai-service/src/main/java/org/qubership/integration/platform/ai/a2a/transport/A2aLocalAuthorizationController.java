package org.qubership.integration.platform.ai.a2a.transport;

import io.quarkus.security.spi.runtime.AuthorizationController;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.qubership.integration.platform.ai.a2a.A2aFeatureGate;

/**
 * Local MVP authorization controller for the A2A surface.
 *
 * <p>When the runtime A2A feature flag is on, method-level {@code @Authenticated} enforcement is
 * disabled so local unauthenticated clients can call A2A. When the flag is off, normal
 * authorization stays enabled. Identity still comes from {@code CallerContextProvider}, never from
 * Message metadata. OIDC-backed providers replace the local caller seam later without changing
 * transport signatures.
 */
@Alternative
@Priority(Interceptor.Priority.LIBRARY_AFTER)
@ApplicationScoped
public class A2aLocalAuthorizationController extends AuthorizationController {

  private final A2aFeatureGate featureGate;

  @Inject
  public A2aLocalAuthorizationController(A2aFeatureGate featureGate) {
    this.featureGate = featureGate;
  }

  @Override
  public boolean isAuthorizationEnabled() {
    return !featureGate.enabled();
  }
}
