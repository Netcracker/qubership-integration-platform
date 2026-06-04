/** Mirrors ai-service ApiHubImportHandoffSupport for UI follow-up after import HITL. */

export const API_HUB_IMPORT_FOLLOW_UP_MESSAGE = "Import specification and continue planning";

export const IMPORT_SPECIFICATION_SCENARIO_HINT = "IMPORT_SPECIFICATION";

export function optionsSuggestApiHubImport(options: string[] | undefined): boolean {
  if (!options?.length) {
    return false;
  }
  return options.some((option) => {
    const lower = option.trim().toLowerCase();
    return lower.includes("import specification") || lower.includes("import an apihub");
  });
}

export function isApiHubImportSpecificationAnswer(answer: string): boolean {
  const lower = answer.trim().toLowerCase();
  if (!lower) {
    return false;
  }
  return (
    lower.includes("import specification") ||
    lower.includes("import an apihub") ||
    lower.startsWith("import specification and continue")
  );
}

export function shouldAutoFollowUpImportSpecification(
  answer: string,
  options: string[] | undefined,
): boolean {
  return isApiHubImportSpecificationAnswer(answer) && optionsSuggestApiHubImport(options);
}
