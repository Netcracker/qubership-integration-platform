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
  EndpointMock,
  MatcherEntityType,
  MatcherType,
} from "../../../src/api/apiTypes.ts";
import { api } from "../../../src/api/api.ts";
import { EndpointMockPage } from "../../../src/pages/testing/EndpointMockPage.tsx";
import { EndpointMockGeneralTab } from "../../../src/components/testing/endpointMock/EndpointMockGeneralTab.tsx";
import { EndpointMockResponseTab } from "../../../src/components/testing/endpointMock/EndpointMockResponseTab.tsx";
import { EndpointMockRequestMatchersTab } from "../../../src/components/testing/endpointMock/EndpointMockRequestMatchersTab.tsx";
import { UserPermissionsContext } from "../../../src/permissions/UserPermissionsContext.tsx";
import type { UserPermissions } from "../../../src/permissions/types.ts";
import { openSelect, querySelectOption } from "../../helpers/antdSelect.ts";
import { installDataRouterGlobals } from "../../helpers/dataRouterGlobals.ts";
import { ChainHeaderActionsTestSlot } from "../../helpers/renderWithChainHeader.tsx";

installDataRouterGlobals();

jest.mock("../../../src/api/api.ts", () => ({
  api: {
    getEndpointMock: jest.fn(),
    updateEndpointMock: jest.fn(),
    getElements: jest.fn(),
    getChain: jest.fn(),
  },
}));

const mockGetEndpointMock = jest.spyOn(api, "getEndpointMock");
const mockUpdateEndpointMock = jest.spyOn(api, "updateEndpointMock");
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

