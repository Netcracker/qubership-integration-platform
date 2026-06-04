package org.qubership.integration.platform.ai.chat.planning;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiHubImportHandoffSupportTest {

  @Test
  void detectsImportHitlOptionsAndAnswers() {
    assertTrue(
        ApiHubImportHandoffSupport.optionsSuggestApiHubImport(
            List.of(
                "Import specification and continue planning",
                "Continue without binding",
                "Cancel")));
    assertTrue(
        ApiHubImportHandoffSupport.isImportSpecificationAnswer(
            "Import specification and continue planning"));
    assertTrue(
        ApiHubImportHandoffSupport.isContinueWithoutBindingAnswer("Continue without binding"));
    assertTrue(ApiHubImportHandoffSupport.isCancelAnswer("Cancel"));
  }

  @Test
  void planApprovalOptionsAreNotImportHitl() {
    assertFalse(
        ApiHubImportHandoffSupport.optionsSuggestApiHubImport(List.of("Agree", "Modify plan")));
    assertFalse(ApiHubImportHandoffSupport.isImportSpecificationAnswer("Agree"));
  }
}
