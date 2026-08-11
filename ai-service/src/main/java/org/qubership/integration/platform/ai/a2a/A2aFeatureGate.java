package org.qubership.integration.platform.ai.a2a;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/**
 * Runtime A2A rollout gate backed by {@code qip.ai.a2a.enabled}.
 *
 * <p>Default is off. Disabling leaves persisted Task rows untouched and does not affect browser
 * chat. Re-enabling makes previously persisted Tasks readable again without a data migration.
 */
@ApplicationScoped
public class A2aFeatureGate {

  public static final String DISABLED_MESSAGE = "A2A is disabled";

  private final AppConfig appConfig;

  @Inject
  public A2aFeatureGate(AppConfig appConfig) {
    this.appConfig = appConfig;
  }

  public boolean enabled() {
    return appConfig.a2a().enabled();
  }

  public void requireEnabled() {
    if (!enabled()) {
      throw new A2aFeatureDisabledException(DISABLED_MESSAGE);
    }
  }
}