function endpointMock(overrides: Partial<EndpointMock> = {}): EndpointMock {
  return {
    id: "mock-1",
    name: "First mock",
    description: "First description",
    enabled: true,
    endpointReference: { chainId: "chain-1", elementId: "element-1" },
    responseSettings: {
      message: { body: "{}", headers: [{ name: "Accept", value: "text/csv" }] },
      status: 200,
      delay: 0,
    },
    requestMatchers: [
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
    createdBy: "author",
    createdAt: "2026-08-13T10:00:00.000Z",
    updatedBy: null,
    updatedAt: null,
    ...overrides,
  };
}

function sender(overrides: Partial<Element> = {}): Element {
  return {
    id: "element-1",
    name: "Outgoing call",
    description: "",
    chainId: "chain-1",
    type: "http-sender",
    properties: {} as never,
    mandatoryChecksPassed: true,
    ...overrides,
  };
}

function editorRoutes(): RouteObject[] {
  return [
    { index: true, element: <div>endpoint mocks list</div> },
    {
      path: ":endpointMockId",
      element: <EndpointMockPage />,
      children: [
        { index: true, element: <Navigate to="general" replace /> },
        { path: "general", element: <EndpointMockGeneralTab /> },
        { path: "response", element: <EndpointMockResponseTab /> },
        {
          path: "request-matchers",
          element: <EndpointMockRequestMatchersTab />,
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
        path: "/chains/:chainId/testing/endpoint-mocks",
        children: editorRoutes(),
      },
      { path: "/admintools/testing/endpoint-mocks", children: editorRoutes() },
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
  fireEvent.click(screen.getByText("Endpoint Mocks"));
}

const CHAIN_EDITOR_PATH = "/chains/chain-1/testing/endpoint-mocks/mock-1";
const ADMIN_EDITOR_PATH = "/admintools/testing/endpoint-mocks/mock-1";

async function renderChainEditor(
  permissions: UserPermissions = ALL_PERMISSIONS,
) {
  const result = renderEditor(CHAIN_EDITOR_PATH, permissions);
  await screen.findByLabelText("Name");
  return result;
}

beforeEach(() => {
  mockShowModal.mockClear();
  mockGetEndpointMock.mockResolvedValue(endpointMock());
  mockUpdateEndpointMock.mockImplementation((_id, request) =>
    Promise.resolve({ ...endpointMock(), ...request }),
  );
  mockGetElements.mockResolvedValue([sender()]);
  mockGetChain.mockResolvedValue({
    id: "chain-1",
    name: "Order chain",
  } as never);
});

describe("EndpointMockPage sub-tab routing", () => {
  it("should redirect the editor index to the general tab", async () => {
    const { router } = await renderChainEditor();

    expect(router.state.location.pathname).toBe(`${CHAIN_EDITOR_PATH}/general`);
    expect(screen.getByLabelText("Name")).toHaveValue("First mock");
  });

  it("should open the response tab when its tab is selected", async () => {
    const { router } = await renderChainEditor();

    fireEvent.click(screen.getByRole("tab", { name: "Response Parameters" }));

    await screen.findByTestId("response-headers");
    expect(router.state.location.pathname).toBe(
      `${CHAIN_EDITOR_PATH}/response`,
    );
  });

  it("should open the request matchers tab when its tab is selected", async () => {
    await renderChainEditor();

    fireEvent.click(screen.getByText("Request Matchers"));

    expect(await screen.findByText("body exists")).toBeInTheDocument();
    expect(screen.getByLabelText("Add matcher")).toBeInTheDocument();
  });
});

describe("EndpointMockPage general tab", () => {
  it("should offer the HTTP endpoints of the chain", async () => {
    mockGetElements.mockResolvedValue([
      sender(),
      sender({ id: "element-2", name: "Mapper", type: "mapper" }),
    ]);
    await renderChainEditor();

    const endpoint = await screen.findByLabelText("Endpoint");
    openSelect(endpoint.closest(".ant-select") as HTMLElement);

    await waitFor(() =>
      expect(querySelectOption("Outgoing call")).not.toBeNull(),
    );
    expect(querySelectOption("Mapper")).toBeNull();
  });

  it("should show the chain of a mock opened outside a chain", async () => {
    renderEditor(ADMIN_EDITOR_PATH);

    expect(await screen.findByText("Order chain")).toBeInTheDocument();
  });

  it("should carry the status and the delay the mock answers with", async () => {
    await renderChainEditor();

    expect(await screen.findByLabelText("Status Code")).toHaveValue("200");
    expect(screen.getByLabelText("Delay, ms")).toHaveValue("0");
  });
});

describe("EndpointMockPage response tab", () => {
  it("should edit the response headers", async () => {
    await renderChainEditor();
    fireEvent.click(screen.getByRole("tab", { name: "Response Parameters" }));

    const section = await screen.findByTestId("response-headers");
    fireEvent.click(within(section).getByText("text/csv"));
    const value = within(section).getByLabelText("Value");
    fireEvent.change(value, { target: { value: "application/json" } });
    fireEvent.keyDown(value, { key: "Enter", keyCode: 13 });

    // Committing the cell reaches the draft the editor holds, which is what
    // opens Save; the cell closes its editor and shows the value it was given.
    await waitFor(() =>
      expect(screen.getByTestId("endpoint-mock-save")).not.toBeDisabled(),
    );
    expect(within(section).getByText("application/json")).toBeInTheDocument();
  });

  // The service refuses a status it cannot answer with, and the message says so
  // where the value is typed rather than only through the disabled Save button.
  it("should report a status the service cannot answer with", async () => {
    await renderChainEditor();
    const status = await screen.findByLabelText("Status Code");
    fireEvent.change(status, { target: { value: "42" } });
    fireEvent.blur(status);

    expect(status).toHaveValue("42");
    expect(
      screen.getByText(/A response status is a whole number/),
    ).toBeInTheDocument();
  });

  // A bounded field clamps on blur, which would rewrite a status stored before
  // the range was enforced into one the mock never named.
  it("should keep a stored status outside that range as it stands", async () => {
    mockGetEndpointMock.mockResolvedValue(
      endpointMock({
        responseSettings: { message: null, status: 42, delay: 0 },
      }),
    );
    await renderChainEditor();
    const status = await screen.findByLabelText("Status Code");
    fireEvent.focus(status);
    fireEvent.blur(status);

    expect(screen.getByLabelText("Status Code")).toHaveValue("42");
  });

  // A stored zero reads as "unset" and the mock then answers 200, so clearing the
  // field must not quietly turn a 404 mock into a 200 one.
  it("should keep the previous status when the field is cleared", async () => {
    await renderChainEditor();
    const status = await screen.findByLabelText("Status Code");
    fireEvent.change(status, { target: { value: "" } });
    fireEvent.blur(status);

    expect(screen.getByLabelText("Status Code")).not.toHaveValue("0");
  });

  it("should refuse a response header name that is not an HTTP field name", async () => {
    await renderChainEditor();
    fireEvent.click(screen.getByRole("tab", { name: "Response Parameters" }));

    const section = await screen.findByTestId("response-headers");
    fireEvent.click(within(section).getByText("Accept"));
    const name = within(section).getByLabelText("Name");
    fireEvent.change(name, { target: { value: "Content Type" } });
    fireEvent.keyDown(name, { key: "Enter", keyCode: 13 });

    expect(
      await within(section).findByText(/A header name may carry/),
    ).toBeInTheDocument();
    // The cell keeps the name to itself, so the mock is still what it was read as.
    expect(screen.getByTestId("endpoint-mock-save")).toBeDisabled();
  });
});

describe("EndpointMockPage save gating", () => {
  it("should keep Save disabled until something changes", async () => {
    await renderChainEditor();

    expect(screen.getByTestId("endpoint-mock-save")).toBeDisabled();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });

    expect(screen.getByTestId("endpoint-mock-save")).not.toBeDisabled();
  });

  it("should disable Save when the name is emptied", async () => {
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: " " } });

    expect(screen.getByTestId("endpoint-mock-save")).toBeDisabled();
  });

  it("should disable Save when the mock names no endpoint", async () => {
    mockGetEndpointMock.mockResolvedValue(
      endpointMock({
        endpointReference: { chainId: "chain-1", elementId: "" },
      }),
    );
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });

    expect(screen.getByTestId("endpoint-mock-save")).toBeDisabled();
  });

  it("should enable Save without a method, which a mock has none of", async () => {
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Description"), {
      target: { value: "Reworded" },
    });

    expect(screen.getByTestId("endpoint-mock-save")).not.toBeDisabled();
  });

  it("should disable Save when a matcher added here is incomplete", async () => {
    await renderChainEditor();

    fireEvent.click(screen.getByText("Request Matchers"));
    fireEvent.click(await screen.findByLabelText("Add matcher"));

    expect(screen.getByTestId("endpoint-mock-save")).toBeDisabled();
  });

  // The service lets an update keep a value the stored mock already carries, so a
  // mock written before the rules existed stays editable rather than being locked
  // out of its own editor.
  it("should enable Save when a matcher the mock was read with is incomplete", async () => {
    mockGetEndpointMock.mockResolvedValue(
      endpointMock({
        requestMatchers: [
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

    expect(screen.getByTestId("endpoint-mock-save")).not.toBeDisabled();
  });

  it("should enable Save when a response header the mock was read with is not an HTTP field name", async () => {
    mockGetEndpointMock.mockResolvedValue(
      endpointMock({
        responseSettings: {
          message: {
            body: "{}",
            headers: [{ name: "Content Type", value: "text/csv" }],
          },
          status: 200,
          delay: 0,
        },
      }),
    );
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });

    expect(screen.getByTestId("endpoint-mock-save")).not.toBeDisabled();
  });

  it("should disable Save when the status is set outside the range the service answers with", async () => {
    await renderChainEditor();

    const status = await screen.findByLabelText("Status Code");
    fireEvent.change(status, { target: { value: "42" } });
    fireEvent.blur(status);

    expect(screen.getByTestId("endpoint-mock-save")).toBeDisabled();
  });

  it("should enable Save when the mock was read with a status outside that range", async () => {
    mockGetEndpointMock.mockResolvedValue(
      endpointMock({
        responseSettings: { message: null, status: 42, delay: 0 },
      }),
    );
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });

    expect(screen.getByTestId("endpoint-mock-save")).not.toBeDisabled();
  });

  // Leniency covers the value that is already stored, not the field carrying it:
  // the service reads a replacement as a violation the caller introduced.
  it("should disable Save when a stored bad status is replaced with another", async () => {
    mockGetEndpointMock.mockResolvedValue(
      endpointMock({
        responseSettings: { message: null, status: 42, delay: 0 },
      }),
    );
    await renderChainEditor();

    const status = await screen.findByLabelText("Status Code");
    fireEvent.change(status, { target: { value: "99" } });
    fireEvent.blur(status);

    expect(screen.getByTestId("endpoint-mock-save")).toBeDisabled();
  });

  // A stored header the service already tolerates keeps Save open, and the cell
  // says what is wrong with it without waiting for the name to be touched.
  it("should name what is wrong with a stored bad header without shutting Save", async () => {
    mockGetEndpointMock.mockResolvedValue(
      endpointMock({
        responseSettings: {
          message: {
            body: "{}",
            headers: [{ name: "Content Type", value: "text/csv" }],
          },
          status: 200,
          delay: 0,
        },
      }),
    );
    await renderChainEditor();
    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });

    fireEvent.click(screen.getByRole("tab", { name: "Response Parameters" }));
    const section = await screen.findByTestId("response-headers");

    expect(
      within(section).getByText(/A header name may carry/),
    ).toBeInTheDocument();
    expect(screen.getByTestId("endpoint-mock-save")).not.toBeDisabled();
  });
});

