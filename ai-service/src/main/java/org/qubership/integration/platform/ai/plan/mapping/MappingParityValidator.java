package org.qubership.integration.platform.ai.plan.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.AttributeReference;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.Constant;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ConstantReference;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ElementReference;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.GivenValue;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.MappingAction;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.MessageSchema;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;

/** Rejects mapper-2 captures and script coverage that drift from approved mapping rules. */
public final class MappingParityValidator {

  private static final String PREFIX = "Mapping parity:";
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  private MappingParityValidator() {}

  public static void requireMapper2(
      MappingEnvelope envelope, MappingIntent intent, MappingDescriptionDocument captured) {
    Objects.requireNonNull(envelope, "envelope");
    Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(captured, "captured");
    rejectUnresolved(intent);
    String capturedDigest = digest(captured.source(), captured.target());
    if (!envelope.digest().equals(capturedDigest)) {
      throw parity("frozen source/target schema does not match the approved envelope");
    }
    List<MappingIntentRule> approved = approvedRules(intent);
    List<CapturedRule> capturedRules = capturedRules(captured, envelope.idToPath());
    if (!sameMultiset(approvedTargetPaths(approved), capturedTargetPaths(capturedRules))) {
      throw parity("captured mapper actions do not match approved target paths");
    }
    for (MappingIntentRule rule : approved) {
      if (!hasMatchingCapturedRule(rule, capturedRules, captured)) {
        throw parity(
            "captured mapper action for target "
                + rule.targetPath()
                + " does not match approved source");
      }
    }
  }

  public static void requireScriptCoverage(
      MappingIntent intent, List<String> implementedTargetPaths) {
    requireScriptCoverage(intent, implementedTargetPaths, MappingContract.unknown());
  }

  public static void requireScriptCoverage(
      MappingIntent intent, List<String> implementedTargetPaths, MappingContract targetContract) {
    Objects.requireNonNull(intent, "intent");
    if (implementedTargetPaths == null) {
      throw parity("script coverage list is required");
    }
    rejectUnresolved(intent);
    MappingContract target = targetContract == null ? MappingContract.unknown() : targetContract;
    List<String> approved =
        new ArrayList<>(
            target.hopBodyFieldsCoveredBy(approvedTargetPaths(intent)));
    List<String> implemented =
        new ArrayList<>(MappingContract.uniqueCanonicalPaths(implementedTargetPaths));
    List<String> missing = new ArrayList<>();
    for (String approvedPath : approved) {
      if (!coveredBy(approvedPath, implemented)) {
        missing.add(approvedPath);
      }
    }
    List<String> unexpected = new ArrayList<>();
    for (String implementedPath : implemented) {
      if (!coversAnyApproved(implementedPath, approved)) {
        unexpected.add(implementedPath);
      }
    }
    if (!missing.isEmpty() || !unexpected.isEmpty()) {
      Collections.sort(missing);
      Collections.sort(unexpected);
      Collections.sort(implemented);
      throw parity(
          "script coverage does not match approved target paths. missing="
              + missing
              + " unexpected="
              + unexpected
              + " implemented="
              + implemented);
    }
  }

  private static boolean coveredBy(String approvedPath, List<String> implemented) {
    for (String implementedPath : implemented) {
      if (MappingContract.pathTouches(approvedPath, implementedPath)) {
        return true;
      }
    }
    return false;
  }

  private static boolean coversAnyApproved(String implementedPath, List<String> approved) {
    for (String approvedPath : approved) {
      if (MappingContract.pathTouches(implementedPath, approvedPath)) {
        return true;
      }
    }
    return false;
  }

  private static void rejectUnresolved(MappingIntent intent) {
    for (MappingIntentRule rule : intent.rules()) {
      if (rule.status() == MappingRuleStatus.UNRESOLVED) {
        throw parity("approved rules must not contain UNRESOLVED status");
      }
    }
  }

  private static List<MappingIntentRule> approvedRules(MappingIntent intent) {
    List<MappingIntentRule> approved = new ArrayList<>();
    for (MappingIntentRule rule : intent.rules()) {
      if (rule.targetPath() == null || rule.targetPath().isBlank()) {
        continue;
      }
      approved.add(rule);
    }
    return approved;
  }

  private static List<String> approvedTargetPaths(MappingIntent intent) {
    return approvedTargetPaths(approvedRules(intent));
  }

  private static List<String> approvedTargetPaths(List<MappingIntentRule> rules) {
    List<String> paths = new ArrayList<>();
    for (MappingIntentRule rule : rules) {
      paths.add(rule.targetPath());
    }
    return paths;
  }

