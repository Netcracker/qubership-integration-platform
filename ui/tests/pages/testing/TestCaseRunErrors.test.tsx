/**
 * @jest-environment jsdom
 */
import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  MatcherEntityType,
  MatcherType,
  TestCaseRunView,
  TestingValidationError,
  TestRunStatus,
} from "../../../src/api/apiTypes.ts";
import { api } from "../../../src/api/api.ts";
import { UserPermissionsContext } from "../../../src/permissions/UserPermissionsContext.tsx";
import type { UserPermissions } from "../../../src/permissions/types.ts";
import { TestCaseRunErrors } from "../../../src/pages/testing/TestCaseRunErrors.tsx";
import { ChainHeaderTestRoot } from "../../helpers/renderWithChainHeader.tsx";

jest.mock("../../../src/api/api.ts", () => ({
  api: {
    getTestCaseRunErrors: jest.fn(),
    getTestCaseRun: jest.fn(),
    exportTestCaseRunErrors: jest.fn(),
  },
}));

const mockGetTestCaseRunErrors = jest.spyOn(api, "getTestCaseRunErrors");
const mockGetTestCaseRun = jest.spyOn(api, "getTestCaseRun");
const mockExportTestCaseRunErrors = jest.spyOn(api, "exportTestCaseRunErrors");

const mockNavigate = jest.fn();
const mockUseParams: jest.Mock<{
  chainId?: string;
  runId?: string;
  caseRunId: string;
}> = jest.fn(() => ({ chainId: "chain-1", caseRunId: "case-run-1" }));

jest.mock("react-router", () => ({
  useNavigate: () => mockNavigate,
  useParams: () => mockUseParams(),
}));

jest.mock("antd", () => {
  const { createChainPageAntdMock } = jest.requireActual<{
    createChainPageAntdMock: () => Record<string, unknown>;
  }>("tests/helpers/chainPageAntdJestMock");
  return createChainPageAntdMock();
});

jest.mock("antd/lib/table", () => ({}));
jest.mock("antd/lib/table/interface", () => ({}));

jest.mock("../../../src/components/table/CompactSearch.tsx", () => ({
  CompactSearch: (props: {
    value: string;
    onChange: (value: string) => void;
    placeholder: string;
  }) => (
    <input
      data-testid="search-input"
      value={props.value}
      placeholder={props.placeholder}
      onChange={(event) => props.onChange(event.target.value)}
    />
  ),
}));

jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

jest.mock("../../../src/Modals.tsx", () => ({
  Modals: ({ children }: { children: React.ReactNode }) => children,
  useModalsContext: () => ({ showModal: jest.fn(), closeModal: jest.fn() }),
}));

const mockNotificationService = {
  requestFailed: jest.fn(),
  errorWithDetails: jest.fn(),
  info: jest.fn(),
  warning: jest.fn(),
};

jest.mock("../../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => mockNotificationService,
}));

Object.defineProperty(URL, "createObjectURL", {
  writable: true,
  value: jest.fn(() => "blob:validation-errors"),
});

const ALL_PERMISSIONS: UserPermissions = {
  chain: ["read", "update", "execute", "import", "export"],
  adminTools: ["read", "update", "execute", "import", "export"],
};

function validationError(
  overrides: Partial<TestingValidationError> = {},
): TestingValidationError {
  return {
    id: "error-1",
    testCaseRunId: "case-run-1",
    matcherId: "matcher-1",
    matcher: {
      id: "matcher-1",
      name: "Status is 200",
      description: "The trigger answers with 200",
      enabled: true,
      type: MatcherType.EQUAL,
      entityType: MatcherEntityType.STATUS,
      entityName: null,
      parameters: [{ name: "value", value: "200" }],
    },
    message: "expected 200, got 500",
    ...overrides,
  };
}

function testCaseRun(
  overrides: Partial<TestCaseRunView> = {},
): TestCaseRunView {
  return {
    id: "case-run-1",
    testsRunId: "tests-run-1",
    testCaseId: "case-1",
    testCaseName: "First case",
    testCaseDescription: "First description",
    chainId: "chain-1",
    start: "2026-08-13T10:00:00.000Z",
    finish: "2026-08-13T10:00:05.000Z",
    status: TestRunStatus.FINISHED,
    sessionId: null,
    ordinal: 0,
    errors: 1,
    ...overrides,
  };
}

