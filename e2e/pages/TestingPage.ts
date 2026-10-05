import type { Locator, Page } from "@playwright/test";

/** The testing section inside a chain, at `/chains/:chainId/testing`. */
export class TestingPage {
  readonly page: Page;
  readonly createTestCase: Locator;
  readonly createDialog: Locator;
  readonly runTestCases: Locator;
  /** The editor's header button; it stays disabled while the draft is unchanged or invalid. */
  readonly save: Locator;

  constructor(page: Page) {
    this.page = page;
    this.createTestCase = page.getByTestId("test-cases-create");
    this.createDialog = page.getByRole("dialog", { name: "Create Test Case" });
    this.runTestCases = page.getByTestId("test-cases-run");
    this.save = page.getByTestId("test-case-save");
  }

  async goto(chainId: string, section: "test-cases" | "endpoint-mocks" | "test-case-runs"): Promise<void> {
    await this.page.goto(`/chains/${chainId}/testing/${section}`);
  }

  async gotoTestCase(chainId: string, testCaseId: string, tab: "general" | "response-validation"): Promise<void> {
    await this.page.goto(`/chains/${chainId}/testing/test-cases/${testCaseId}/${tab}`);
  }

  /** A section of the menu beside the lists: Test Cases, Endpoint Mocks, or Test Case Runs. */
  section(name: string): Locator {
    return this.page.getByRole("menuitem", { name });
  }
}
