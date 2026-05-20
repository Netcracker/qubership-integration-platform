/**
 * @jest-environment jsdom
 */

import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";

Object.defineProperty(globalThis, "matchMedia", {
  writable: true,
  value: jest.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })),
});

const mockCloseContainingModal = jest.fn();
jest.mock("../../../src/ModalContextProvider.tsx", () => ({
  useModalContext: () => ({
    closeContainingModal: mockCloseContainingModal,
  }),
}));

const mockDownloadFile = jest.fn() as jest.MockedFunction<(file: File) => void>;
jest.mock("../../../src/misc/download-utils.ts", () => ({
  downloadFile: (file: File) => {
    mockDownloadFile(file);
  },
}));

const mockCopyToClipboard = jest
  .fn<Promise<void>, [string]>()
  .mockResolvedValue(undefined);
jest.mock("../../../src/misc/clipboard-util.ts", () => ({
  copyToClipboard: (text: string) => mockCopyToClipboard(text),
}));

jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`}>{name}</span>
  ),
}));

jest.mock("../../../src/misc/format-utils.ts", () => ({
  formatTimestamp: (ts: number | string) => `ts(${String(ts)})`,
  capitalize: (s: string) =>
    s && s.length > 0 ? s[0].toUpperCase() + s.slice(1).toLowerCase() : s,
  PLACEHOLDER: "—",
  formatOptional: <T,>(v: T, f?: (x: T) => string) =>
    v ? (f ? f(v) : String(v)) : "—",
}));

import { ErrorDetails } from "../../../src/components/modal/ErrorDetails";

describe("ErrorDetails", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("renders generic Error title when status is omitted", () => {
    render(
      <ErrorDetails
        service="svc-a"
        timestamp={0}
        message="Something broke"
        stacktrace=""
      />,
    );

    expect(screen.getByText("Error")).toBeInTheDocument();
  });

  it("renders Failed title when status=FAILED", () => {
    render(
      <ErrorDetails
        service="svc"
        timestamp={0}
        message="m"
        stacktrace=""
        status="FAILED"
      />,
    );

    expect(screen.getByText("Failed")).toBeInTheDocument();
  });

  it.each([
    ["PROCESSING", "Processing"],
    ["DEPLOYED", "Deployed"],
    ["REMOVED", "Removed"],
  ])("maps %s status to title %s", (status, label) => {
    render(
      <ErrorDetails
        service="svc"
        timestamp={0}
        message="m"
        stacktrace=""
        status={status}
      />,
    );
    expect(screen.getByText(label)).toBeInTheDocument();
  });

  it("capitalizes unknown statuses as title", () => {
    render(
      <ErrorDetails
        service="svc"
        timestamp={0}
        message="m"
        stacktrace=""
        status="unknown-status"
      />,
    );
    expect(screen.getByText("Unknown-status")).toBeInTheDocument();
  });

  it("shows service chip, formatted occurrence time, and message content", () => {
    render(
      <ErrorDetails
        service="qip-engine-v1"
        timestamp={999}
        message="Kafka predeploy check is failed"
        stacktrace=""
      />,
    );

    expect(screen.getByText("qip-engine-v1")).toBeInTheDocument();
    expect(screen.getByText("ts(999)")).toBeInTheDocument();
    expect(
      screen.getByText("Kafka predeploy check is failed"),
    ).toBeInTheDocument();
  });

  it('renders an em-dash when service is empty and "No message provided" when message is empty', () => {
    render(<ErrorDetails service="" timestamp={0} message="" stacktrace="" />);
    expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.getByText("No message provided")).toBeInTheDocument();
  });

  it("does not render the stack trace section when stacktrace is empty", () => {
    render(
      <ErrorDetails service="s" timestamp={0} message="m" stacktrace="" />,
    );
    expect(screen.queryByText("Stack trace")).not.toBeInTheDocument();
  });

  it("splits real newlines in stacktrace into separate lines", () => {
    render(
      <ErrorDetails
        service="s"
        timestamp={0}
        message="m"
        stacktrace={"at Foo\nat Bar\r\nat Baz"}
      />,
    );
    expect(screen.getByText("at Foo")).toBeInTheDocument();
    expect(screen.getByText("at Bar")).toBeInTheDocument();
    expect(screen.getByText("at Baz")).toBeInTheDocument();
    expect(screen.getByText("stderr")).toBeInTheDocument();
  });

  it("clicking Copy sends the full report text to clipboard including stacktrace", () => {
    render(
      <ErrorDetails
        service="svc"
        timestamp={10}
        message="msg"
        stacktrace="line1\nline2"
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /copy/i }));

    expect(mockCopyToClipboard).toHaveBeenCalledTimes(1);
    const [text] = mockCopyToClipboard.mock.calls[0];
    expect(text).toContain("Error Originating Service: svc");
    expect(text).toContain("Error Date: ts(10)");
    expect(text).toContain("Error Message:");
    expect(text).toContain("msg");
    expect(text).toContain("Stacktrace:");
  });

  it("omits Stacktrace section from copied text when stacktrace is empty", () => {
    render(
      <ErrorDetails service="svc" timestamp={10} message="msg" stacktrace="" />,
    );
    fireEvent.click(screen.getByRole("button", { name: /copy/i }));
    const [text] = mockCopyToClipboard.mock.calls[0];
    expect(text).not.toContain("Stacktrace:");
  });

  it("clicking Download produces a File with name derived from service and timestamp", () => {
    render(
      <ErrorDetails service="svc-a" timestamp={42} message="m" stacktrace="" />,
    );

    fireEvent.click(screen.getByRole("button", { name: /download/i }));

    expect(mockDownloadFile).toHaveBeenCalledTimes(1);
    const [file] = mockDownloadFile.mock.calls[0];
    expect(file).toBeInstanceOf(File);
    expect(file.name).toBe("Error at svc-a at ts(42).txt");
    expect(file.type).toBe("text/plain");
  });
});
