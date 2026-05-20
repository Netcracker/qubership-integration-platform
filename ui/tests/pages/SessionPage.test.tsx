/**
 * @jest-environment jsdom
 */

import React from "react";
import { screen, waitFor, within, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  ExecutionStatus,
  type Session,
  type SessionElement,
} from "../../src/api/apiTypes.ts";
import { api } from "../../src/api/api.ts";
import { SessionPage } from "../../src/pages/SessionPage.tsx";
import { renderPageWithChainHeader } from "../helpers/renderWithChainHeader.tsx";

const mockUseParams = jest.fn(() => ({
  chainId: "chain-1",
  sessionId: "session-1",
}));
const mockNavigate = jest.fn();

jest.mock("react-router", () => ({
  useParams: () => mockUseParams(),
  useNavigate: () => mockNavigate,
}));

jest.mock("../../src/api/api.ts", () => ({
  api: {
    getSession: jest.fn(),
    exportSessions: jest.fn(),
  },
}));

jest.mock("antd", () =>
  // eslint-disable-next-line @typescript-eslint/no-require-imports, @typescript-eslint/no-unsafe-call, @typescript-eslint/no-unsafe-member-access, @typescript-eslint/no-unsafe-return -- jest.mock hoisting
  require("tests/helpers/chainPageAntdJestMock").createChainPageAntdMock(),
);

jest.mock("antd/lib/table", () => ({}));
jest.mock("antd/lib/table/interface", () => ({}));
jest.mock("antd/es/table/interface", () => ({}));

jest.mock("../../src/components/table/CompactSearch.tsx", () => ({
  CompactSearch: (props: {
    value: string;
    onChange: (v: string) => void;
    placeholder: string;
  }) => (
    <input
      data-testid="session-elements-search"
      value={props.value}
      onChange={(e) => props.onChange(e.target.value)}
      placeholder={props.placeholder}
    />
  ),
}));

jest.mock("../../src/components/sessions/SessionStatus.tsx", () => ({
  SessionStatus: ({ status }: { status: string }) => (
    <span data-testid="session-status">{String(status)}</span>
  ),
}));

jest.mock("../../src/components/sessions/SessionElementDuration.tsx", () => ({
  SessionElementDuration: ({ duration }: { duration: number }) => (
    <span data-testid="element-duration">{duration}</span>
  ),
}));

jest.mock("../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`}>{name}</span>
  ),
}));

const notifyMocks = {
  requestFailed: jest.fn(),
  errorWithDetails: jest.fn(),
  info: jest.fn(),
  warning: jest.fn(),
};

jest.mock("../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => notifyMocks,
}));

jest.mock("../../src/Modals.tsx", () => {
  const R = require("react") as typeof import("react");
  const modalsApi = {
    showModal: jest.fn(),
    closeModal: jest.fn(),
  };
  return {
    Modals: ({ children }: { children: R.ReactNode }) => (
      <R.Fragment>{children}</R.Fragment>
    ),
    useModalsContext: () => modalsApi,
  };
});

jest.mock("../../src/misc/download-utils.ts", () => ({
  downloadFile: jest.fn(),
}));

function baseElement(
  overrides: Partial<SessionElement> & {
    elementId: string;
    elementName: string;
  },
): SessionElement {
  return {
    sessionId: "session-1",
    chainElementId: `ce-${overrides.elementId}`,
    actualElementChainId: "chain-1",
    parentElement: "",
    previousElement: "",
    camelName: "script",
    bodyBefore: "",
    bodyAfter: "",
    headersBefore: {},
    headersAfter: {},
    propertiesBefore: {},
    propertiesAfter: {},
    contextBefore: {},
    contextAfter: {},
    started: "",
    finished: "",
    duration: 10,
    syncDuration: 0,
    executionStatus: ExecutionStatus.COMPLETED_NORMALLY,
    exceptionInfo: { message: "", stackTrace: "" },
    children: undefined,
    ...overrides,
  };
}

