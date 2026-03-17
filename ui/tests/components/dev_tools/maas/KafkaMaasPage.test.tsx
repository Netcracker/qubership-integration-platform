/**
 * @jest-environment jsdom
 */
import {
  describe,
  test,
  expect,
  jest,
  beforeEach,
  afterEach,
} from "@jest/globals";
import "@testing-library/jest-dom/jest-globals";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { KafkaMaasPage } from "../../../../src/components/dev_tools/maas/KafkaMaasPage";

const TEST_NAMESPACE = "test-namespace";

const mockGetMaasKafkaDeclarativeFile =
  jest.fn<(_request: { topicClassifierName: string }) => Promise<File>>();
const mockCreateMaasKafkaEntity =
  jest.fn<
    (_request: {
      namespace: string;
      topicClassifierName: string;
    }) => Promise<void>
  >();
const mockRequestFailed = jest.fn();
const mockDownloadFile = jest.fn();

jest.mock("../../../../src/api/api", () => ({
  api: {
    getMaasKafkaDeclarativeFile: (req: { topicClassifierName: string }) =>
      mockGetMaasKafkaDeclarativeFile(req),
    createMaasKafkaEntity: (req: {
      namespace: string;
      topicClassifierName: string;
    }) => mockCreateMaasKafkaEntity(req),
  },
}));

jest.mock("../../../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => ({ requestFailed: mockRequestFailed }),
}));

jest.mock("../../../../src/misc/download-utils", () => ({
  downloadFile: (...args: unknown[]) => mockDownloadFile(...args),
}));

jest.mock("../../../../src/components/dev_tools/DevTools.module.css", () => ({
  __esModule: true,
  default: { container: "container" },
}));

jest.mock("../../../../src/components/dev_tools/maas/Maas.module.css", () => ({
  __esModule: true,
  default: {
    header: "header",
    titleHeader: "titleHeader",
    parametersSection: "parametersSection",
    parametersHeading: "parametersHeading",
    footer: "footer",
    actionsContainer: "actionsContainer",
  },
}));

jest.mock("../../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

describe("KafkaMaasPage", () => {
  let originalWindow: unknown;

  beforeEach(() => {
    originalWindow = (globalThis as unknown as { window?: unknown }).window;
    (globalThis as unknown as { window?: unknown }).window = {
      routes: { namespace: "test-namespace" },
    };
    mockGetMaasKafkaDeclarativeFile.mockResolvedValue(
      new File(["data"], "declarative.json"),
    );
    mockCreateMaasKafkaEntity.mockResolvedValue(undefined);
    mockRequestFailed.mockClear();
  });

  afterEach(() => {
    if (originalWindow !== undefined) {
      (globalThis as unknown as { window?: unknown }).window = originalWindow;
    } else {
      delete (globalThis as unknown as { window?: unknown }).window;
    }
  });

  test("renders title and form fields", () => {
    render(<KafkaMaasPage />);
    expect(screen.getByText("Kafka - MaaS")).toBeInTheDocument();
    expect(screen.getByLabelText(/namespace/i)).toBeInTheDocument();
    expect(
      screen.getByPlaceholderText(/topic classifier name/i),
    ).toBeInTheDocument();
  });

  test("Create calls api.createMaasKafkaEntity with form values", async () => {
    render(<KafkaMaasPage />);
    fireEvent.change(screen.getByLabelText(/namespace/i), {
      target: { value: "test-namespace" },
    });
    fireEvent.change(screen.getByPlaceholderText(/topic classifier name/i), {
      target: { value: "my-classifier" },
    });
    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /create/i }),
      ).not.toBeDisabled();
    });
    fireEvent.click(screen.getByRole("button", { name: /create/i }));
    await waitFor(() => {
      expect(mockCreateMaasKafkaEntity).toHaveBeenCalledWith({
        namespace: "test-namespace",
        topicClassifierName: "my-classifier",
      });
    });
  });

  test("Export calls api.getMaasKafkaDeclarativeFile and downloadFile", async () => {
    render(<KafkaMaasPage />);
    fireEvent.change(screen.getByLabelText(/namespace/i), {
      target: { value: "test-namespace" },
    });
    fireEvent.change(screen.getByPlaceholderText(/topic classifier name/i), {
      target: { value: "export-classifier" },
    });
    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /export/i }),
      ).not.toBeDisabled();
    });
    fireEvent.click(screen.getByRole("button", { name: /export/i }));
    await waitFor(() => {
      expect(mockGetMaasKafkaDeclarativeFile).toHaveBeenCalledWith({
        topicClassifierName: "export-classifier",
      });
      expect(mockDownloadFile).toHaveBeenCalled();
    });
  });

  test("shows error when export fails", async () => {
    mockGetMaasKafkaDeclarativeFile.mockRejectedValue(
      new Error("Export failed"),
    );
    render(<KafkaMaasPage />);
    fireEvent.change(screen.getByLabelText(/namespace/i), {
      target: { value: TEST_NAMESPACE },
    });
    fireEvent.change(screen.getByPlaceholderText(/topic classifier name/i), {
      target: { value: "export-classifier" },
    });
    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /export/i }),
      ).not.toBeDisabled();
    });
    fireEvent.click(screen.getByRole("button", { name: /export/i }));
    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Unable to export Kafka declarative file.",
        expect.any(Error),
      );
    });
  });

  test("shows error when create fails", async () => {
    mockCreateMaasKafkaEntity.mockRejectedValue(new Error("API error"));
    render(<KafkaMaasPage />);
    fireEvent.change(screen.getByLabelText(/namespace/i), {
      target: { value: "test-namespace" },
    });
    fireEvent.change(screen.getByPlaceholderText(/topic classifier name/i), {
      target: { value: "my-classifier" },
    });
    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /create/i }),
      ).not.toBeDisabled();
    });
    fireEvent.click(screen.getByRole("button", { name: /create/i }));
    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Unable to create MaaS entity with given values.",
        expect.any(Error),
      );
    });
  });

  test("Reset clears topic classifier field", async () => {
    render(<KafkaMaasPage />);
    const input = screen.getByPlaceholderText(/topic classifier name/i);
    fireEvent.change(input, { target: { value: "changed" } });
    expect(input).toHaveValue("changed");
    fireEvent.click(screen.getByRole("button", { name: /reset/i }));
    await waitFor(() => {
      expect(input).toHaveValue("");
    });
  });
});
