/**
 * @jest-environment jsdom
 */
import React from "react";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  createMemoryRouter,
  Navigate,
  RouterProvider,
  type RouteObject,
} from "react-router";
import {
  Element,
  MatcherEntityType,
  MatcherType,
  TestCaseView,
} from "../../../src/api/apiTypes.ts";
import { api } from "../../../src/api/api.ts";
import { TestCasePage } from "../../../src/pages/testing/TestCasePage.tsx";
import { TestCaseGeneralTab } from "../../../src/components/testing/testCase/TestCaseGeneralTab.tsx";
import { TestCaseRequestTab } from "../../../src/components/testing/testCase/TestCaseRequestTab.tsx";
import { TestCaseResponseValidationTab } from "../../../src/components/testing/testCase/TestCaseResponseValidationTab.tsx";
import { UserPermissionsContext } from "../../../src/permissions/UserPermissionsContext.tsx";
import type { UserPermissions } from "../../../src/permissions/types.ts";
import { openSelect, querySelectOption } from "../../helpers/antdSelect.ts";
import { installDataRouterGlobals } from "../../helpers/dataRouterGlobals.ts";
import { ChainHeaderActionsTestSlot } from "../../helpers/renderWithChainHeader.tsx";

installDataRouterGlobals();

jest.mock("../../../src/api/api.ts", () => ({
  api: {
    getTestCase: jest.fn(),
    updateTestCase: jest.fn(),
    getElements: jest.fn(),
    getChain: jest.fn(),
  },
}));

const mockGetTestCase = jest.spyOn(api, "getTestCase");
const mockUpdateTestCase = jest.spyOn(api, "updateTestCase");
const mockGetElements = jest.spyOn(api, "getElements");
const mockGetChain = jest.spyOn(api, "getChain");

jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

jest.mock("../../../src/components/Script.tsx", () => ({
  Script: ({
    value,
    onChange,
  }: {
    value: string;
    onChange?: (value: string) => void;
  }) => (
    <textarea
      aria-label="Body"
      value={value}
      onChange={(event) => onChange?.(event.target.value)}
    />
  ),
}));

const mockShowModal = jest.fn();

