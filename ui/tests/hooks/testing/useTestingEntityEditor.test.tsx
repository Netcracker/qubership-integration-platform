/**
 * @jest-environment jsdom
 */
import React from "react";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import "@testing-library/jest-dom";
import { Breadcrumb } from "antd";
import { createMemoryRouter, RouterProvider, useParams } from "react-router";
import {
  TestingEntityEditorConfig,
  useTestingEntityEditor,
} from "../../../src/hooks/testing/useTestingEntityEditor.tsx";
import { UserPermissionsContext } from "../../../src/permissions/UserPermissionsContext.tsx";
import type { UserPermissions } from "../../../src/permissions/types.ts";
import { installDataRouterGlobals } from "../../helpers/dataRouterGlobals.ts";
import { ChainHeaderActionsTestSlot } from "../../helpers/renderWithChainHeader.tsx";

installDataRouterGlobals();

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

/** A stand-in entity, so the hook is exercised without either real editor. */
type Widget = { id: string; name: string };

const getWidget = jest.fn<Promise<Widget>, [string]>();
const updateWidget = jest.fn<Promise<Widget>, [string, Widget]>();

const WIDGET_EDITOR: TestingEntityEditorConfig<Widget, Widget> = {
  listSegment: "widgets",
  tabs: [
    { key: "general", label: "General" },
    { key: "details", label: "Details" },
  ],
  nouns: { singular: "widget", listTitle: "Widgets" },
  saveTestId: "widget-save",
  get: (id) => getWidget(id),
  update: (id, request) => updateWidget(id, request),
  toRequest: (widget) => widget,
  violations: () => [],
  isValid: (widget) => widget.name.trim().length > 0,
};

const WidgetEditor: React.FC = () => {
  const { chainId, widgetId } = useParams<{
    chainId?: string;
    widgetId: string;
  }>();
  const { entity, loading, onChange, breadcrumbItems } = useTestingEntityEditor(
    { ...WIDGET_EDITOR, chainId, entityId: widgetId },
  );

  if (loading) {
    return <div>loading</div>;
  }
  if (!entity) {
    return <div>widget not found</div>;
  }
  return (
    <div>
      <Breadcrumb items={breadcrumbItems} />
      <div data-testid="widget-name">{entity.name}</div>
      <button onClick={() => onChange({ name: `${entity.name}!` })}>
        rename
      </button>
    </div>
  );
};

const EDITOR_PATH = "/chains/chain-1/testing/widgets/widget-1";

function renderEditor(path: string = EDITOR_PATH) {
  const router = createMemoryRouter(
    [
      {
        path: "/chains/:chainId/testing/widgets",
        children: [
          { index: true, element: <div>widgets list</div> },
          { path: ":widgetId", element: <WidgetEditor /> },
        ],
      },
      { path: "/admintools/testing/widgets", element: <div>widgets list</div> },
    ],
    { initialEntries: [path] },
  );
  const utils = render(
    <UserPermissionsContext.Provider value={ALL_PERMISSIONS}>
      <ChainHeaderActionsTestSlot>
        <RouterProvider router={router} />
      </ChainHeaderActionsTestSlot>
    </UserPermissionsContext.Provider>,
  );
  return { ...utils, router };
}

async function renderLoadedEditor() {
  const result = renderEditor();
  await screen.findByTestId("widget-name");
  return result;
}

/** A user leaves the editor by the breadcrumb link back to the list. */
function leaveEditor() {
  fireEvent.click(screen.getByText("Widgets"));
}

beforeEach(() => {
  mockShowModal.mockClear();
  getWidget.mockResolvedValue({ id: "widget-1", name: "First widget" });
  updateWidget.mockImplementation((id, request) =>
    Promise.resolve({ ...request, id }),
  );
});

function promptProps(): {
  onYes: () => void;
  onNo: () => void;
  onCancelQuestion: () => void;
} {
  const { component } = mockShowModal.mock.calls[0][0] as {
    component: React.ReactElement;
  };
  return (
    component as React.ReactElement<{
      onYes: () => void;
      onNo: () => void;
      onCancelQuestion: () => void;
    }>
  ).props;
}

