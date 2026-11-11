package org.qubership.integration.platform.ai.compiler.capture.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;

class ToolCallFingerprintStoreTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final ToolCallFingerprintStore store = new ToolCallFingerprintStore(mapper);

  @Test
  void reconstructedStoreRetainsCreditsUntilAnExplicitReset() {
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var first = new ToolCallFingerprintStore(mapper, artifacts, sessions);
    first.consumeSoftCredit("conv", "defect-a");

    var restored = new ToolCallFingerprintStore(mapper,
        new CompilationArtifacts(blobs, mapper, Clock.systemUTC()),
        new CompilationSessions(blobs, mapper, Clock.systemUTC()));
    assertEquals(true, restored.softCreditUsed("conv", "defect-a"));
    assertEquals(false, restored.softCreditUsed("conv", "defect-b"));
    assertEquals(1, restored.softCreditsUsed("conv"));
    restored.clear("conv");
    assertEquals(0, new ToolCallFingerprintStore(mapper, artifacts, sessions).softCreditsUsed("conv"));
    assertEquals(2, artifacts.history(sessions.active("conv"), CompilationArtifacts.Kind.CAPTURE_CREDITS).size());
  }

  @Test
  void startingAnotherCompilationDoesNotInheritOldCaptureCredits() {
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var first = new ToolCallFingerprintStore(mapper, artifacts, sessions);
    first.consumeSoftCredit("conv", "defect-a");
    String before = sessions.active("conv");
    sessions.startNew("conv");
    assertEquals(false, first.softCreditUsed("conv", "defect-a"));
    assertEquals(1, artifacts.history(before, CompilationArtifacts.Kind.CAPTURE_CREDITS).size());
  }

  @Test
  void identicalNormalizedArgsShareFingerprint() {
    Map<String, Object> a = Map.of("scripts", List.of(Map.of("targetNodeId", "n1", "script", "x")));
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("script", "x");
    b.put("targetNodeId", "n1");
    Map<String, Object> bOuter = Map.of("scripts", List.of(b));

    String fpA = store.fingerprint("repairScriptBodies", "cap-a", a);
    // Different key order inside nested object still normalizes the same when built equivalently
    String fpSame =
        ToolCallFingerprints.fingerprint(
            mapper,
            "repairScriptBodies",
            "cap-a",
            Map.of("scripts", List.of(Map.of("script", "x", "targetNodeId", "n1"))));

    assertEquals(fpA, fpSame);
    assertEquals(fpA, store.fingerprint("repairScriptBodies", "cap-a", bOuter));
  }

  @Test
  void changedArgsMintNewFingerprintAndSoftCredit() {
    String first =
        store.fingerprint("repairScriptBodies", "cap", Map.of("scripts", List.of()));
    String second =
        store.fingerprint(
            "repairScriptBodies", "cap", Map.of("scripts", List.of(Map.of("targetNodeId", "n1"))));

    assertNotEquals(first, second);
    store.consumeSoftCredit("conv", first);
    assertEquals(true, store.softCreditUsed("conv", first));
    assertEquals(false, store.softCreditUsed("conv", second));
  }

  @Test
  void rationaleOnlyChangeKeepsSameFingerprint() {
    Map<String, Object> withRationaleA =
        Map.of("scripts", List.of(), "rationale", "first explanation");
    Map<String, Object> withRationaleB =
        Map.of("scripts", List.of(), "rationale", "totally different");
    Map<String, Object> withExplanation =
        Map.of("scripts", List.of(), "explanation", "noise", "comment", "also noise");

    String a = store.fingerprint("repairScriptBodies", "cap", withRationaleA);
    String b = store.fingerprint("repairScriptBodies", "cap", withRationaleB);
    String c = store.fingerprint("repairScriptBodies", "cap", withExplanation);

    assertEquals(a, b);
    assertEquals(a, c);
  }

  @Test
  void arrayOrderIsPreservedInFingerprint() {
    String ordered =
        store.fingerprint("captureGraphPatch", "cap", Map.of("ids", List.of("a", "b")));
    String reversed =
        store.fingerprint("captureGraphPatch", "cap", Map.of("ids", List.of("b", "a")));
    assertNotEquals(ordered, reversed);
  }

  @Test
  void stringValuesAreStripped() {
    String padded =
        store.fingerprint("tool", "cap", Map.of("name", "  value  "));
    String plain = store.fingerprint("tool", "cap", Map.of("name", "value"));
    assertEquals(plain, padded);
  }

  @Test
  void nullAndBlankCapabilityNormalizeTheSame() {
    Object args = Map.of("x", 1);
    assertEquals(
        store.fingerprint("tool", null, args), store.fingerprint("tool", "  ", args));
  }
}