describe("EndpointMockPage saving", () => {
  it("should send the edited mock and stay on the editor", async () => {
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: " Renamed " },
    });
    fireEvent.click(screen.getByTestId("endpoint-mock-save"));

    await waitFor(() =>
      expect(mockUpdateEndpointMock).toHaveBeenCalledTimes(1),
    );
    expect(mockUpdateEndpointMock).toHaveBeenCalledWith("mock-1", {
      name: "Renamed",
      description: "First description",
      enabled: true,
      endpointReference: { chainId: "chain-1", elementId: "element-1" },
      responseSettings: {
        message: {
          body: "{}",
          headers: [{ name: "Accept", value: "text/csv" }],
        },
        status: 200,
        delay: 0,
      },
      requestMatchers: [expect.objectContaining({ name: "body exists" })],
    });
    // Saving is not leaving: the editor stays put and Save shuts itself.
    await waitFor(() =>
      expect(screen.getByTestId("endpoint-mock-save")).toBeDisabled(),
    );
    expect(screen.getByLabelText("Name")).toHaveValue("Renamed");
    expect(screen.queryByText("endpoint mocks list")).not.toBeInTheDocument();
    expect(mockShowModal).not.toHaveBeenCalled();
  });

  it("should stay on the editor when saving fails", async () => {
    mockUpdateEndpointMock.mockRejectedValue(new Error("boom"));
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });
    fireEvent.click(screen.getByTestId("endpoint-mock-save"));

    await waitFor(() =>
      expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
        "Failed to save the endpoint mock",
        expect.any(Error),
      ),
    );
    expect(screen.queryByText("endpoint mocks list")).not.toBeInTheDocument();
  });
});

