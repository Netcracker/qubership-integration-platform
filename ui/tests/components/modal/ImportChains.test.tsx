/**
 * @jest-environment jsdom
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  ChainCommitRequestAction,
  DomainType,
  EngineDomain,
  ImportPreview,
} from "../../../src/api/apiTypes";

jest.mock("react-resizable/css/styles.css", () => ({}));

jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

jest.mock("antd/es/upload/Dragger", () => ({
  __esModule: true,
  default: ({ onChange }: { onChange?: (info: unknown) => void }) => (
    <button
      data-testid="dragger"
      type="button"
      onClick={() =>
        onChange?.({
          fileList: [
            {
              uid: "1",
              name: "export.zip",
              originFileObj: new File(["zip"], "export.zip"),
            },
          ],
        })
      }
    />
  ),
}));

jest.mock("../../../src/api/api", () => ({
  api: {
    getImportPreview: jest.fn(),
    getDomains: jest.fn(),
    commitImport: jest.fn(),
  },
}));

const mockApi: Record<string, jest.Mock> = jest.requireMock(
  "../../../src/api/api",
).api;

// A stable reference keeps getDomains, which depends on the service, from re-running its effect.
const mockNotificationService = { requestFailed: jest.fn() };

jest.mock("../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => mockNotificationService,
}));

jest.mock("../../../src/ModalContextProvider.tsx", () => ({
  useModalContext: () => ({ closeContainingModal: jest.fn() }),
}));

jest.mock("../../../src/Modals.tsx", () => ({
  useModalsContext: () => ({ showModal: jest.fn() }),
}));

jest.mock("../../../src/components/chains/diff/ChainDiffPopup.tsx", () => ({
  ChainDiffPopup: () => null,
}));

let mockDomainTypes: DomainType[] = [DomainType.CLASSIC, DomainType.MICRO];

jest.mock("../../../src/components/SelectDomains.tsx", () => ({
  ...jest.requireActual("../../../src/components/SelectDomains.tsx"),
  useDomainTypes: () => ({ loaded: true, domainTypes: mockDomainTypes }),
}));

import { ImportChains } from "../../../src/components/modal/ImportChains";

const classicDomain: EngineDomain = {
  id: "default",
  name: "Default",
  replicas: 1,
  namespace: "qip",
  type: DomainType.CLASSIC,
};

const preview = {
  chains: [
    {
      id: "chain-1",
      name: "Orders chain",
      usedSystems: [],
      deployAction: ChainCommitRequestAction.DEPLOY,
      deployments: [{ domain: "default" }, { domain: "orders" }],
      exists: false,
    },
  ],
  systems: [],
  variables: [],
} as unknown as ImportPreview;

async function openPreview() {
  render(<ImportChains />);
  fireEvent.click(screen.getByTestId("dragger"));
  fireEvent.click(screen.getByText("Next"));
  await waitFor(() => expect(mockApi.getDomains).toHaveBeenCalled());
}

describe("ImportChains", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockDomainTypes = [DomainType.CLASSIC, DomainType.MICRO];
    mockApi.getImportPreview.mockResolvedValue(preview);
    mockApi.getDomains.mockResolvedValue([classicDomain]);
    mockApi.commitImport.mockResolvedValue({ importId: undefined });
  });

  it("should not show chains when the domains have not loaded yet", async () => {
    mockApi.getDomains.mockReturnValue(new Promise(() => {}));

    await openPreview();

    expect(screen.queryByText("Orders chain")).not.toBeInTheDocument();
  });

  it("should show an unlisted domain as a micro-domain when micro-domains are enabled", async () => {
    await openPreview();

    expect(await screen.findByText("Orders chain")).toBeInTheDocument();
    expect(screen.getByText("Default")).toBeInTheDocument();
    expect(screen.getByText("orders")).toBeInTheDocument();
    expect(screen.getAllByText("micro")).toHaveLength(1);
  });

  it("should drop an unlisted domain when micro-domains are disabled", async () => {
    mockDomainTypes = [DomainType.CLASSIC];

    await openPreview();

    expect(await screen.findByText("Orders chain")).toBeInTheDocument();
    expect(screen.getByText("Default")).toBeInTheDocument();
    expect(screen.queryByText("orders")).not.toBeInTheDocument();
    expect(screen.queryByText("micro")).not.toBeInTheDocument();
  });

  it("should send the listed and the micro-domain when importing", async () => {
    await openPreview();
    await screen.findByText("Orders chain");

    fireEvent.click(screen.getByRole("button", { name: "Import" }));

    await waitFor(() => expect(mockApi.commitImport).toHaveBeenCalled());
    const [, request] = mockApi.commitImport.mock.calls[0];
    expect(request.chainCommitRequests).toEqual([
      {
        id: "chain-1",
        archiveName: "export.zip",
        deployAction: ChainCommitRequestAction.DEPLOY,
        domains: [
          { id: "default", name: "Default" },
          { id: "orders", name: "orders" },
        ],
      },
    ]);
  });

  it("should report the failure when the domains fail to load", async () => {
    const error = new Error("boom");
    mockApi.getDomains.mockRejectedValue(error);

    await openPreview();

    await waitFor(() =>
      expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
        "Failed to get domains",
        error,
      ),
    );
  });
});
