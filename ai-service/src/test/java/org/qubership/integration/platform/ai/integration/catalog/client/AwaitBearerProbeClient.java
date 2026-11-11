package org.qubership.integration.platform.ai.integration.catalog.client;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Probe used to prove the renewal wait runs outside the catalog client's HTTP timeout. */
@RegisterRestClient(configKey = "await-bearer-probe")
@RegisterProvider(CatalogBearerTokenFilter.class)
@AwaitUsableTurnBearer
@Retry(maxRetries = 2, retryOn = Exception.class)
public interface AwaitBearerProbeClient {

  @GET
  @Path("/probe")
  String probe();

  @GET
  @Path("/slow")
  String slow();
}