describe("EndpointMockPage unsaved changes", () => {
  it("should leave without prompting when nothing changed", async () => {
    await renderChainEditor();

    leaveEditor();

    expect(await screen.findByText("endpoint mocks list")).toBeInTheDocument();
    expect(mockShowModal).not.toHaveBeenCalled();
  });

  it("should prompt before leaving with unsaved changes", async () => {
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });
    leaveEditor();

    await waitFor(() => expect(mockShowModal).toHaveBeenCalledTimes(1));
    expect(screen.queryByText("endpoint mocks list")).not.toBeInTheDocument();
  });

  it("should discard the changes when the prompt is answered no", async () => {
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

    expect(await screen.findByText("endpoint mocks list")).toBeInTheDocument();
    expect(mockUpdateEndpointMock).not.toHaveBeenCalled();
  });

  it("should switch sub-tabs without prompting while changes are pending", async () => {
    const { router } = await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });
    fireEvent.click(screen.getByRole("tab", { name: "Response Parameters" }));

    await screen.findByTestId("response-headers");
    expect(router.state.location.pathname).toBe(
      `${CHAIN_EDITOR_PATH}/response`,
    );
    expect(mockShowModal).not.toHaveBeenCalled();
  });

  it("should keep the pending changes when a sub-tab is switched", async () => {
    await renderChainEditor();

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Renamed" },
    });
    fireEvent.click(screen.getByRole("tab", { name: "Response Parameters" }));
    await screen.findByTestId("response-headers");
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

    await waitFor(() =>
      expect(mockUpdateEndpointMock).toHaveBeenCalledTimes(1),
    );
    expect(await screen.findByText("endpoint mocks list")).toBeInTheDocument();
  });
});

