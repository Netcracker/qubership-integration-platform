declare module "*.css";

// Augment expect's Matchers interface with @testing-library/jest-dom matchers
// (toBeInTheDocument, toHaveValue, …) so tests using expect from @jest/globals
// resolve them at the type level. The runtime side-effect (expect.extend) is
// applied via setupFilesAfterEach in jest.config.ts.
import type { TestingLibraryMatchers } from "@testing-library/jest-dom/matchers";

declare module "expect" {
  // eslint-disable-next-line @typescript-eslint/no-empty-object-type
  interface Matchers<R extends void | Promise<void>>
    extends TestingLibraryMatchers<unknown, R> {}
}
