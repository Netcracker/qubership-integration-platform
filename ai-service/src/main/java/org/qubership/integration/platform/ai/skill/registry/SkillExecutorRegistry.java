package org.qubership.integration.platform.ai.skill.registry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.qubership.integration.platform.ai.compiler.CaptureRouter;
import org.qubership.integration.platform.ai.compiler.CompilerSkillCapabilityGate;
import org.qubership.integration.platform.ai.compiler.CompilerSkillRuntime;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutor;
import org.qubership.integration.platform.ai.skill.impl.CompilerSkillExecutor;

@ApplicationScoped
public class SkillExecutorRegistry {

  private final Map<String, SkillExecutor> bySkillId;
  private final CompilerSkillCapabilityGate capabilityGate;
  private final CompilerSkillRuntime compilerSkillRuntime;
  private final CaptureRouter captureRouter;

  @Inject
  public SkillExecutorRegistry(
      @Any Instance<SkillExecutor> executors,
      CompilerSkillCapabilityGate capabilityGate,
      CompilerSkillRuntime compilerSkillRuntime,
      CaptureRouter captureRouter) {
    this(index(executors), capabilityGate, compilerSkillRuntime, captureRouter);
  }

  /** Test-only registry with an explicit skill map and no generic fallback. */
  public static SkillExecutorRegistry forTest(Map<String, SkillExecutor> executors) {
    return forTest(executors, null, null, null);
  }

  /** Test-only registry with optional generic compiler fallback. */
  public static SkillExecutorRegistry forTest(
      Map<String, SkillExecutor> executors,
      CompilerSkillCapabilityGate capabilityGate,
      CompilerSkillRuntime compilerSkillRuntime) {
    return forTest(executors, capabilityGate, compilerSkillRuntime, null);
  }

  /** Test-only registry with optional generic compiler fallback and capture router. */
  public static SkillExecutorRegistry forTest(
      Map<String, SkillExecutor> executors,
      CompilerSkillCapabilityGate capabilityGate,
      CompilerSkillRuntime compilerSkillRuntime,
      CaptureRouter captureRouter) {
    return new SkillExecutorRegistry(
        Map.copyOf(executors), capabilityGate, compilerSkillRuntime, captureRouter);
  }

  private SkillExecutorRegistry(
      Map<String, SkillExecutor> bySkillId,
      CompilerSkillCapabilityGate capabilityGate,
      CompilerSkillRuntime compilerSkillRuntime,
      CaptureRouter captureRouter) {
    this.bySkillId = bySkillId;
    this.capabilityGate = capabilityGate;
    this.compilerSkillRuntime = compilerSkillRuntime;
    this.captureRouter = captureRouter;
  }

  public Optional<SkillExecutor> find(String skillId) {
    return Optional.ofNullable(bySkillId.get(skillId));
  }

  public SkillExecutor require(String skillId) {
    SkillExecutor dedicated = bySkillId.get(skillId);
    if (dedicated != null) {
      return dedicated;
    }
    if (capabilityGate != null
        && compilerSkillRuntime != null
        && capabilityGate.allowsGenericExecution(skillId)) {
      return captureRouter != null
          ? new CompilerSkillExecutor(compilerSkillRuntime, capabilityGate, captureRouter, skillId)
          : new CompilerSkillExecutor(compilerSkillRuntime, capabilityGate, skillId);
    }
    String reason =
        capabilityGate != null
            ? capabilityGate.rejectReason(skillId)
            : "No SkillExecutor registered for: " + skillId;
    throw new IllegalStateException(
        dedicatedExecutorMissingMessage(skillId, reason));
  }

  private static String dedicatedExecutorMissingMessage(String skillId, String reason) {
    return "No SkillExecutor registered for: " + skillId + ". " + reason;
  }

  private static Map<String, SkillExecutor> index(Instance<SkillExecutor> executors) {
    Map<String, SkillExecutor> map = new HashMap<>();
    for (SkillExecutor executor : executors) {
      String id = resolveSkillId(executor);
      if (map.put(id, executor) != null) {
        throw new IllegalStateException("Duplicate SkillExecutor registration for: " + id);
      }
    }
    if (map.isEmpty()) {
      throw new IllegalStateException("No SkillExecutor beans discovered — check CDI @Any wiring");
    }
    return map;
  }

  private static String resolveSkillId(SkillExecutor executor) {
    Class<?> type = executor.getClass();
    SkillId annotation = type.getAnnotation(SkillId.class);
    if (annotation == null && type.getSuperclass() != null) {
      annotation = type.getSuperclass().getAnnotation(SkillId.class);
    }
    if (annotation != null) {
      return annotation.value();
    }
    return executor.skillId();
  }
}