type RenderOptions = {
  /** Render the drill-down reached from the admin test-run list. */
  adminScoped?: boolean;
  permissions?: UserPermissions;
};

function renderErrors({
  adminScoped = false,
  permissions = ALL_PERMISSIONS,
}: RenderOptions = {}) {
  mockUseParams.mockReturnValue(
    adminScoped
      ? { runId: "tests-run-1", caseRunId: "case-run-1" }
      : { chainId: "chain-1", caseRunId: "case-run-1" },
  );
  return render(
    <UserPermissionsContext.Provider value={permissions}>
      <ChainHeaderTestRoot>
        <TestCaseRunErrors />
      </ChainHeaderTestRoot>
    </UserPermissionsContext.Provider>,
  );
}

async function renderWithErrors(
  errors: TestingValidationError[],
  options: RenderOptions = {},
) {
  mockGetTestCaseRunErrors.mockResolvedValue(errors);
  const result = renderErrors(options);
  await waitFor(() => expect(mockGetTestCaseRunErrors).toHaveBeenCalled());
  if (errors.length > 0) {
    await screen.findByText(errors[0].message);
  }
  return result;
}

beforeEach(() => {
  mockUseParams.mockReturnValue({
    chainId: "chain-1",
    caseRunId: "case-run-1",
  });
  mockGetTestCaseRunErrors.mockResolvedValue([]);
  mockGetTestCaseRun.mockResolvedValue(testCaseRun());
});

describe("TestCaseRunErrors loading", () => {
  it("should read the errors and the run of the case run it was opened for", async () => {
    await renderWithErrors([validationError()]);

    expect(mockGetTestCaseRunErrors).toHaveBeenCalledWith("case-run-1");
    await waitFor(() =>
      expect(mockGetTestCaseRun).toHaveBeenCalledWith("case-run-1"),
    );
  });

  it("should render the failing rule and its message", async () => {
    await renderWithErrors([validationError()]);

    expect(screen.getByText("Status is 200")).toBeInTheDocument();
    expect(
      screen.getByText("The trigger answers with 200"),
    ).toBeInTheDocument();
    expect(screen.getByText("expected 200, got 500")).toBeInTheDocument();
  });

  it("should notify when the errors cannot be read", async () => {
    mockGetTestCaseRunErrors.mockRejectedValue(new Error("no connection"));
    renderErrors();

    await waitFor(() =>
      expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
        "Failed to load the validation errors",
        expect.anything(),
      ),
    );
  });

  it("should reload both requests on refresh", async () => {
    await renderWithErrors([validationError()]);
    mockGetTestCaseRunErrors.mockClear();
    mockGetTestCaseRun.mockClear();

    fireEvent.click(screen.getByTestId("test-case-run-errors-refresh"));

    await waitFor(() => expect(mockGetTestCaseRunErrors).toHaveBeenCalled());
    expect(mockGetTestCaseRun).toHaveBeenCalled();
  });
});

