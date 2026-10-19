package org.qubership.integration.platform.ai.compiler.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PipelineCompatibilityReportAugmenterTest {

  @Test
  void mergesPackShippedProfileWhenActivationAllowed() {
    PipelineCompatibilityReport base =
        new PipelineCompatibilityReport(
            1,
            "prev",
            "candidate",
            PipelineChangeClass.CONTENT_ONLY,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("create-chain@1", "create-chain@2"),
            List.of(),
            true,
            List.of());

    PipelineCompatibilityReport augmented =
        PipelineCompatibilityReportAugmenter.augmentWithPackShippedProfiles(
            base, List.of("create-chain@2", "create-chain@3"));

    assertTrue(augmented.compatibleProfileVersions().contains("create-chain@3"));
    assertEquals(
        List.of("create-chain@1", "create-chain@2", "create-chain@3"),
        augmented.compatibleProfileVersions());
  }

  @Test
  void leavesReportUnchangedWhenActivationDenied() {
    PipelineCompatibilityReport base =
        new PipelineCompatibilityReport(
            1,
            "prev",
            "candidate",
            PipelineChangeClass.BREAKING,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("create-chain@1"),
            List.of(),
            false,
            List.of("blocked"));

    PipelineCompatibilityReport augmented =
        PipelineCompatibilityReportAugmenter.augmentWithPackShippedProfiles(
            base, List.of("create-chain@3"));

    assertEquals(base, augmented);
    assertFalse(augmented.compatibleProfileVersions().contains("create-chain@3"));
  }
}
