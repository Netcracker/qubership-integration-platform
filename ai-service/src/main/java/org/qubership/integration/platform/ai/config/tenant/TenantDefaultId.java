package org.qubership.integration.platform.ai.config.tenant;

import java.util.NoSuchElementException;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Resolves {@code tenant.default.id} from the first non-blank MicroProfile Config source.
 *
 * <p>Skipping blanks lets Consul's tenant UUID win when a high-ordinal properties file would
 * otherwise define an empty value (SmallRye {@code SRCFG00040}).
 */
final class TenantDefaultId {

  static final String PROPERTY = "tenant.default.id";

  private TenantDefaultId() {}

  static String resolve() {
    for (ConfigSource source : ConfigProvider.getConfig().getConfigSources()) {
      String value = source.getValue(PROPERTY);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    throw new NoSuchElementException(
        "No non-blank value for "
            + PROPERTY
            + "; set TENANT_DEFAULT_ID (upstream) or Consul key"
            + " config/{namespace}/application/tenant/default/id (NC)");
  }
}
