/**
 * @jest-environment jsdom
 */
import React from "react";
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
import { ServiceContext } from "../../../../src/components/services/detail/ServiceParametersPage";
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
  useNotificationService: () => ({ requestFailed: mockRequestFailed }),
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

type SubmitProps = { onSubmit: (request: EnvironmentRequest) => Promise<void> };

async function openEditModalAndGetSubmit(): Promise<SubmitProps["onSubmit"]> {
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
          <ServiceEnvironmentsTab formatTimestamp={String} />
        </ServiceContext.Provider>
      </UserPermissionsContext.Provider>
    </MemoryRouter>,
  );

  fireEvent.click(await screen.findByRole("button", { name: "Env 1" }));

  const { component } = mockShowModal.mock.calls[0][0] as {
    component: React.ReactElement<SubmitProps>;
  };
  return component.props.onSubmit;
}

describe("ServiceEnvironmentsTab", () => {
  beforeEach(() => {
    mockGetEnvironments.mockResolvedValue([ENVIRONMENT]);
  });

  it("should reject the modal submit when the update request fails", async () => {
    const error = new Error("boom");
    mockUpdateEnvironment.mockRejectedValue(error);
    const onSubmit = await openEditModalAndGetSubmit();

    await expect(onSubmit(REQUEST)).rejects.toBe(error);
    expect(mockRequestFailed).toHaveBeenCalledWith("boom", error);
  });

  it("should resolve the modal submit and show the updated row when the update succeeds", async () => {
    mockUpdateEnvironment.mockResolvedValue({ ...ENVIRONMENT, name: "Env 2" });
    const onSubmit = await openEditModalAndGetSubmit();

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
});
