package org.qubership.integration.platform.ai.config.tenant;

import com.netcracker.cloud.framework.contexts.tenant.DefaultTenantStrategy;
import com.netcracker.cloud.framework.contexts.tenant.TenantContextObject;
import java.util.function.Supplier;

public class AppDefaultTenantStrategy extends DefaultTenantStrategy {

  public AppDefaultTenantStrategy() {
    super(AppDefaultTenantStrategy::resolveDefaultTenant);
  }

  @Override
  protected Supplier<TenantContextObject> defaultObjectSupplier() {
    return AppDefaultTenantStrategy::resolveDefaultTenant;
  }

  @Override
  public TenantContextObject get() {
    return resolveDefaultTenant();
  }

  private static TenantContextObject resolveDefaultTenant() {
    return new TenantContextObject(TenantDefaultId.resolve());
  }
}
