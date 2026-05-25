package org.qubership.integration.platform.ai.chat.guardrail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GuardrailClassificationTextTest {

  @Test
  void boilerplateDetectsUiPhrase() {
    assertTrue(GuardrailClassificationText.isAttachmentBoilerplate("See attached files."));
    assertTrue(GuardrailClassificationText.isAttachmentBoilerplate("  SEE ATTACHED FILES  "));
    assertFalse(
        GuardrailClassificationText.isAttachmentBoilerplate("Create a chain for Pet store"));
  }

  @Test
  void blankMessageIsBoilerplateForAttachmentOnlyTurn() {
    assertTrue(GuardrailClassificationText.isAttachmentBoilerplate(""));
    assertTrue(GuardrailClassificationText.isAttachmentBoilerplate("   "));
  }

  @Test
  void toClassifierMessageOmitsIdsBody() {
    String idsSnippet = "# Integration Design Specification (IDS)";
    String out =
        GuardrailClassificationText.toClassifierMessage(
            "See attached files.",
            AttachmentManifest.fromObjectKeys(
                List.of(
                    "bf1ecfb5-d2df-4fc9-bf5a-85aafc4bddc2/60327ccd-e5f8-4032-a43b-b897568c2b64.md")),
            true);
    assertTrue(out.contains("60327ccd-e5f8-4032-a43b-b897568c2b64.md"));
    assertTrue(out.contains("content omitted"));
    assertFalse(out.contains(idsSnippet));
  }

  @Test
  void manifestExtractsBasenameFromKey() {
    AttachmentManifest m = AttachmentManifest.fromObjectKeys(List.of("conv-id/design.md"));
    assertEquals(List.of("design.md"), m.fileNames());
  }
}
