package org.qubership.integration.platform.ai.productpipeline.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

class KnowledgeSidecarReadinessCheckTest {

  @Test
  void publicModeRequiresDocumentationButDoesNotReadPrivatePackage() {
    var api = mock(KnowledgeSidecarApi.class);
    when(api.documentationManifest()).thenReturn(new KnowledgeSidecarApi.DocumentationManifestDto(
        1, "sha256:" + "a".repeat(64), 1, 1));
    var check = new KnowledgeSidecarReadinessCheck(api);
    check.rockySkills = mock(org.qubership.integration.platform.ai.skills.RockySkills.class);
    assertEquals(HealthCheckResponse.Status.UP, check.call().getStatus());
    org.mockito.Mockito.verify(api, org.mockito.Mockito.never()).activePackage();
  }

  @Test
  void publicModeIsNotReadyWithoutDocumentation() {
    var api = mock(KnowledgeSidecarApi.class);
    when(api.documentationManifest()).thenThrow(new IllegalStateException("Documentation is unavailable"));
    var check = new KnowledgeSidecarReadinessCheck(api);
    check.rockySkills = mock(org.qubership.integration.platform.ai.skills.RockySkills.class);
    assertEquals(HealthCheckResponse.Status.DOWN, check.call().getStatus());
  }

  static KnowledgeSidecarApi.PackageRefDto packageDto(String status) {
    return new KnowledgeSidecarApi.PackageRefDto(
        "fixture@1.0.0",
        "1.0.0",
        "1.0.0",
        "sha256:package-a",
        status,
        "sha256:certificate");
  }

  @Test
  void readyWhenProbeReturnsCertifiedPackage() {
    KnowledgeSidecarApi api = mock(KnowledgeSidecarApi.class);
    when(api.documentationManifest()).thenReturn(new KnowledgeSidecarApi.DocumentationManifestDto(
        1, "sha256:" + "a".repeat(64), 1, 1));
    when(api.ready()).thenReturn(new KnowledgeSidecarApi.HealthDto("ok", null));
    when(api.activePackage())
        .thenReturn(new KnowledgeSidecarApi.PackageResponseDto(packageDto("CERTIFIED")));

    KnowledgeSidecarReadinessCheck check = new KnowledgeSidecarReadinessCheck(api);
    check.rockySkills = mock(org.qubership.integration.platform.ai.skills.RockySkills.class);
    when(check.rockySkills.available()).thenReturn(true);
    assertEquals(HealthCheckResponse.Status.UP, check.call().getStatus());
  }

  @Test
  void downWhenSidecarStopped() {
    KnowledgeSidecarApi api = mock(KnowledgeSidecarApi.class);
    when(api.documentationManifest()).thenReturn(new KnowledgeSidecarApi.DocumentationManifestDto(
        1, "sha256:" + "a".repeat(64), 1, 1));
    when(api.ready()).thenThrow(new RuntimeException("connection refused"));
    KnowledgeSidecarReadinessCheck check = new KnowledgeSidecarReadinessCheck(api);
    check.rockySkills = mock(org.qubership.integration.platform.ai.skills.RockySkills.class);
    when(check.rockySkills.available()).thenReturn(true);
    assertEquals(HealthCheckResponse.Status.DOWN, check.call().getStatus());
  }

  @Test
  void downWhenPackageNotCertified() {
    KnowledgeSidecarApi api = mock(KnowledgeSidecarApi.class);
    when(api.documentationManifest()).thenReturn(new KnowledgeSidecarApi.DocumentationManifestDto(
        1, "sha256:" + "a".repeat(64), 1, 1));
    when(api.ready()).thenReturn(new KnowledgeSidecarApi.HealthDto("ok", null));
    when(api.activePackage())
        .thenReturn(new KnowledgeSidecarApi.PackageResponseDto(packageDto("PENDING")));
    KnowledgeSidecarReadinessCheck check = new KnowledgeSidecarReadinessCheck(api);
    check.rockySkills = mock(org.qubership.integration.platform.ai.skills.RockySkills.class);
    when(check.rockySkills.available()).thenReturn(true);
    HealthCheckResponse response = check.call();
    assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    assertFalse(response.getData().orElseThrow().isEmpty());
  }
}
