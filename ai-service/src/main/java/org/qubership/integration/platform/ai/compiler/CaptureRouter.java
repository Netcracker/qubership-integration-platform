package org.qubership.integration.platform.ai.compiler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.compiler.addon.AddonRuntimeMetadata;
import org.qubership.integration.platform.ai.compiler.pipeline.InternalPipelineSkills;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;

/** Resolves compiler skill capture routes from addon runtime metadata. */
@ApplicationScoped
public class CaptureRouter {

  private final CompilerSkillAddonRepository addonRepository;

  @Inject
  public CaptureRouter(CompilerSkillAddonRepository addonRepository) {
    this.addonRepository = addonRepository;
  }

  public CaptureRoute routeFor(String capabilityId) {
    return InternalPipelineSkills.captureRoute(capabilityId)
        .orElseGet(() -> routeFromAddon(capabilityId));
  }

  private CaptureRoute routeFromAddon(String capabilityId) {
    AddonRuntimeMetadata metadata =
        addonRepository
            .loadRuntimeMetadata(capabilityId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Compiler skill addon is missing runtime.capture.tool: " + capabilityId));
    if (metadata.captureTool() == null) {
      throw new IllegalStateException(
          "Compiler skill addon is missing runtime.capture.tool: " + capabilityId);
    }
    return new CaptureRoute(capabilityId, metadata.captureTool());
  }
}