function baseSession(overrides: Partial<Session>): Session {
  return {
    id: "session-1",
    chainId: "chain-1",
    chainName: "Chain",
    started: "",
    finished: "",
    duration: 100,
    syncDuration: 0,
    executionStatus: ExecutionStatus.COMPLETED_NORMALLY,
    importedSession: false,
    externalSessionCipId: "",
    domain: "d",
    engineAddress: "",
    loggingLevel: "INFO",
    snapshotName: "snap-v1",
    correlationId: "",
    parentSessionId: "",
    sessionElements: [],
    ...overrides,
  };
}

function renderSessionPage() {
  return renderPageWithChainHeader(<SessionPage />);
}

describe("SessionPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockUseParams.mockReturnValue({
      chainId: "chain-1",
      sessionId: "session-1",
    });
    (api.getSession as jest.Mock).mockResolvedValue(
      baseSession({
        sessionElements: [
          baseElement({
            elementId: "e-apple",
            elementName: "Apple",
          }),
          baseElement({
            elementId: "e-banana",
            elementName: "Banana",
          }),
        ],
      }),
    );
    (api.exportSessions as jest.Mock).mockResolvedValue(new Blob());
  });

  test("chain header shows snapshot tag, status, search, toolbar actions", async () => {
    renderSessionPage();

    await waitFor(() => {
      expect(
        within(screen.getByTestId("chain-header-slot")).getByText("snap-v1"),
      ).toBeInTheDocument();
    });
    const slot = screen.getByTestId("chain-header-slot");
    expect(within(slot).getByTestId("session-status")).toHaveTextContent(
      ExecutionStatus.COMPLETED_NORMALLY,
    );
    expect(
      within(slot).getByPlaceholderText("Search session elements..."),
    ).toBeInTheDocument();
    expect(within(slot).getByTestId("icon-expandAll")).toBeInTheDocument();
    expect(within(slot).getByTestId("icon-collapseAll")).toBeInTheDocument();
    expect(within(slot).getByTestId("icon-cloudDownload")).toBeInTheDocument();
  });

  test("search filters element rows by name", async () => {
    renderSessionPage();

    await waitFor(() => {
      expect(screen.getByText("Apple")).toBeInTheDocument();
    });
    expect(screen.getByText("Banana")).toBeInTheDocument();

    fireEvent.change(screen.getByTestId("session-elements-search"), {
      target: { value: "Banana" },
    });

    await waitFor(() => {
      expect(screen.queryByText("Apple")).not.toBeInTheDocument();
    });
    expect(screen.getByText("Banana")).toBeInTheDocument();
  });

  test("expand all then collapse all toggles nested row visibility", async () => {
    (api.getSession as jest.Mock).mockResolvedValue(
      baseSession({
        sessionElements: [
          baseElement({
            elementId: "e-root",
            elementName: "Root",
            children: [
              baseElement({
                elementId: "e-child",
                elementName: "ChildNested",
                parentElement: "e-root",
              }),
            ],
          }),
        ],
      }),
    );

    renderSessionPage();

    await waitFor(() => {
      expect(screen.getByText("Root")).toBeInTheDocument();
    });
    expect(screen.queryByText("ChildNested")).not.toBeInTheDocument();

    const slot = screen.getByTestId("chain-header-slot");
    fireEvent.click(within(slot).getByTestId("icon-expandAll"));

    await waitFor(() => {
      expect(screen.getByText("ChildNested")).toBeInTheDocument();
    });

    fireEvent.click(within(slot).getByTestId("icon-collapseAll"));

    await waitFor(() => {
      expect(screen.queryByText("ChildNested")).not.toBeInTheDocument();
    });
  });

  test("export triggers api.exportSessions for current session", async () => {
    renderSessionPage();

    await waitFor(() => {
      expect(screen.getByText("Apple")).toBeInTheDocument();
    });

    const slot = screen.getByTestId("chain-header-slot");
    fireEvent.click(within(slot).getByTestId("icon-cloudDownload"));

    await waitFor(() => {
      expect(api.exportSessions).toHaveBeenCalledWith(["session-1"]);
    });
  });

  test("getSession failure notifies user", async () => {
    (api.getSession as jest.Mock).mockRejectedValue(new Error("boom"));

    renderSessionPage();

    await waitFor(() => {
      expect(notifyMocks.requestFailed).toHaveBeenCalledWith(
        "Failed to get session",
        expect.any(Error),
      );
    });
  });
});
