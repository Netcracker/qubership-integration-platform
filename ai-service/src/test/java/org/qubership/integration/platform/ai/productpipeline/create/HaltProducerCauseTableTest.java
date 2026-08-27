package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;

class HaltProducerCauseTableTest {

  @Test
  void everyProducerListsAtLeastOneCauseAndEveryCauseIsKnown() {
    Set<RecoveryCauseCode> allCodes = EnumSet.allOf(RecoveryCauseCode.class);
    Set<RecoveryCauseCode> emitted = EnumSet.noneOf(RecoveryCauseCode.class);
    for (HaltProducer producer : HaltProducer.values()) {
      Set<RecoveryCauseCode> causes = HaltProducerCauseTable.causesOf(producer);
      assertFalse(causes.isEmpty(), producer + " must emit at least one cause");
      assertTrue(allCodes.containsAll(causes), producer + " listed an unknown cause: " + causes);
      emitted.addAll(causes);
    }
    assertTrue(emitted.contains(RecoveryCauseCode.CATALOG_RESOLUTION));
    assertTrue(emitted.contains(RecoveryCauseCode.INTERNAL));
  }

  @Test
  void ownerCategoryIsDefinedForEveryCauseCode() {
    for (RecoveryCauseCode code : RecoveryCauseCode.values()) {
      HaltProducerCauseTable.ownerCategory(code);
    }
  }

  @Test
  void instructionNamesTheChangeFromTheTypedCause() {
    String sentence =
        HaltProducerCauseTable.instruction(
            RecoveryCause.of(RecoveryCauseCode.SECURITY_POLICY),
            OwnerDiagnosis.of("The brief omitted the scheduler.", "analysis"),
            List.of(new OwnerCandidate("analysis", "requirement-brief")));

    assertEquals("State the access policy in the requirements.", sentence);
  }

  @Test
  void instructionForAnEmptyInternalFailureOffersStopWithReport() {
    assertEquals(
        "Stop with a report. This run has no producer to reopen.",
        HaltProducerCauseTable.instruction(
            RecoveryCause.of(RecoveryCauseCode.INTERNAL), OwnerDiagnosis.none(""), List.of()));
  }

  @Test
  void instructionForAnAmbiguousOwnerAsksWhichArtifactToRevise() {
    assertEquals(
        "Pick which artifact to revise.",
        HaltProducerCauseTable.instruction(
            RecoveryCause.of(RecoveryCauseCode.VALIDATION_BLOCKER),
            OwnerDiagnosis.ask("Either could be wrong."),
            List.of(
                new OwnerCandidate("planning", "implementation-plan"),
                new OwnerCandidate("analysis", "requirement-brief"))));
  }
}
