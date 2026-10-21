package org.qubership.integration.platform.ai.compiler.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementRole;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementSkeleton;
import org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;

class CaptureSessionProtocolTest {

  private static final String CONVERSATION_ID = "protocol-conv";
  private static final String CAPABILITY_A = "capability-a";
  private static final String CAPABILITY_B = "capability-b";

  private CaptureSession session;

  @BeforeEach
  void setUp() {
    session = new CaptureSession();
  }

  @Test
  void firstAcceptReturnsSuccessAndGetReturnsValue() {
    CaptureKey key = CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, CONVERSATION_ID);
    SelectedPattern value = pattern("GP-01");

    String result = session.accept(key, value, "success finish this turn", "duplicate finish this turn");

    assertEquals("success finish this turn", result);
    assertEquals(Optional.of(value), session.get(key, SelectedPattern.class));
    assertTrue(session.isPresent(key));
  }

  @Test
  void secondAcceptThrowsCaptureValidationExceptionWithAlreadyCapturedAndFinishTurn() {
    CaptureKey key = CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, CONVERSATION_ID);
    session.accept(key, pattern("GP-01"), "success finish this turn", "already captured finish this turn");

    CaptureValidationException thrown =
        assertThrows(
            CaptureValidationException.class,
            () ->
                session.accept(
                    key, pattern("GP-02"), "success finish this turn", "already captured finish this turn"));

    assertTrue(thrown.getMessage().toLowerCase().contains("already captured"));
    assertTrue(thrown.getMessage().contains("finish this turn"));
  }

  @Test
  void valueAfterDuplicateIsStillFirstValue() {
    CaptureKey key = CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, CONVERSATION_ID);
    SelectedPattern first = pattern("GP-01");
    session.accept(key, first, "success finish this turn", "already captured finish this turn");

    assertThrows(
        CaptureValidationException.class,
        () ->
            session.accept(
                key, pattern("GP-02"), "success finish this turn", "already captured finish this turn"));

    assertEquals(Optional.of(first), session.get(key, SelectedPattern.class));
  }

  @Test
  void acceptAllIsAtomicWhenAnyKeyAlreadyExists() {
    CaptureKey skeletonKey =
        CaptureKey.conversation(CaptureSlot.ELEMENT_SKELETON, CONVERSATION_ID);
    CaptureKey patternKey =
        CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, CONVERSATION_ID);
    ElementSkeleton existingSkeleton = skeleton();
    session.accept(skeletonKey, existingSkeleton, "ok", "dup");

    assertThrows(
        CaptureValidationException.class,
        () ->
            session.acceptAll(
                Map.of(patternKey, pattern("GP-01"), skeletonKey, skeleton()),
                "ok",
                "already captured finish this turn"));

    assertFalse(session.isPresent(patternKey));
    assertEquals(Optional.of(existingSkeleton), session.get(skeletonKey, ElementSkeleton.class));
  }

  @Test
  void concurrentWritersProduceExactlyOneSuccessAndOneCaptureValidationException()
      throws Exception {
    CaptureKey key = CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, CONVERSATION_ID);
    SelectedPattern first = pattern("GP-01");
    SelectedPattern second = pattern("GP-02");
    CyclicBarrier barrier = new CyclicBarrier(2);
    List<Object> outcomes = Collections.synchronizedList(new ArrayList<>());

    Thread t1 =
        new Thread(
            () -> {
              try {
                barrier.await(5, TimeUnit.SECONDS);
                outcomes.add(session.accept(key, first, "ok-1", "dup"));
              } catch (Exception e) {
                outcomes.add(e);
              }
            });
    Thread t2 =
        new Thread(
            () -> {
              try {
                barrier.await(5, TimeUnit.SECONDS);
                outcomes.add(session.accept(key, second, "ok-2", "dup"));
              } catch (Exception e) {
                outcomes.add(e);
              }
            });

    t1.start();
    t2.start();
    t1.join(5_000);
    t2.join(5_000);

    long successes =
        outcomes.stream().filter(o -> o instanceof String s && s.startsWith("ok-")).count();
    long duplicates =
        outcomes.stream().filter(CaptureValidationException.class::isInstance).count();
    assertEquals(1, successes);
    assertEquals(1, duplicates);

    SelectedPattern stored = session.get(key, SelectedPattern.class).orElseThrow();
    assertTrue(stored.equals(first) || stored.equals(second));
  }

  @Test
  void differentCapabilityIdsAreIndependent() {
    CaptureKey keyA =
        CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, CAPABILITY_A);
    CaptureKey keyB =
        CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, CAPABILITY_B);
    GraphPatch patchA = emptyPatch("a");
    GraphPatch patchB = emptyPatch("b");

    session.accept(keyA, patchA, "ok-a", "dup-a");
    session.accept(keyB, patchB, "ok-b", "dup-b");

    assertEquals(Optional.of(patchA), session.get(keyA, GraphPatch.class));
    assertEquals(Optional.of(patchB), session.get(keyB, GraphPatch.class));
  }

  @Test
  void factoriesRejectMismatchedScope() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CaptureKey.conversation(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CaptureKey.capability(
                CaptureSlot.SELECTED_PATTERN, CONVERSATION_ID, CAPABILITY_A));
  }

  @Test
  void blankIdentifiersFailBeforeMapAccess() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, " "));
    assertThrows(
        IllegalArgumentException.class,
        () -> CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, " "));
    assertThrows(
        IllegalArgumentException.class,
        () -> CaptureKey.capability(CaptureSlot.GRAPH_PATCH, " ", CAPABILITY_A));
  }

  @Test
  void acceptAndGetRejectMismatchedValueType() {
    CaptureKey key = CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, CONVERSATION_ID);

    assertThrows(
        IllegalArgumentException.class,
        () -> session.accept(key, "not-a-pattern", "ok", "dup"));

    session.accept(key, pattern("GP-01"), "ok", "dup");
    assertThrows(IllegalArgumentException.class, () -> session.get(key, String.class));
  }

  @Test
  void getRejectsBroadTypeBeforeReadingEmptySlot() {
    CaptureKey key = CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, CONVERSATION_ID);

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> session.get(key, Object.class));

    assertTrue(thrown.getMessage().contains("does not match slot value type"));
    assertFalse(session.isPresent(key));
  }

  @Test
  void clearIfSameRemovesWinningValueButNotReplacement() {
    CaptureKey key = CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, CONVERSATION_ID);
    SelectedPattern first = pattern("GP-01");
    SelectedPattern replacement = pattern("GP-02");

    session.accept(key, first, "ok", "dup");
    assertTrue(session.clearIfSame(key, first));
    assertFalse(session.isPresent(key));

    session.accept(key, replacement, "ok", "dup");
    assertFalse(session.clearIfSame(key, first));
    assertEquals(Optional.of(replacement), session.get(key, SelectedPattern.class));
  }

  @Test
  void adapterStyleCatchRethrowsCaptureValidationException() {
    CaptureKey key = CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, CONVERSATION_ID);
    session.accept(key, pattern("GP-01"), "ok", "already captured finish this turn");

    AtomicReference<String> softError = new AtomicReference<>();
    CaptureValidationException thrown =
        assertThrows(
            CaptureValidationException.class,
            () -> softError.set(adapterCatchStyle(key, pattern("GP-02"))));

    assertTrue(thrown.getMessage().contains("already captured"));
    assertTrue(thrown.getMessage().contains("finish this turn"));
    assertEquals(null, softError.get());
  }

  private String adapterCatchStyle(CaptureKey key, SelectedPattern value) {
    try {
      return session.accept(key, value, "ok", "already captured finish this turn");
    } catch (CaptureValidationException e) {
      throw e;
    } catch (Exception e) {
      return "Error: " + e.getMessage();
    }
  }

  private static SelectedPattern pattern(String patternId) {
    return new SelectedPattern(patternId, "name", "reason", null, List.of(), "summary");
  }

  private static GraphPatch emptyPatch(String patchId) {
    return new GraphPatch(
        patchId, CAPABILITY_A, List.of(), List.of(), List.of(), List.of(), List.of(), "rationale");
  }

  private static ElementSkeleton skeleton() {
    return new ElementSkeleton(
        1,
        "GP-01",
        List.of("entry"),
        List.of(new ElementRole("entry", "http-trigger", null, 1, 1)),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }
}
