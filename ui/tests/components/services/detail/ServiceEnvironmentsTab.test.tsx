/**
 * @jest-environment jsdom
 */
import React, { useCallback, useState } from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { MemoryRouter } from "react-router-dom";
import {
  Environment,
  EnvironmentRequest,
  EnvironmentSourceType,
  IntegrationSystem,
  IntegrationSystemType,
} from "../../../../src/api/apiTypes";
import { UserPermissionsContext } from "../../../../src/permissions/UserPermissionsContext.tsx";
import { getAllPermissions } from "../../../../src/permissions/funcs.ts";
import {
  ServiceContext,
  ServiceParametersToolbarContext,
} from "../../../../src/components/services/detail/ServiceParametersPage";
import { ServiceEnvironmentsTab } from "../../../../src/components/services/detail/ServiceEnvironmentsTab";

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

const mockGetEnvironments = jest.fn();
const mockUpdateEnvironment = jest.fn();
const mockCreateEnvironment = jest.fn();
const mockShowModal = jest.fn();
const mockRequestFailed = jest.fn();
// A new object on each call would rebuild the toolbar on every render.
const mockNotificationService = { requestFailed: mockRequestFailed };

jest.mock("../../../../src/api/api", () => ({
  api: {
    getEnvironments: (...args: unknown[]) => mockGetEnvironments(...args),
    updateEnvironment: (...args: unknown[]) => mockUpdateEnvironment(...args),
    createEnvironment: (...args: unknown[]) => mockCreateEnvironment(...args),
  },
}));

jest.mock("../../../../src/Modals.tsx", () => ({
  useModalsContext: () => ({ showModal: mockShowModal }),
}));

jest.mock("../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => mockNotificationService,
}));

const ENVIRONMENT: Environment = {
  id: "env-1",
  systemId: "svc-1",
  name: "Env 1",
  address: "localhost",
  labels: [],
  sourceType: EnvironmentSourceType.MANUAL,
  properties: {},
};

const REQUEST: EnvironmentRequest = {
  name: "Env 1",
  address: "localhost",
  labels: [],
  sourceType: EnvironmentSourceType.MANUAL,
  properties: {},
};

type ModalProps = {
  action: "create" | "edit";
  protocol: string | undefined;
  environment: Environment;
  onSubmit: (request: EnvironmentRequest) => Promise<void>;
};

const ToolbarHost: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [toolbar, setToolbarNode] = useState<React.ReactNode>(null);
  const setToolbar = useCallback(
    (_owner: string, node: React.ReactNode) => setToolbarNode(node),
    [],
  );
  return (
    <ServiceParametersToolbarContext.Provider value={{ setToolbar }}>
      {toolbar}
      {children}
    </ServiceParametersToolbarContext.Provider>
  );
};

function renderTab() {
  render(
    <MemoryRouter initialEntries={["/services/svc-1/environments"]}>
      <UserPermissionsContext.Provider value={getAllPermissions()}>
        <ServiceContext.Provider
          value={
            {
              id: "svc-1",
              protocol: "http",
              type: IntegrationSystemType.EXTERNAL,
            } as IntegrationSystem
          }
        >
          <ToolbarHost>
            <ServiceEnvironmentsTab formatTimestamp={String} />
          </ToolbarHost>
        </ServiceContext.Provider>
      </UserPermissionsContext.Provider>
    </MemoryRouter>,
  );
}

async function openModal(buttonName: string): Promise<ModalProps> {
  renderTab();
  fireEvent.click(await screen.findByRole("button", { name: buttonName }));
  const { component } = mockShowModal.mock.calls[0][0] as {
    component: React.ReactElement<ModalProps>;
  };
  return component.props;
}

describe("ServiceEnvironmentsTab", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockGetEnvironments.mockResolvedValue([ENVIRONMENT]);
  });

  it("should reject the modal submit when the update request fails", async () => {
    const error = new Error("boom");
    mockUpdateEnvironment.mockRejectedValue(error);
    const { onSubmit } = await openModal("Env 1");

    await expect(onSubmit(REQUEST)).rejects.toBe(error);
    expect(mockRequestFailed).toHaveBeenCalledWith("boom", error);
  });

  it("should resolve the modal submit and show the updated row when the update succeeds", async () => {
    mockUpdateEnvironment.mockResolvedValue({ ...ENVIRONMENT, name: "Env 2" });
    const { onSubmit } = await openModal("Env 1");

    await expect(onSubmit(REQUEST)).resolves.toBeUndefined();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Env 2" })).toBeInTheDocument(),
    );
    expect(mockUpdateEnvironment).toHaveBeenCalledWith(
      "svc-1",
      "env-1",
      REQUEST,
    );
  });

  // The modal renders outside ServiceContext, so the tab passes the protocol.
  it.each(["Env 1", "Add Environment"])(
    "should pass the service protocol to the modal opened by %s",
    async (buttonName) => {
      const { protocol } = await openModal(buttonName);

      expect(protocol).toBe("http");
    },
  );

  it("should open the create modal with the default HTTP environment when Add Environment is clicked", async () => {
    const { action, environment } = await openModal("Add Environment");

    expect(action).toBe("create");
    expect(environment).toEqual(
      expect.objectContaining({
        systemId: "svc-1",
        name: "New Environment",
        sourceType: EnvironmentSourceType.MANUAL,
        properties: expect.objectContaining({ connectTimeout: "120000" }),
      }),
    );
  });

  it("should reject the modal submit when the create request fails", async () => {
    const error = new Error("boom");
    mockCreateEnvironment.mockRejectedValue(error);
    const { onSubmit } = await openModal("Add Environment");

    await expect(onSubmit(REQUEST)).rejects.toBe(error);
    expect(mockRequestFailed).toHaveBeenCalledWith("boom", error);
  });

  it("should resolve the modal submit and show the created row when the create succeeds", async () => {
    mockCreateEnvironment.mockResolvedValue({
      ...ENVIRONMENT,
      id: "env-2",
      name: "Env 2",
    });
    const { onSubmit } = await openModal("Add Environment");

    await expect(onSubmit(REQUEST)).resolves.toBeUndefined();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Env 2" })).toBeInTheDocument(),
    );
    expect(mockCreateEnvironment).toHaveBeenCalledWith("svc-1", REQUEST);
  });
});