jest.mock("../../../src/Modals.tsx", () => ({
  useModalsContext: () => ({ showModal: mockShowModal, closeModal: jest.fn() }),
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

const ALL_PERMISSIONS: UserPermissions = {
  chain: ["read", "update", "execute", "import", "export"],
  adminTools: ["read", "update", "execute", "import", "export"],
};

function testCase(overrides: Partial<TestCaseView> = {}): TestCaseView {
  return {
    id: "case-1",
    name: "First case",
    description: "First description",
    enabled: false,
    triggerReference: { chainId: "chain-1", elementId: "element-1" },
    requestSettings: {
      queryParameters: [{ name: "flag", value: "on" }],
      pathParameters: [],
      message: { body: "{}", headers: [] },
      method: "GET",
      timeout: 120000,
    },
    responseValidationRules: [
      {
        id: "rule-1",
        name: "body exists",
        description: "",
        enabled: true,
        type: MatcherType.EXIST,
        entityType: MatcherEntityType.BODY,
        entityName: null,
        parameters: [],
      },
    ],
    validationRuleCount: 1,
    enabledRuleCount: 1,
    createdBy: "author",
    createdAt: "2026-08-13T10:00:00.000Z",
    updatedBy: null,
    updatedAt: null,
    ...overrides,
  };
}

function trigger(overrides: Partial<Element> = {}): Element {
  return {
    id: "element-1",
    name: "Incoming request",
    description: "",
    chainId: "chain-1",
    type: "http-trigger",
    properties: { httpMethodRestrict: "GET,POST" } as never,
    mandatoryChecksPassed: true,
    ...overrides,
  };
}

function editorRoutes(): RouteObject[] {
  return [
    { index: true, element: <div>test cases list</div> },
    {
      path: ":testCaseId",
      element: <TestCasePage />,
      children: [
        { index: true, element: <Navigate to="general" replace /> },
        { path: "general", element: <TestCaseGeneralTab /> },
        { path: "request", element: <TestCaseRequestTab /> },
        {
          path: "response-validation",
          element: <TestCaseResponseValidationTab />,
        },
      ],
    },
  ];
}

function renderEditor(
  path: string,
  permissions: UserPermissions = ALL_PERMISSIONS,
) {
  const router = createMemoryRouter(
    [
      {
        path: "/chains/:chainId/testing/test-cases",
        children: editorRoutes(),
      },
      { path: "/admintools/testing/test-cases", children: editorRoutes() },
    ],
    { initialEntries: [path] },
  );
  const utils = render(
    <UserPermissionsContext.Provider value={permissions}>
      <ChainHeaderActionsTestSlot>
        <RouterProvider router={router} />
      </ChainHeaderActionsTestSlot>
    </UserPermissionsContext.Provider>,
  );
  return { ...utils, router };
}

/** Save lives in the chain header now, so leaving is the breadcrumb's job. */
function leaveEditor() {
  fireEvent.click(screen.getByText("Test Cases"));
}

const CHAIN_EDITOR_PATH = "/chains/chain-1/testing/test-cases/case-1";
const ADMIN_EDITOR_PATH = "/admintools/testing/test-cases/case-1";

async function renderChainEditor(
  permissions: UserPermissions = ALL_PERMISSIONS,
) {
  const result = renderEditor(CHAIN_EDITOR_PATH, permissions);
  await screen.findByLabelText("Name");
  return result;
}

beforeEach(() => {
  mockShowModal.mockClear();
  mockGetTestCase.mockResolvedValue(testCase());
  mockUpdateTestCase.mockImplementation((_id, request) =>
    Promise.resolve({ ...testCase(), ...request }),
  );
  mockGetElements.mockResolvedValue([trigger()]);
  mockGetChain.mockResolvedValue({
    id: "chain-1",
    name: "Order chain",
  } as never);
});

describe("TestCasePage sub-tab routing", () => {
  it("should redirect the editor index to the general tab", async () => {
    const { router } = await renderChainEditor();

    expect(router.state.location.pathname).toBe(`${CHAIN_EDITOR_PATH}/general`);
    expect(screen.getByLabelText("Name")).toHaveValue("First case");
  });

  it("should open the request tab when its tab is selected", async () => {
    const { router } = await renderChainEditor();

    fireEvent.click(screen.getByRole("tab", { name: "Request Parameters" }));

    await screen.findByTestId("query-parameters");
    expect(router.state.location.pathname).toBe(`${CHAIN_EDITOR_PATH}/request`);
  });

  it("should open the response validation tab when its tab is selected", async () => {
    await renderChainEditor();

    fireEvent.click(screen.getByText("Response Validation"));

    expect(await screen.findByText("body exists")).toBeInTheDocument();
    expect(screen.getByLabelText("Add matcher")).toBeInTheDocument();
  });
});

describe("TestCasePage general tab", () => {
  it("should offer the methods the trigger accepts", async () => {
    await renderChainEditor();

    const method = await screen.findByLabelText("Method");
    openSelect(method.closest(".ant-select") as HTMLElement);

    await waitFor(() => expect(querySelectOption("POST")).not.toBeNull());
    expect(querySelectOption("GET")).not.toBeNull();
    expect(querySelectOption("DELETE")).toBeNull();
  });

  it("should show the chain of a case opened outside a chain", async () => {
    renderEditor(ADMIN_EDITOR_PATH);
    await screen.findByLabelText("Name");

    expect(await screen.findByText("Order chain")).toBeInTheDocument();
  });

  it("should carry the trigger the case calls and how it is called", async () => {
    await renderChainEditor();

    expect(await screen.findByLabelText("Trigger")).toBeInTheDocument();
    expect(screen.getByLabelText("Method")).toBeInTheDocument();
    expect(screen.getByLabelText("Timeout, ms")).toHaveValue("120000");
  });
});

describe("TestCasePage request tab", () => {
  it("should edit the query parameters of the request", async () => {
    await renderChainEditor();
    fireEvent.click(screen.getByRole("tab", { name: "Request Parameters" }));

    const section = await screen.findByTestId("query-parameters");
    fireEvent.click(within(section).getByText("on"));
    const value = within(section).getByLabelText("Value");
    fireEvent.change(value, { target: { value: "off" } });
    fireEvent.keyDown(value, { key: "Enter", keyCode: 13 });

    // Committing the cell reaches the draft the editor holds, which is what
    // opens Save; the cell closes its editor and shows the value it was given.
    await waitFor(() =>
      expect(screen.getByTestId("test-case-save")).not.toBeDisabled(),
    );
    expect(within(section).getByText("off")).toBeInTheDocument();
  });

  // The parameters a case carries are worth seeing without a click; the ones it
  // has none of are worth a single line.
  it("should open a section that carries something and leave an empty one shut", async () => {
    await renderChainEditor();
    fireEvent.click(screen.getByRole("tab", { name: "Request Parameters" }));

    const filled = await screen.findByTestId("query-parameters");
    expect(within(filled).getByText("on")).toBeInTheDocument();

    const empty = screen.getByTestId("path-parameters");
    expect(within(empty).queryByText("Name")).toBeNull();
    fireEvent.click(within(empty).getByText("Path Parameters"));
    expect(await within(empty).findByText(/No entries/)).toBeInTheDocument();
  });
});

describe("TestCasePage save gating", () => {
  it("should keep Save disabled until something changes", async () => {
    await renderChainEditor();

    expect(screen.getByTestId("test-case-save")).toBeDisabled();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });

    expect(screen.getByTestId("test-case-save")).not.toBeDisabled();
  });

  it("should disable Save when the name is emptied", async () => {
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: " " } });

    expect(screen.getByTestId("test-case-save")).toBeDisabled();
  });

  it("should disable Save when the case names no trigger", async () => {
    mockGetTestCase.mockResolvedValue(
      testCase({ triggerReference: { chainId: "chain-1", elementId: "" } }),
    );
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });

    expect(screen.getByTestId("test-case-save")).toBeDisabled();
  });

  it("should disable Save when a rule added here is incomplete", async () => {
    await renderChainEditor();

    fireEvent.click(screen.getByText("Response Validation"));
    fireEvent.click(await screen.findByLabelText("Add matcher"));

    expect(screen.getByTestId("test-case-save")).toBeDisabled();
  });

  // The service lets an update keep a value the stored case already carries, so a
  // case written before the rules existed stays editable rather than being locked
  // out of its own editor.
  it("should enable Save when a rule the case was read with is incomplete", async () => {
    mockGetTestCase.mockResolvedValue(
      testCase({
        responseValidationRules: [
          {
            id: "rule-1",
            name: "",
            description: "",
            enabled: true,
            type: MatcherType.EXIST,
            entityType: MatcherEntityType.BODY,
            entityName: null,
            parameters: [],
          },
        ],
      }),
    );
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });

    expect(screen.getByTestId("test-case-save")).not.toBeDisabled();
  });
});

