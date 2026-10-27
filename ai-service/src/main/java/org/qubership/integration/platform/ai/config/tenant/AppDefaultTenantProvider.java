package org.qubership.integration.platform.ai.config.tenant;

import com.netcracker.cloud.context.propagation.core.RegisterProvider;
import com.netcracker.cloud.context.propagation.core.Strategy;
import com.netcracker.cloud.framework.contexts.tenant.DefaultTenantProvider;
import com.netcracker.cloud.framework.contexts.tenant.TenantContextObject;

@RegisterProvider
public class AppDefaultTenantProvider extends DefaultTenantProvider {

  private final AppDefaultTenantStrategy defaultTenantStrategy = new AppDefaultTenantStrategy();

  @Override
  public Strategy<TenantContextObject> strategy() {
    return defaultTenantStrategy;
  }

  @Override
  public int providerOrder() {
    return Integer.MIN_VALUE;
  }
}
