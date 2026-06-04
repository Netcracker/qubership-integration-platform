package org.qubership.integration.platform.ai.chat.planning;

import java.util.List;
import java.util.Locale;

/** Detects ApiHub import HITL options/answers and the tool result that stops CREATE_CHAIN_PLAN. */
public final class ApiHubImportHandoffSupport {

  public static final String IMPORT_HANDOFF_STOP_RESULT =
      """
      User confirmed ApiHub specification import. STOP this CREATE_CHAIN_PLAN turn immediately.
      Do not call describeElementPatchSchema, importApiHubSpecificationToCatalog, \
      importApiHubSpecToSystem, or emit chain-plan-json.
      Do not write a numbered implementation plan or manual Next Steps.
      Tell the user briefly that catalog import runs next in IMPORT_SPECIFICATION, then end \
      your assistant reply.
      The next user message routes to IMPORT_SPECIFICATION automatically.""";

  private ApiHubImportHandoffSupport() {}

  public static boolean optionsSuggestApiHubImport(List<String> options) {
    if (options == null || options.isEmpty()) {
      return false;
    }
    for (String option : options) {
      if (option == null || option.isBlank()) {
        continue;
      }
      String lower = option.trim().toLowerCase(Locale.ROOT);
      if (lower.contains("import specification") || lower.contains("import an apihub")) {
        return true;
      }
    }
    return false;
  }

  public static boolean isImportSpecificationAnswer(String answer) {
    if (answer == null || answer.isBlank()) {
      return false;
    }
    String lower = answer.trim().toLowerCase(Locale.ROOT);
    return lower.contains("import specification")
        || lower.contains("import an apihub")
        || lower.startsWith("import specification and continue");
  }

  public static boolean isContinueWithoutBindingAnswer(String answer) {
    if (answer == null || answer.isBlank()) {
      return false;
    }
    String lower = answer.trim().toLowerCase(Locale.ROOT);
    return lower.contains("continue without binding") || lower.contains("without binding");
  }

  public static boolean isCancelAnswer(String answer) {
    if (answer == null || answer.isBlank()) {
      return false;
    }
    return "cancel".equalsIgnoreCase(answer.trim());
  }
}