describe("TestCasePage saving", () => {
  it("should send the edited case and stay on the editor", async () => {
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: " Renamed " },
    });
    fireEvent.click(screen.getByTestId("test-case-save"));

    await waitFor(() => expect(mockUpdateTestCase).toHaveBeenCalledTimes(1));
    expect(mockUpdateTestCase).toHaveBeenCalledWith("case-1", {
      name: "Renamed",
      description: "First description",
      enabled: false,
      triggerReference: { chainId: "chain-1", elementId: "element-1" },
      requestSettings: {
        queryParameters: [{ name: "flag", value: "on" }],
        pathParameters: [],
        message: { body: "{}", headers: [] },
        method: "GET",
        timeout: 120000,
      },
      responseValidationRules: [
        expect.objectContaining({ name: "body exists" }),
      ],
    });
    // Saving is not leaving: the editor stays put and Save shuts itself.
    await waitFor(() =>
      expect(screen.getByTestId("test-case-save")).toBeDisabled(),
    );
    expect(screen.getByLabelText("Name")).toHaveValue("Renamed");
    expect(screen.queryByText("test cases list")).not.toBeInTheDocument();
    expect(mockShowModal).not.toHaveBeenCalled();
  });

  it("should stay on the editor when saving fails", async () => {
    mockUpdateTestCase.mockRejectedValue(new Error("boom"));
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });
    fireEvent.click(screen.getByTestId("test-case-save"));

    await waitFor(() =>
      expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
        "Failed to save the test case",
        expect.any(Error),
      ),
    );
    expect(screen.queryByText("test cases list")).not.toBeInTheDocument();
  });
});