describe("EndpointMockPage entity lifetime", () => {
  const OTHER_EDITOR_PATH = "/chains/chain-1/testing/endpoint-mocks/mock-2";

  it("should read the mock the route names when the id changes", async () => {
    const { router } = await renderChainEditor();
    mockGetEndpointMock.mockResolvedValue(
      endpointMock({ id: "mock-2", name: "Second mock" }),
    );

    await router.navigate(OTHER_EDITOR_PATH);

    await waitFor(() =>
      expect(screen.getByLabelText("Name")).toHaveValue("Second mock"),
    );
  });

  // The editor would otherwise keep the mock it holds and let Save write it back
  // under the id the address now names.
  it("should drop the mock on screen when another id fails to load", async () => {
    const { router } = await renderChainEditor();
    mockGetEndpointMock.mockRejectedValue(new Error("no connection"));

    await router.navigate(OTHER_EDITOR_PATH);

    expect(
      await screen.findByText("Endpoint mock not found"),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText("Name")).not.toBeInTheDocument();
    expect(screen.queryByTestId("endpoint-mock-save")).not.toBeInTheDocument();
  });
});

describe("EndpointMockPage read-only mode", () => {
  it("should hide the toolbar and disable the fields outside a chain", async () => {
    renderEditor(ADMIN_EDITOR_PATH);
    await screen.findByLabelText("Name");

    expect(screen.queryByTestId("endpoint-mock-save")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Name")).toBeDisabled();
    expect(screen.getByLabelText("Description")).toBeDisabled();
  });

  // The General tab hangs off one `disabled={readonly}` on its form, so the
  // response settings are checked rather than assumed to follow the name.
  it("should disable the response settings outside a chain", async () => {
    renderEditor(ADMIN_EDITOR_PATH);
    await screen.findByLabelText("Name");

    expect(await screen.findByLabelText("Status Code")).toBeDisabled();
    expect(screen.getByLabelText("Delay, ms")).toBeDisabled();
  });

  it("should offer no way to edit the response headers outside a chain", async () => {
    renderEditor(ADMIN_EDITOR_PATH);
    await screen.findByLabelText("Name");

    fireEvent.click(screen.getByRole("tab", { name: "Response Parameters" }));

    const section = await screen.findByTestId("response-headers");
    expect(within(section).queryByLabelText("Delete")).not.toBeInTheDocument();
    expect(within(section).queryByText("Add header")).not.toBeInTheDocument();
  });

  it("should render the matchers table read-only outside a chain", async () => {
    renderEditor(ADMIN_EDITOR_PATH);
    await screen.findByLabelText("Name");

    fireEvent.click(screen.getByText("Request Matchers"));

    expect(await screen.findByText("body exists")).toBeInTheDocument();
    expect(screen.queryByLabelText("Add matcher")).not.toBeInTheDocument();
  });

  it("should leave without prompting outside a chain", async () => {
    const { router } = renderEditor(ADMIN_EDITOR_PATH);
    await screen.findByLabelText("Name");

    await router.navigate("/admintools/testing/endpoint-mocks");

    expect(await screen.findByText("endpoint mocks list")).toBeInTheDocument();
    expect(mockShowModal).not.toHaveBeenCalled();
  });
});

describe("EndpointMockPage permission gating", () => {
  it("should hide the save actions without the update right", async () => {
    await renderChainEditor({ chain: ["read"] });

    expect(screen.queryByTestId("endpoint-mock-save")).not.toBeInTheDocument();
  });
});
