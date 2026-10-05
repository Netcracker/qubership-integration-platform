/**
 * @jest-environment jsdom
 */
import { render, screen } from "@testing-library/react";
import { describe, it, expect, jest } from "@jest/globals";

jest.mock("monaco-editor", () => ({ editor: {} }));

jest.mock("../../../src/hooks/useMonacoTheme", () => ({
  useMonacoTheme: () => "vs-dark",
  applyVSCodeThemeToMonaco: jest.fn(),
}));

// Renders the wrapper the real editor puts `wrapperProps` on.
jest.mock("@monaco-editor/react", () => ({
  Editor: ({ wrapperProps }: { wrapperProps?: Record<string, unknown> }) => (
    <section {...wrapperProps} />
  ),
}));

import { SessionElementBodyView } from "../../../src/components/sessions/SessionElementBodyView";

describe("SessionElementBodyView", () => {
  it("should mark the editor with the default test id when none is passed", () => {
    render(<SessionElementBodyView headers={{}} body="" />);
    expect(screen.getByTestId("session-body-editor")).toBeTruthy();
  });

  it("should mark the editor with the caller test id when one is passed", () => {
    render(
      <SessionElementBodyView
        data-testid="session-body-after-editor"
        headers={{}}
        body=""
      />,
    );
    expect(screen.getByTestId("session-body-after-editor")).toBeTruthy();
    expect(screen.queryByTestId("session-body-editor")).toBeNull();
  });
});
