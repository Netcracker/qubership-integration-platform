package org.qubership.integration.platform.ai.productpipeline.knowledge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/** Fails readiness until the sidecar reports ready and a CERTIFIED active package. */
@Readiness
@ApplicationScoped
public class KnowledgeSidecarReadinessCheck implements HealthCheck {

  private final KnowledgeSidecarApi api;

  @Inject
  public KnowledgeSidecarReadinessCheck(@RestClient KnowledgeSidecarApi api) {
    this.api = api;
  }

  @Override
  public HealthCheckResponse call() {
    try {
      KnowledgeSidecarApi.HealthDto ready = api.ready();
      if (ready == null || !"ok".equalsIgnoreCase(ready.status())) {
        return HealthCheckResponse.named("knowledge-sidecar")
            .down()
            .withData("reason", ready == null ? "empty ready response" : ready.detail())
            .build();
      }
      KnowledgeSidecarApi.PackageResponseDto packageResponse = api.activePackage();
      if (packageResponse == null || packageResponse.packageRef() == null) {
        return HealthCheckResponse.named("knowledge-sidecar")
            .down()
            .withData("reason", "empty package response")
            .build();
      }
      if (!"CERTIFIED".equals(packageResponse.packageRef().certificationStatus())) {
        return HealthCheckResponse.named("knowledge-sidecar")
            .down()
            .withData("reason", "active package is not CERTIFIED")
            .build();
      }
      SidecarKnowledgeClient.toPackageRef(packageResponse.packageRef());
      return HealthCheckResponse.named("knowledge-sidecar").up().build();
    } catch (RuntimeException e) {
      return HealthCheckResponse.named("knowledge-sidecar")
          .down()
          .withData("reason", e.getMessage())
          .build();
    }
  }
}