describe("TestCasePage unsaved changes", () => {
  it("should leave without prompting when nothing changed", async () => {
    await renderChainEditor();

    leaveEditor();

    expect(await screen.findByText("test cases list")).toBeInTheDocument();
    expect(mockShowModal).not.toHaveBeenCalled();
  });

  it("should prompt before leaving with unsaved changes", async () => {
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });
    leaveEditor();

    await waitFor(() => expect(mockShowModal).toHaveBeenCalledTimes(1));
    expect(screen.queryByText("test cases list")).not.toBeInTheDocument();
  });

  it("should discard the changes when the prompt is answered yes", async () => {
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });
    leaveEditor();

    await waitFor(() => expect(mockShowModal).toHaveBeenCalledTimes(1));
    const modal = (
      mockShowModal.mock.calls[0][0] as { component: React.ReactElement }
    ).component as React.ReactElement<{ onNo: () => void }>;
    modal.props.onNo();

    expect(await screen.findByText("test cases list")).toBeInTheDocument();
    expect(mockUpdateTestCase).not.toHaveBeenCalled();
  });

  it("should switch sub-tabs without prompting while changes are pending", async () => {
    const { router } = await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });
    fireEvent.click(screen.getByRole("tab", { name: "Request Parameters" }));

    await screen.findByTestId("query-parameters");
    expect(router.state.location.pathname).toBe(`${CHAIN_EDITOR_PATH}/request`);
    expect(mockShowModal).not.toHaveBeenCalled();
  });

  it("should keep the pending changes when a sub-tab is switched", async () => {
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });
    fireEvent.click(screen.getByRole("tab", { name: "Request Parameters" }));
    await screen.findByTestId("query-parameters");
    fireEvent.click(screen.getByText("General"));

    expect(await screen.findByLabelText("Name")).toHaveValue("Renamed");
  });

  it("should save before leaving when the prompt is answered save", async () => {
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });
    leaveEditor();

    await waitFor(() => expect(mockShowModal).toHaveBeenCalledTimes(1));
    const modal = (
      mockShowModal.mock.calls[0][0] as { component: React.ReactElement }
    ).component as React.ReactElement<{ onYes: () => void }>;
    modal.props.onYes();

    await waitFor(() => expect(mockUpdateTestCase).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("test cases list")).toBeInTheDocument();
  });
});

describe("TestCasePage entity lifetime", () => {
  const OTHER_EDITOR_PATH = "/chains/chain-1/testing/test-cases/case-2";

  it("should read the case the route names when the id changes", async () => {
    const { router } = await renderChainEditor();
    mockGetTestCase.mockResolvedValue(
      testCase({ id: "case-2", name: "Second case" }),
    );

    await router.navigate(OTHER_EDITOR_PATH);

    await waitFor(() =>
      expect(screen.getByLabelText("Name")).toHaveValue("Second case"),
    );
  });

  // The editor would otherwise keep the case it holds and let Save write it back
  // under the id the address now names.
  it("should drop the case on screen when another id fails to load", async () => {
    const { router } = await renderChainEditor();
    mockGetTestCase.mockRejectedValue(new Error("no connection"));

    await router.navigate(OTHER_EDITOR_PATH);

    expect(await screen.findByText("Test case not found")).toBeInTheDocument();
    expect(screen.queryByLabelText("Name")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-case-save")).not.toBeInTheDocument();
  });
});

describe("TestCasePage read-only mode", () => {
  it("should hide the toolbar and disable the fields outside a chain", async () => {
    renderEditor(ADMIN_EDITOR_PATH);
    await screen.findByLabelText("Name");

    expect(screen.queryByTestId("test-case-save")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Name")).toBeDisabled();
    expect(screen.getByLabelText("Description")).toBeDisabled();
  });

  // The tab hangs off one `disabled={readonly}` on its form, so the trigger
  // fields are checked rather than assumed to follow the name beside them.
  it("should disable the trigger fields outside a chain", async () => {
    renderEditor(ADMIN_EDITOR_PATH);
    await screen.findByLabelText("Name");

    expect(await screen.findByLabelText("Trigger")).toBeDisabled();
    expect(screen.getByLabelText("Method")).toBeDisabled();
    expect(screen.getByLabelText("Timeout, ms")).toBeDisabled();
  });

  it("should render the request parameter tables read-only outside a chain", async () => {
    renderEditor(ADMIN_EDITOR_PATH);
    await screen.findByLabelText("Name");

    fireEvent.click(screen.getByRole("tab", { name: "Request Parameters" }));

    const headers = await screen.findByTestId("headers");
    expect(within(headers).queryByLabelText("Name")).not.toBeInTheDocument();
    expect(within(headers).queryByLabelText("Delete")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Add" }),
    ).not.toBeInTheDocument();
  });

  it("should render the matchers table read-only outside a chain", async () => {
    renderEditor(ADMIN_EDITOR_PATH);
    await screen.findByLabelText("Name");

    fireEvent.click(screen.getByText("Response Validation"));

    expect(await screen.findByText("body exists")).toBeInTheDocument();
    expect(screen.queryByLabelText("Add matcher")).not.toBeInTheDocument();
  });

  it("should leave without prompting outside a chain", async () => {
    const { router } = renderEditor(ADMIN_EDITOR_PATH);
    await screen.findByLabelText("Name");

    await router.navigate("/admintools/testing/test-cases");

    expect(await screen.findByText("test cases list")).toBeInTheDocument();
    expect(mockShowModal).not.toHaveBeenCalled();
  });
});

describe("TestCasePage permission gating", () => {
  it("should hide the save actions without the update right", async () => {
    await renderChainEditor({ chain: ["read"] });

    expect(screen.queryByTestId("test-case-save")).not.toBeInTheDocument();
  });
});
