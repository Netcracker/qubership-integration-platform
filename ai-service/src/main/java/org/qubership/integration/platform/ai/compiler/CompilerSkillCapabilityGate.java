package org.qubership.integration.platform.ai.compiler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityDescriptor;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityRegistry;
import org.qubership.integration.platform.ai.qipknowledge.skill.CipKnowledgeCapabilityPhase;

/**
 * Decides whether a pack skill can run through the generic {@link
 * org.qubership.integration.platform.ai.skill.impl.CompilerSkillExecutor}.
 */
@ApplicationScoped
public class CompilerSkillCapabilityGate {

  private static final Set<CipKnowledgeCapabilityPhase> GENERIC_EXECUTOR_PHASES =
      Set.of(
          CipKnowledgeCapabilityPhase.DISCOVERY,
          CipKnowledgeCapabilityPhase.GENERATOR,
          CipKnowledgeCapabilityPhase.GRAPH_CONSTRUCTION,
          CipKnowledgeCapabilityPhase.VALIDATOR);

  private static final Set<String> RUNTIME_GRAPH_CONSTRUCTION_SKILLS =
      Set.of("cip-chain-generator");

  private final CipKnowledgePackRepository repository;
  private final CompilerSkillRuntimeEligibility runtimeEligibility;

  @Inject
  public CompilerSkillCapabilityGate(CipKnowledgePackRepository repository) {
    this(repository, new CompilerSkillRuntimeEligibility(repository));
  }

  CompilerSkillCapabilityGate(
      CipKnowledgePackRepository repository, CompilerSkillRuntimeEligibility runtimeEligibility) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.runtimeEligibility = Objects.requireNonNull(runtimeEligibility, "runtimeEligibility");
  }

  public boolean allowsGenericExecution(String skillId) {
    return phaseFor(skillId).isPresent();
  }

  public Optional<CipKnowledgeCapabilityPhase> phaseFor(String skillId) {
    if (!runtimeEligibility.allowsRuntimeAccess(skillId)) {
      return Optional.empty();
    }
    return findCapability(skillId)
        .filter(CapabilityDescriptor::supported)
        .map(CapabilityDescriptor::phase)
        .filter(phase -> allowsRuntimePhase(skillId, phase));
  }

  public String rejectReason(String skillId) {
    Objects.requireNonNull(skillId, "skillId");
    String key = skillId.trim();
    if (key.isEmpty()) {
      return "skillId is required";
    }

    Optional<String> catalogExclusion = runtimeEligibility.exclusionReason(key);
    if (catalogExclusion.isPresent()) {
      return catalogExclusion.get();
    }

    CapabilityRegistry registry = repository.loadCapabilityRegistry();
    Optional<CapabilityDescriptor> capability = findCapability(registry, key);
    if (capability.isEmpty()) {
      return "No compiler skill registered in the active pack: " + key;
    }

    CapabilityDescriptor descriptor = capability.get();
    if (!descriptor.supported()) {
      return descriptor.reasonIfUnsupported() != null
          ? descriptor.reasonIfUnsupported()
          : "Compiler skill is not supported: " + key;
    }
    if (!allowsRuntimePhase(key, descriptor.phase())) {
      return "Generic compiler executor does not support phase "
          + descriptor.phase()
          + ": "
          + key;
    }
    return "Compiler skill cannot use generic executor: " + key;
  }

  private boolean allowsRuntimePhase(String skillId, CipKnowledgeCapabilityPhase phase) {
    if (!GENERIC_EXECUTOR_PHASES.contains(phase)) {
      return false;
    }
    if (phase == CipKnowledgeCapabilityPhase.GRAPH_CONSTRUCTION) {
      return RUNTIME_GRAPH_CONSTRUCTION_SKILLS.contains(skillId.trim())
          || repository.loadRuntimePromotedSkillIds().contains(skillId.trim());
    }
    if (phase == CipKnowledgeCapabilityPhase.DISCOVERY) {
      return repository.loadRuntimePromotedSkillIds().contains(skillId.trim());
    }
    if (phase == CipKnowledgeCapabilityPhase.VALIDATOR) {
      return repository.loadRuntimePromotedSkillIds().contains(skillId.trim());
    }
    return true;
  }

  private Optional<CapabilityDescriptor> findCapability(String skillId) {
    Objects.requireNonNull(skillId, "skillId");
    String key = skillId.trim();
    if (key.isEmpty()) {
      return Optional.empty();
    }
    return findCapability(repository.loadCapabilityRegistry(), key);
  }

  private static Optional<CapabilityDescriptor> findCapability(
      CapabilityRegistry registry, String skillId) {
    return registry.capabilities().stream()
        .filter(
            capability ->
                skillId.equals(capability.id()) || skillId.equals(capability.sourceSkillId()))
        .findFirst();
  }
}
