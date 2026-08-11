package org.qubership.integration.platform.ai.a2a.access;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/**
 * Local permit-all caller. Returns configured tenant and subject defaults.
 *
 * <p>Resolves identity only from configuration. Never reads Message metadata or request headers.
 * Replace this bean with an OIDC-backed {@link CallerContextProvider} without changing callers.
 */
@ApplicationScoped
public class LocalCallerContextProvider implements CallerContextProvider {

  private final String tenantId;
  private final String subjectId;

  @Inject
  public LocalCallerContextProvider(AppConfig appConfig) {
    this.tenantId = appConfig.a2a().defaultTenantId();
    this.subjectId = appConfig.a2a().defaultSubjectId();
  }

  /** Test helper. */
  public LocalCallerContextProvider(String tenantId, String subjectId) {
    this.tenantId = tenantId;
    this.subjectId = subjectId;
  }

  @Override
  public CallerContext current() {
    return new CallerContext(tenantId, subjectId);
  }
}
