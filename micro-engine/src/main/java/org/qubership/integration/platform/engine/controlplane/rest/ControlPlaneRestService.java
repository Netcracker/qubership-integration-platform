package org.qubership.integration.platform.engine.controlplane.rest;

import com.netcracker.cloud.quarkus.security.auth.rest.M2MFilter;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.qubership.integration.platform.engine.controlplane.rest.model.v1.get.RouteConfigurationResponse;
import org.qubership.integration.platform.engine.controlplane.rest.model.v3.post.RouteConfigurationObjectV3;
import org.qubership.integration.platform.engine.controlplane.rest.model.v3.post.tlsdef.TLSDefinitionObjectV3;

import java.util.List;

@RegisterRestClient(configKey = "control-plane")
@RegisterProvider(ControlPlaneResponseExceptionMapper.class)
@RegisterProvider(M2MFilter.class)
public interface ControlPlaneRestService {
    @GET
    @Path("/api/v1/routes/route-configs")
    List<RouteConfigurationResponse> getRouteConfiguration();

    @DELETE
    @Path("/api/v2/control-plane/routes/uuid/{id}")
    String deleteRoute(@PathParam("id") String id);

    @POST
    @Path("/api/v3/config")
    String postRouteConfiguration(RouteConfigurationObjectV3 configuration);

    @POST
    @Path("/api/v3/config")
    String postTlsConfiguration(TLSDefinitionObjectV3 configuration);
}
