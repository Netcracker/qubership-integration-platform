/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { ServiceListPage } from "../../../src/components/services/ServiceListPage";

// ---------------------------------------------------------------------------
// Mock useLocationHash so tests control the active tab without touching the
// real browser hash or the real hook implementation.
// ---------------------------------------------------------------------------
const mockUseLocationHash = jest.fn();
jest.mock("../../../src/components/services/useLocationHash", () => ({
  useLocationHash: (...args: unknown[]) => mockUseLocationHash(...args),
}));

// ---------------------------------------------------------------------------
// Stub child components with lightweight identifiable nodes.
// ServicesList also captures its props so we can assert prop forwarding.
// ---------------------------------------------------------------------------
let capturedServicesListProps: Record<string, unknown> = {};

jest.mock("../../../src/components/services/ServicesList", () => ({
  ServicesList: (props: Record<string, unknown>) => {
    capturedServicesListProps = props;
    return <div data-testid="services-list" data-tab={props.tab as string} />;
  },
}));

jest.mock("../../../src/components/services/mcp/McpServiceList", () => ({
  McpServiceList: () => <div data-testid="mcp-service-list" />,
}));

jest.mock(
  "../../../src/components/services/context/ContextServiceList",
  () => ({
    ContextServiceList: () => <div data-testid="context-service-list" />,
  }),
);

jest.mock("../../../src/pages/NotFound", () => ({
  NotFound: () => <div data-testid="not-found" />,
}));

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Simulate a given hash being active and render the page. */
function renderWithHash(hash: string) {
  mockUseLocationHash.mockReturnValue([hash, jest.fn()]);
  return render(<ServiceListPage />);
}

const ALL_STUBS = [
  "services-list",
  "mcp-service-list",
  "context-service-list",
  "not-found",
] as const;

/** Assert none of the other stubs are present in the document. */
function expectOnlyVisible(activeTestId: string) {
  for (const id of ALL_STUBS) {
    if (id === activeTestId) {
      expect(screen.getByTestId(id)).toBeInTheDocument();
    } else {
      expect(screen.queryByTestId(id)).not.toBeInTheDocument();
    }
  }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("ServiceListPage", () => {
  beforeEach(() => {
    capturedServicesListProps = {};
  });

  // --- Routing: correct component is mounted for each hash value -----------

  it("TC-1: renders ServicesList for the default hash value 'external'", () => {
    renderWithHash("external");

    expect(screen.getByTestId("services-list")).toBeInTheDocument();
    // useLocationHash must be called with the correct defaultValue
    expect(mockUseLocationHash).toHaveBeenCalledWith("external");
  });

  it("TC-2: renders ServicesList for hash 'internal'", () => {
    renderWithHash("internal");

    expect(screen.getByTestId("services-list")).toBeInTheDocument();
  });

  it("TC-3: renders ServicesList for hash 'implemented'", () => {
    renderWithHash("implemented");

    expect(screen.getByTestId("services-list")).toBeInTheDocument();
  });

  it("TC-4: renders McpServiceList for hash 'mcp'", () => {
    renderWithHash("mcp");

    expect(screen.getByTestId("mcp-service-list")).toBeInTheDocument();
  });

  it("TC-5: renders ContextServiceList for hash 'context'", () => {
    renderWithHash("context");

    expect(screen.getByTestId("context-service-list")).toBeInTheDocument();
  });

  it("TC-6: renders NotFound for an unrecognised hash value", () => {
    renderWithHash("unknown-hash");

    expect(screen.getByTestId("not-found")).toBeInTheDocument();
  });

  // --- Prop forwarding: ServicesList receives the correct `tab` prop -------

  it("TC-7: passes tab='external' to ServicesList when hash is 'external'", () => {
    renderWithHash("external");

    expect(capturedServicesListProps.tab).toBe("external");
  });

  it("TC-8: passes tab='internal' to ServicesList when hash is 'internal'", () => {
    renderWithHash("internal");

    expect(capturedServicesListProps.tab).toBe("internal");
  });

  it("TC-9: passes tab='implemented' to ServicesList when hash is 'implemented'", () => {
    renderWithHash("implemented");

    expect(capturedServicesListProps.tab).toBe("implemented");
  });

  // --- Mutual exclusivity: only one branch renders at a time ---------------

  it("TC-10: only the expected child is mounted for each route — no double-rendering", () => {
    const cases: Array<[string, string]> = [
      ["external", "services-list"],
      ["internal", "services-list"],
      ["implemented", "services-list"],
      ["mcp", "mcp-service-list"],
      ["context", "context-service-list"],
      ["unknown-hash", "not-found"],
    ];

    for (const [hash, expectedId] of cases) {
      const { unmount } = renderWithHash(hash);
      expectOnlyVisible(expectedId);
      unmount();
    }
  });
});
