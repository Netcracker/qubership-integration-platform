package org.qubership.integration.platform.ai.config.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.netcracker.cloud.framework.contexts.tenant.TenantContextObject;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppDefaultTenantStrategyTest {

  private static final String TENANT_DEFAULT_ID_PROPERTY = "tenant.default.id";

  private String previousTenantId;

  @BeforeEach
  void setDefaultTenantId() {
    previousTenantId = System.setProperty(TENANT_DEFAULT_ID_PROPERTY, "default-tenant");
  }

  @AfterEach
  void restoreDefaultTenantId() {
    if (previousTenantId == null) {
      System.clearProperty(TENANT_DEFAULT_ID_PROPERTY);
    } else {
      System.setProperty(TENANT_DEFAULT_ID_PROPERTY, previousTenantId);
    }
  }

  @Test
  void get_returnsResolvedDefaultTenant() {
    AppDefaultTenantStrategy strategy = new AppDefaultTenantStrategy();

    assertEquals("default-tenant", strategy.get().getTenant());
  }

  @Test
  void get_resolvesSameTenantIdOnRepeatedCalls() {
    AppDefaultTenantStrategy strategy = new AppDefaultTenantStrategy();

    assertEquals("default-tenant", strategy.get().getTenant());
    assertEquals("default-tenant", strategy.get().getTenant());
  }

  @Test
  void defaultObjectSupplier_returnsSameTenantId() {
    AppDefaultTenantStrategy strategy = new AppDefaultTenantStrategy();

    Supplier<TenantContextObject> supplier = strategy.defaultObjectSupplier();

    assertEquals(strategy.get().getTenant(), supplier.get().getTenant());
  }
}