describe("useTestingEntityEditor", () => {
  it("should read the entity the route names when the editor opens", async () => {
    await renderLoadedEditor();

    expect(getWidget).toHaveBeenCalledWith("widget-1");
    expect(screen.getByTestId("widget-name")).toHaveTextContent("First widget");
  });

  it("should leave without prompting when nothing changed", async () => {
    await renderLoadedEditor();

    leaveEditor();

    expect(await screen.findByText("widgets list")).toBeInTheDocument();
    expect(mockShowModal).not.toHaveBeenCalled();
  });

  it("should prompt before leaving with unsaved changes", async () => {
    await renderLoadedEditor();

    fireEvent.click(screen.getByText("rename"));
    leaveEditor();

    await waitFor(() => expect(mockShowModal).toHaveBeenCalledTimes(1));
    expect(screen.queryByText("widgets list")).not.toBeInTheDocument();
  });

  // Without the guard, every render while the navigation is held stacks another
  // copy of the same question on screen.
  it("should prompt once when the editor re-renders while the navigation stays blocked", async () => {
    await renderLoadedEditor();

    fireEvent.click(screen.getByText("rename"));
    leaveEditor();
    await waitFor(() => expect(mockShowModal).toHaveBeenCalledTimes(1));

    // Both re-run the prompt effect: the draft changes, then the blocked
    // navigation is attempted again.
    fireEvent.click(screen.getByText("rename"));
    leaveEditor();

    await waitFor(() =>
      expect(screen.getByTestId("widget-name")).toHaveTextContent(
        "First widget!!",
      ),
    );
    expect(mockShowModal).toHaveBeenCalledTimes(1);
  });

  it("should save and continue to the requested page when the prompt is answered save", async () => {
    await renderLoadedEditor();

    fireEvent.click(screen.getByText("rename"));
    leaveEditor();
    await waitFor(() => expect(mockShowModal).toHaveBeenCalledTimes(1));

    promptProps().onYes();

    await waitFor(() => expect(updateWidget).toHaveBeenCalledTimes(1));
    expect(updateWidget).toHaveBeenCalledWith("widget-1", {
      id: "widget-1",
      name: "First widget!",
    });
    expect(await screen.findByText("widgets list")).toBeInTheDocument();
  });

  it("should stay on the editor when the save behind the prompt fails", async () => {
    updateWidget.mockRejectedValue(new Error("boom"));
    await renderLoadedEditor();

    fireEvent.click(screen.getByText("rename"));
    leaveEditor();
    await waitFor(() => expect(mockShowModal).toHaveBeenCalledTimes(1));

    promptProps().onYes();

    await waitFor(() =>
      expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
        "Failed to save the widget",
        expect.any(Error),
      ),
    );
    expect(screen.queryByText("widgets list")).not.toBeInTheDocument();
  });

  it("should discard the changes when the prompt is answered no", async () => {
    await renderLoadedEditor();

    fireEvent.click(screen.getByText("rename"));
    leaveEditor();
    await waitFor(() => expect(mockShowModal).toHaveBeenCalledTimes(1));

    promptProps().onNo();

    expect(await screen.findByText("widgets list")).toBeInTheDocument();
    expect(updateWidget).not.toHaveBeenCalled();
  });

  it("should stay on the editor with the changes intact when the prompt is dismissed", async () => {
    await renderLoadedEditor();

    fireEvent.click(screen.getByText("rename"));
    leaveEditor();
    await waitFor(() => expect(mockShowModal).toHaveBeenCalledTimes(1));

    promptProps().onCancelQuestion();
    // Give the navigation the chance to complete before asserting none did.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    expect(screen.queryByText("widgets list")).not.toBeInTheDocument();
    expect(screen.getByTestId("widget-name")).toHaveTextContent(
      "First widget!",
    );
    expect(updateWidget).not.toHaveBeenCalled();
  });

  it("should register a Save button the draft opens and a save shuts", async () => {
    await renderLoadedEditor();

    expect(screen.getByTestId("widget-save")).toBeDisabled();

    fireEvent.click(screen.getByText("rename"));
    expect(screen.getByTestId("widget-save")).not.toBeDisabled();

    fireEvent.click(screen.getByTestId("widget-save"));

    await waitFor(() => expect(updateWidget).toHaveBeenCalledTimes(1));
    await waitFor(() =>
      expect(screen.getByTestId("widget-save")).toBeDisabled(),
    );
  });

  it("should hide the Save button and leave without prompting outside a chain", async () => {
    const router = createMemoryRouter(
      [
        {
          path: "/admintools/testing/widgets",
          children: [
            { index: true, element: <div>widgets list</div> },
            { path: ":widgetId", element: <WidgetEditor /> },
          ],
        },
      ],
      { initialEntries: ["/admintools/testing/widgets/widget-1"] },
    );
    render(
      <UserPermissionsContext.Provider value={ALL_PERMISSIONS}>
        <ChainHeaderActionsTestSlot>
          <RouterProvider router={router} />
        </ChainHeaderActionsTestSlot>
      </UserPermissionsContext.Provider>,
    );
    await screen.findByTestId("widget-name");

    expect(screen.queryByTestId("widget-save")).not.toBeInTheDocument();

    fireEvent.click(screen.getByText("rename"));
    leaveEditor();

    expect(await screen.findByText("widgets list")).toBeInTheDocument();
    expect(mockShowModal).not.toHaveBeenCalled();
  });

  it("should report a failed read and leave the editor empty", async () => {
    getWidget.mockRejectedValue(new Error("no connection"));

    renderEditor();

    expect(await screen.findByText("widget not found")).toBeInTheDocument();
    expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
      "Failed to load the widget",
      expect.any(Error),
    );
  });
});