describe("TestCaseRunErrors routes", () => {
  it("should lead back to the case runs of the chain", async () => {
    await renderWithErrors([validationError()]);

    fireEvent.click(screen.getByText("Test Case Runs"));

    expect(mockNavigate).toHaveBeenCalledWith(
      "/chains/chain-1/testing/test-case-runs",
    );
  });

  it("should lead back through the run it was drilled into", async () => {
    await renderWithErrors([validationError()], { adminScoped: true });

    fireEvent.click(screen.getByText("Test Runs"));
    expect(mockNavigate).toHaveBeenCalledWith("/admintools/testing/test-runs");

    fireEvent.click(screen.getByText("tests-run-1"));
    expect(mockNavigate).toHaveBeenCalledWith(
      "/admintools/testing/test-runs/tests-run-1",
    );
  });

  it("should link the rule to the response validation of its test case", async () => {
    await renderWithErrors([validationError()]);
    await screen.findByText("First case");

    fireEvent.click(screen.getByText("Status is 200"));

    expect(mockNavigate).toHaveBeenCalledWith(
      "/chains/chain-1/testing/test-cases/case-1/response-validation",
    );
  });

  it("should link the rule to the admin editor when opened from a run", async () => {
    await renderWithErrors([validationError()], { adminScoped: true });
    await screen.findByText("First case");

    fireEvent.click(screen.getByText("Status is 200"));

    expect(mockNavigate).toHaveBeenCalledWith(
      "/admintools/testing/test-cases/case-1/response-validation",
    );
  });

  it("should fall back to the matcher id when the error carries no matcher", async () => {
    mockGetTestCaseRun.mockResolvedValue(testCaseRun({ testCaseId: null }));
    await renderWithErrors([validationError({ matcher: null })]);

    fireEvent.click(screen.getByText("matcher-1"));

    expect(mockNavigate).not.toHaveBeenCalled();
  });
});

describe("TestCaseRunErrors search", () => {
  it("should keep only the rows matching the search term", async () => {
    await renderWithErrors([
      validationError(),
      validationError({
        id: "error-2",
        matcherId: "matcher-2",
        matcher: null,
        message: "body does not contain the order id",
      }),
    ]);

    fireEvent.change(screen.getByTestId("search-input"), {
      target: { value: "order id" },
    });

    expect(
      screen.getByText("body does not contain the order id"),
    ).toBeInTheDocument();
    expect(screen.queryByText("expected 200, got 500")).not.toBeInTheDocument();
  });

  it("should drop the selection when the search changes", async () => {
    await renderWithErrors([
      validationError(),
      validationError({
        id: "error-2",
        matcherId: "matcher-2",
        matcher: null,
        message: "body does not contain the order id",
      }),
    ]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.change(screen.getByTestId("search-input"), {
      target: { value: "order id" },
    });

    await waitFor(() =>
      expect(
        (screen.getAllByRole("checkbox")[1] as HTMLInputElement).checked,
      ).toBe(false),
    );
    // The export must not carry a row the search has hidden.
    fireEvent.click(screen.getByTestId("test-case-run-errors-export"));
    expect(mockExportTestCaseRunErrors).not.toHaveBeenCalled();
  });
});

describe("TestCaseRunErrors export", () => {
  it("should export the selected errors by their own ids", async () => {
    mockExportTestCaseRunErrors.mockResolvedValue(
      new File([], "validation-errors.csv"),
    );
    await renderWithErrors([validationError()]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.click(screen.getByTestId("test-case-run-errors-export"));

    await waitFor(() =>
      expect(mockExportTestCaseRunErrors).toHaveBeenCalledWith(["error-1"]),
    );
  });

  it("should do nothing when no row is selected", async () => {
    await renderWithErrors([validationError()]);

    fireEvent.click(screen.getByTestId("test-case-run-errors-export"));

    expect(mockExportTestCaseRunErrors).not.toHaveBeenCalled();
  });

  it("should notify when the export fails", async () => {
    mockExportTestCaseRunErrors.mockRejectedValue(new Error("no connection"));
    await renderWithErrors([validationError()]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.click(screen.getByTestId("test-case-run-errors-export"));

    await waitFor(() =>
      expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
        "Failed to export the validation errors",
        expect.anything(),
      ),
    );
  });
});

describe("TestCaseRunErrors permission gating", () => {
  it("should hide the export without the export right of the chain", async () => {
    await renderWithErrors([validationError()], {
      permissions: { chain: ["read"] },
    });

    expect(
      screen.getByTestId("test-case-run-errors-refresh"),
    ).toBeInTheDocument();
    expect(
      screen.queryByTestId("test-case-run-errors-export"),
    ).not.toBeInTheDocument();
  });

  it("should gate the drill-down on admin tools rights", async () => {
    await renderWithErrors([validationError()], {
      adminScoped: true,
      permissions: { chain: ["read", "update", "execute", "export", "import"] },
    });

    expect(
      screen.queryByTestId("test-case-run-errors-refresh"),
    ).not.toBeInTheDocument();
  });
});
