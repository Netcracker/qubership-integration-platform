/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest, beforeEach } from "@jest/globals";
import React from "react";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";

import { UserMenu } from "../../src/components/UserMenu";
import { configure } from "../../src/appConfig";
import { ThemeContext } from "../../src/theme/context";

let capturedConfirmOnOk: (() => void | Promise<void>) | undefined;

jest.mock("../../src/misc/confirm-utils.ts", () => ({
  confirmAndRun: (opts: { onOk: () => void | Promise<void> }) => {
    capturedConfirmOnOk = opts.onOk;
  },
}));

const mockOnThemeChange = jest.fn();

function renderWithTheme(ui: React.ReactElement = <UserMenu />) {
  return render(
    <ThemeContext.Provider
      value={{ theme: "light", onThemeChange: mockOnThemeChange }}
    >
      {ui}
    </ThemeContext.Provider>,
  );
}

function resetUserConfig(): void {
  configure({
    userInfo: {
      userName: undefined,
      email: undefined,
      tenantName: undefined,
      tenantId: undefined,
    },
    onLogout: undefined,
  });
}

describe("UserMenu", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    resetUserConfig();
    capturedConfirmOnOk = undefined;
    mockOnThemeChange.mockClear();
    localStorage.clear();
  });

  it("renders fallback user icon when no userName is set", () => {
    const { container } = render(<UserMenu />);
    const trigger = container.querySelector(
      'button[aria-label="User menu"]',
    ) as HTMLButtonElement;
    expect(trigger).toBeTruthy();
    expect(trigger.querySelector(".anticon-user")).toBeTruthy();
  });

  it("uses the userName as the trigger tooltip title", () => {
    configure({ userInfo: { userName: "Tenant Admin" } });

    const { container } = render(<UserMenu />);
    const trigger = container.querySelector(
      'button[aria-label="User menu"]',
    ) as HTMLButtonElement;
    expect(trigger.getAttribute("title")).toBe("Tenant Admin");
  });

  it("opens dropdown and shows user info, email and tenant fields", async () => {
    configure({
      userInfo: {
        userName: "Tenant Admin",
        email: "admin@example.com",
        tenantName: "cpq",
        tenantId: "tid-123",
      },
    });

    const { container } = render(<UserMenu />);
    fireEvent.click(container.querySelector('button[aria-label="User menu"]')!);

    expect(await screen.findByText("Tenant Admin")).toBeTruthy();
    expect(screen.getByText("admin@example.com")).toBeTruthy();
    expect(screen.getByText("Tenant name")).toBeTruthy();
    expect(screen.getByText("cpq")).toBeTruthy();
    expect(screen.getByText("Tenant ID")).toBeTruthy();
    expect(screen.getByText("tid-123")).toBeTruthy();
  });

  it("uses 'Dev user' label when userName is absent", async () => {
    const { container } = render(<UserMenu />);
    fireEvent.click(container.querySelector('button[aria-label="User menu"]')!);

    expect(await screen.findByText("Dev user")).toBeTruthy();
  });

  it("hides the tenant section when no tenant info is set", async () => {
    configure({ userInfo: { userName: "Alice" } });

    const { container } = render(<UserMenu />);
    fireEvent.click(container.querySelector('button[aria-label="User menu"]')!);

    await screen.findByText("Alice");
    expect(screen.queryByText("Tenant name")).toBeNull();
    expect(screen.queryByText("Tenant ID")).toBeNull();
  });

  it("renders a copy control next to Tenant ID", async () => {
    configure({
      userInfo: { userName: "Alice", tenantId: "tid-42" },
    });

    const { container } = render(<UserMenu />);
    fireEvent.click(container.querySelector('button[aria-label="User menu"]')!);

    await screen.findByText("tid-42");
    await waitFor(() => {
      expect(document.body.querySelector(".ant-typography-copy")).toBeTruthy();
    });
  });

  it("hides the Log out button when onLogout is not configured", async () => {
    configure({ userInfo: { userName: "Alice" } });

    const { container } = render(<UserMenu />);
    fireEvent.click(container.querySelector('button[aria-label="User menu"]')!);

    await screen.findByText("Reset UI preferences");
    expect(screen.queryByText("Log out")).toBeNull();
  });

  it("shows the Log out button and calls onLogout when clicked", async () => {
    const onLogout = jest.fn();
    configure({ userInfo: { userName: "Alice" }, onLogout });

    const { container } = render(<UserMenu />);
    fireEvent.click(container.querySelector('button[aria-label="User menu"]')!);

    const logout = await screen.findByText("Log out");
    fireEvent.click(logout);

    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it("opens a confirmation when Reset UI preferences is clicked", async () => {
    configure({ userInfo: { userName: "Alice" } });

    const { container } = render(<UserMenu />);
    fireEvent.click(container.querySelector('button[aria-label="User menu"]')!);

    const reset = await screen.findByText("Reset UI preferences");
    fireEvent.click(reset);

    expect(capturedConfirmOnOk).toBeDefined();
  });

  it("clears localStorage and reloads when reset confirmation is accepted", async () => {
    const reloadSpy = jest.fn();
    const originalLocation = window.location;
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { ...originalLocation, reload: reloadSpy },
    });

    localStorage.setItem("foo", "bar");
    localStorage.setItem("baz", "qux");
    configure({ userInfo: { userName: "Alice" } });

    const { container } = render(<UserMenu />);
    fireEvent.click(container.querySelector('button[aria-label="User menu"]')!);

    const reset = await screen.findByText("Reset UI preferences");
    fireEvent.click(reset);

    await act(async () => {
      await capturedConfirmOnOk!();
    });

    expect(localStorage.length).toBe(0);
    expect(reloadSpy).toHaveBeenCalledTimes(1);

    Object.defineProperty(window, "location", {
      configurable: true,
      value: originalLocation,
    });
  });

  it("does not clear localStorage if reset confirmation is not accepted", async () => {
    localStorage.setItem("keep-me", "1");
    configure({ userInfo: { userName: "Alice" } });

    const { container } = render(<UserMenu />);
    fireEvent.click(container.querySelector('button[aria-label="User menu"]')!);

    const reset = await screen.findByText("Reset UI preferences");
    fireEvent.click(reset);

    expect(localStorage.getItem("keep-me")).toBe("1");
  });

  it("does not re-render when unrelated config fields change", () => {
    configure({ userInfo: { userName: "Alice" } });

    const renderSpy = jest.fn();
    const Probe: React.FC = () => {
      renderSpy();
      return <UserMenu />;
    };
    render(<Probe />);
    const initialCalls = renderSpy.mock.calls.length;

    act(() => {
      configure({ documentationBaseUrl: "https://example.com/unrelated" });
    });

    expect(renderSpy.mock.calls.length).toBe(initialCalls);
  });

  it("hides the theme switcher when ThemeContext is not provided", async () => {
    const { container } = render(<UserMenu />);
    fireEvent.click(container.querySelector('button[aria-label="User menu"]')!);

    await screen.findByText("Reset UI preferences");
    expect(screen.queryByText("Theme")).toBeNull();
    expect(screen.queryByText("System")).toBeNull();
  });

  it("shows the theme switcher above Reset UI preferences when ThemeContext is provided", async () => {
    configure({ userInfo: { userName: "Alice" } });

    const { container } = renderWithTheme();
    fireEvent.click(container.querySelector('button[aria-label="User menu"]')!);

    const themeLabel = await screen.findByText("Theme");
    const reset = screen.getByText("Reset UI preferences");
    expect(
      themeLabel.compareDocumentPosition(reset) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("shows theme options inline when ThemeContext is provided", async () => {
    configure({ userInfo: { userName: "Alice" } });

    const { container } = renderWithTheme();
    fireEvent.click(container.querySelector('button[aria-label="User menu"]')!);

    // Options are icon-only; the label is kept as the native tooltip (title).
    expect(await screen.findByTitle("System")).toBeTruthy();
    expect(screen.getByTitle("Light")).toBeTruthy();
    expect(screen.getByTitle("Dark")).toBeTruthy();
    expect(screen.getByTitle("HC")).toBeTruthy();
  });

  it("reacts to configure() updates after mount", async () => {
    const { container } = render(<UserMenu />);
    const trigger = container.querySelector(
      'button[aria-label="User menu"]',
    ) as HTMLButtonElement;
    expect(trigger.getAttribute("title")).toBe("Dev user");

    act(() => {
      configure({ userInfo: { userName: "Bob" } });
    });

    await waitFor(() => {
      expect(trigger.getAttribute("title")).toBe("Bob");
    });
  });
});