  private static List<String> capturedTargetPaths(List<CapturedRule> rules) {
    List<String> paths = new ArrayList<>();
    for (CapturedRule rule : rules) {
      paths.add(rule.targetPath());
    }
    return paths;
  }

  private static boolean sameMultiset(List<String> left, List<String> right) {
    List<String> sortedLeft = new ArrayList<>(left);
    List<String> sortedRight = new ArrayList<>(right);
    Collections.sort(sortedLeft);
    Collections.sort(sortedRight);
    return sortedLeft.equals(sortedRight);
  }

  private static List<CapturedRule> capturedRules(
      MappingDescriptionDocument captured, Map<String, String> idToPath) {
    List<CapturedRule> rules = new ArrayList<>();
    for (MappingAction action : captured.actions()) {
      String targetPath = resolveAttributePath(action.target(), idToPath);
      String sourcePath = resolveSourcePath(action, captured, idToPath);
      rules.add(new CapturedRule(sourcePath, targetPath));
    }
    return rules;
  }

  private static boolean hasMatchingCapturedRule(
      MappingIntentRule approved, List<CapturedRule> captured, MappingDescriptionDocument document) {
    for (CapturedRule rule : captured) {
      if (!approved.targetPath().equals(rule.targetPath())) {
        continue;
      }
      if (MappingMechanismSelector.isConstantLiteral(approved.sourcePath())) {
        if (constantSourceMatches(approved.sourcePath(), rule.sourcePath())) {
          return true;
        }
        continue;
      }
      if (approved.sourcePath().equals(rule.sourcePath())) {
        return true;
      }
      if (rule.sourcePath().isBlank() && actionUsesDeclaredConstant(approved, document)) {
        return true;
      }
    }
    return false;
  }

  private static boolean actionUsesDeclaredConstant(
      MappingIntentRule approved, MappingDescriptionDocument document) {
    if (!MappingMechanismSelector.isConstantLiteral(approved.sourcePath())) {
      return false;
    }
    String expected = MappingMechanismSelector.constantValue(approved.sourcePath());
    for (Constant constant : document.constants()) {
      if (constant.valueSupplier() instanceof GivenValue given
          && expected.equals(given.value())) {
        return true;
      }
    }
    return false;
  }

  private static boolean constantSourceMatches(String approvedSource, String capturedSource) {
    if (!MappingMechanismSelector.isConstantLiteral(approvedSource)) {
      return false;
    }
    if (MappingMechanismSelector.isConstantLiteral(capturedSource)) {
      return approvedSource.equals(capturedSource);
    }
    return MappingMechanismSelector.constantValue(approvedSource).equals(capturedSource);
  }

  private static String resolveSourcePath(
      MappingAction action, MappingDescriptionDocument captured, Map<String, String> idToPath) {
    if (action.sources() == null || action.sources().isEmpty()) {
      return "";
    }
    ElementReference source = action.sources().getFirst();
    if (source instanceof AttributeReference attributeReference) {
      return resolveAttributePath(attributeReference, idToPath);
    }
    if (source instanceof ConstantReference constantReference) {
      return resolveConstantSource(constantReference, captured);
    }
    return "";
  }

  private static String resolveConstantSource(
      ConstantReference reference, MappingDescriptionDocument captured) {
    for (Constant constant : captured.constants()) {
      if (!constant.id().equals(reference.constantId())) {
        continue;
      }
      if (constant.valueSupplier() instanceof GivenValue given) {
        return "\"" + given.value() + "\"";
      }
    }
    return "";
  }

  private static String resolveAttributePath(AttributeReference reference, Map<String, String> idToPath) {
    if (reference == null || reference.path() == null || reference.path().isEmpty()) {
      return "";
    }
    String lastId = reference.path().getLast();
    String resolved = idToPath.get(lastId);
    if (resolved != null) {
      return resolved;
    }
    for (String id : reference.path()) {
      resolved = idToPath.get(id);
      if (resolved != null) {
        return resolved;
      }
    }
    return "";
  }

  private static String digest(MessageSchema source, MessageSchema target) {
    Map<String, MessageSchema> payload = new TreeMap<>();
    payload.put("source", source);
    payload.put("target", target);
    try {
      byte[] json = JSON.writeValueAsBytes(payload);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot serialize mapping schema for digest", e);
    }
  }

  private static IllegalArgumentException parity(String detail) {
    return new IllegalArgumentException(PREFIX + " " + detail);
  }

  private record CapturedRule(String sourcePath, String targetPath) {}
}
