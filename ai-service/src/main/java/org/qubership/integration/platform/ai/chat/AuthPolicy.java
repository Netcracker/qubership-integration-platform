package org.qubership.integration.platform.ai.chat;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Whether callers must present their own bearer token. On by default (deployed environments).
 * Local stacks without an identity provider switch it off with {@code QIP_AI_AUTH_REQUIRED=false}:
 * a request without a bearer is then served with the service identity ({@code CATALOG_TOKEN}), as
 * it was before caller tokens were propagated. A bearer that does arrive is always honoured.
 */
@ApplicationScoped
public class AuthPolicy {

  static final String CFG_REQUIRED = "qip.ai.auth.required";

  private static final Logger LOG = Logger.getLogger(AuthPolicy.class);

  public static boolean required() {
    return ConfigProvider.getConfig().getOptionalValue(CFG_REQUIRED, Boolean.class).orElse(true);
  }

  void warnWhenDisabled(@Observes StartupEvent event) {
    if (!required()) {
      LOG.warn(
          "qip.ai.auth.required=false: requests without a bearer token are accepted and use the " +
              "service identity. Never enable this outside a local environment.");
    }
  }
}
