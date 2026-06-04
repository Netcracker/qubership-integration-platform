import {
  API_HUB_IMPORT_FOLLOW_UP_MESSAGE,
  isApiHubImportSpecificationAnswer,
  optionsSuggestApiHubImport,
  shouldAutoFollowUpImportSpecification,
} from "../../../src/components/ai/apiHubImportHitl";

describe("apiHubImportHitl", () => {
  it("detects ApiHub import HITL options", () => {
    expect(
      optionsSuggestApiHubImport([
        "Import specification and continue planning",
        "Continue without binding",
        "Cancel",
      ]),
    ).toBe(true);
    expect(optionsSuggestApiHubImport(["Agree", "Modify plan"])).toBe(false);
  });

  it("detects import specification answers", () => {
    expect(isApiHubImportSpecificationAnswer(API_HUB_IMPORT_FOLLOW_UP_MESSAGE)).toBe(true);
    expect(isApiHubImportSpecificationAnswer("Continue without binding")).toBe(false);
  });

  it("requires both import option set and import answer for auto follow-up", () => {
    expect(
      shouldAutoFollowUpImportSpecification(API_HUB_IMPORT_FOLLOW_UP_MESSAGE, [
        "Import specification and continue planning",
        "Continue without binding",
      ]),
    ).toBe(true);
    expect(
      shouldAutoFollowUpImportSpecification("Continue without binding", [
        "Import specification and continue planning",
      ]),
    ).toBe(false);
  });
});
