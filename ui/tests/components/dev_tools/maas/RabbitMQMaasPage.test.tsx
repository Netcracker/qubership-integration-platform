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
import { RabbitMQMaasPage } from "../../../../src/components/dev_tools/maas/RabbitMQMaasPage";

const TEST_NAMESPACE = "test-namespace";

const mockGetMaasRabbitMQDeclarativeFile =
  jest.fn<
    (_request: {
      vhost: string;
      exchange: string;
      queue: string;
      routingKey?: string;
    }) => Promise<File>
  >();
const mockCreateMaasRabbitMQEntity =
  jest.fn<
    (_request: {
      namespace: string;
      vhost: string;
      exchange: string;
      queue: string;
      routingKey?: string;
    }) => Promise<void>
  >();
const mockRequestFailed = jest.fn();
const mockDownloadFile = jest.fn();

jest.mock("../../../../src/api/api", () => ({
  api: {
    getMaasRabbitMQDeclarativeFile: (req: {
      vhost: string;
      exchange: string;
      queue: string;
      routingKey?: string;
    }) => mockGetMaasRabbitMQDeclarativeFile(req),
    createMaasRabbitMQEntity: (req: {
      namespace: string;
      vhost: string;
      exchange: string;
      queue: string;
      routingKey?: string;
    }) => mockCreateMaasRabbitMQEntity(req),
  },
}));

jest.mock("../../../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => ({ requestFailed: mockRequestFailed }),
}));

jest.mock("../../../../src/misc/download-utils", () => ({
  downloadFile: (...args: unknown[]) => mockDownloadFile(...args),
}));

jest.mock("@ant-design/icons", () => {
  const React = require("react");
  return {
    InfoCircleOutlined: () =>
      React.createElement("span", { "data-testid": "info-icon" }),
  };
});

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
    labelWithIcon: "labelWithIcon",
    infoIcon: "infoIcon",
  },
}));

jest.mock("../../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

function fillValidFormForCreate(exchange = "my-exchange", queue = "my-queue") {
  fireEvent.change(screen.getByLabelText(/namespace/i), {
    target: { value: TEST_NAMESPACE },
  });
  fireEvent.change(screen.getByPlaceholderText(/vhost classifier name/i), {
    target: { value: "public" },
  });
  fireEvent.change(screen.getByPlaceholderText(/exchange name/i), {
    target: { value: exchange },
  });
  fireEvent.change(screen.getByPlaceholderText(/queue name/i), {
    target: { value: queue },
  });
}

function fillValidFormForExport(
  exchange = "export-exchange",
  queue = "export-queue",
) {
  fireEvent.change(screen.getByLabelText(/namespace/i), {
    target: { value: TEST_NAMESPACE },
  });
  fireEvent.change(screen.getByPlaceholderText(/vhost classifier name/i), {
    target: { value: "public" },
  });
  fireEvent.change(screen.getByPlaceholderText(/exchange name/i), {
    target: { value: exchange },
  });
  fireEvent.change(screen.getByPlaceholderText(/queue name/i), {
    target: { value: queue },
  });
}

describe("RabbitMQMaasPage", () => {
  let originalWindow: unknown;

  beforeEach(() => {
    originalWindow = (globalThis as unknown as { window?: unknown }).window;
    (globalThis as unknown as { window?: unknown }).window = {
      routes: { namespace: TEST_NAMESPACE },
    };
    mockGetMaasRabbitMQDeclarativeFile.mockResolvedValue(
      new File(["data"], "declarative.json"),
    );
    mockCreateMaasRabbitMQEntity.mockResolvedValue(undefined);
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
    render(<RabbitMQMaasPage />);
    expect(screen.getByText("RabbitMQ - MaaS")).toBeInTheDocument();
    expect(screen.getByLabelText(/namespace/i)).toBeInTheDocument();
    expect(
      screen.getByPlaceholderText(/vhost classifier name/i),
    ).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/exchange name/i)).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/queue name/i)).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/routing key/i)).toBeInTheDocument();
  });

  test("Create calls api.createMaasRabbitMQEntity with form values", async () => {
    render(<RabbitMQMaasPage />);
    fillValidFormForCreate("my-exchange", "my-queue");
    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /create/i }),
      ).not.toBeDisabled();
    });
    fireEvent.click(screen.getByRole("button", { name: /create/i }));
    await waitFor(() => {
      expect(mockCreateMaasRabbitMQEntity).toHaveBeenCalledWith({
        namespace: TEST_NAMESPACE,
        vhost: "public",
        exchange: "my-exchange",
        queue: "my-queue",
        routingKey: "",
      });
    });
  });

  test("Create with routing key calls api with routing key", async () => {
    render(<RabbitMQMaasPage />);
    fillValidFormForCreate("ex", "q");
    fireEvent.change(screen.getByPlaceholderText(/routing key/i), {
      target: { value: "routing.key" },
    });
    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /create/i }),
      ).not.toBeDisabled();
    });
    fireEvent.click(screen.getByRole("button", { name: /create/i }));
    await waitFor(() => {
      expect(mockCreateMaasRabbitMQEntity).toHaveBeenCalledWith({
        namespace: TEST_NAMESPACE,
        vhost: "public",
        exchange: "ex",
        queue: "q",
        routingKey: "routing.key",
      });
    });
  });

  test("Export calls api.getMaasRabbitMQDeclarativeFile and downloadFile", async () => {
    render(<RabbitMQMaasPage />);
    fillValidFormForExport("export-exchange", "export-queue");
    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /export/i }),
      ).not.toBeDisabled();
    });
    fireEvent.click(screen.getByRole("button", { name: /export/i }));
    await waitFor(() => {
      expect(mockGetMaasRabbitMQDeclarativeFile).toHaveBeenCalledWith({
        vhost: "public",
        exchange: "export-exchange",
        queue: "export-queue",
        routingKey: "",
      });
      expect(mockDownloadFile).toHaveBeenCalled();
    });
  });

  test("shows error when export fails", async () => {
    mockGetMaasRabbitMQDeclarativeFile.mockRejectedValue(
      new Error("Export failed"),
    );
    render(<RabbitMQMaasPage />);
    fillValidFormForExport();
    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /export/i }),
      ).not.toBeDisabled();
    });
    fireEvent.click(screen.getByRole("button", { name: /export/i }));
    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Unable to export RabbitMQ declarative file.",
        expect.any(Error),
      );
    });
  });

  test("shows error when create fails", async () => {
    mockCreateMaasRabbitMQEntity.mockRejectedValue(new Error("API error"));
    render(<RabbitMQMaasPage />);
    fillValidFormForCreate();
    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /create/i }),
      ).not.toBeDisabled();
    });
    fireEvent.click(screen.getByRole("button", { name: /create/i }));
    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Unable to create MaaS RabbitMQ entity with given values.",
        expect.any(Error),
      );
    });
  });

  test("Reset clears form fields", async () => {
    render(<RabbitMQMaasPage />);
    fillValidFormForCreate("changed-exchange", "changed-queue");
    const exchangeInput = screen.getByPlaceholderText(/exchange name/i);
    const queueInput = screen.getByPlaceholderText(/queue name/i);
    expect(exchangeInput).toHaveValue("changed-exchange");
    expect(queueInput).toHaveValue("changed-queue");
    fireEvent.click(screen.getByRole("button", { name: /reset/i }));
    await waitFor(() => {
      expect(exchangeInput).toHaveValue("");
      expect(queueInput).toHaveValue("");
    });
  });

  test("form valid with exchange only", async () => {
    render(<RabbitMQMaasPage />);
    fireEvent.change(screen.getByLabelText(/namespace/i), {
      target: { value: TEST_NAMESPACE },
    });
    fireEvent.change(screen.getByPlaceholderText(/vhost classifier name/i), {
      target: { value: "public" },
    });
    fireEvent.change(screen.getByPlaceholderText(/exchange name/i), {
      target: { value: "only-exchange" },
    });
    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /create/i }),
      ).not.toBeDisabled();
    });
  });

  test("form valid with queue only", async () => {
    render(<RabbitMQMaasPage />);
    fireEvent.change(screen.getByLabelText(/namespace/i), {
      target: { value: TEST_NAMESPACE },
    });
    fireEvent.change(screen.getByPlaceholderText(/vhost classifier name/i), {
      target: { value: "public" },
    });
    fireEvent.change(screen.getByPlaceholderText(/queue name/i), {
      target: { value: "only-queue" },
    });
    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /create/i }),
      ).not.toBeDisabled();
    });
  });

  test("Create disabled when both exchange and queue are empty", async () => {
    render(<RabbitMQMaasPage />);
    fireEvent.change(screen.getByLabelText(/namespace/i), {
      target: { value: TEST_NAMESPACE },
    });
    fireEvent.change(screen.getByPlaceholderText(/vhost classifier name/i), {
      target: { value: "public" },
    });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: /create/i })).toBeDisabled();
      expect(screen.getByRole("button", { name: /export/i })).toBeDisabled();
    });
  });

  test("routing key validation requires both exchange and queue", async () => {
    render(<RabbitMQMaasPage />);
    fireEvent.change(screen.getByLabelText(/namespace/i), {
      target: { value: TEST_NAMESPACE },
    });
    fireEvent.change(screen.getByPlaceholderText(/vhost classifier name/i), {
      target: { value: "public" },
    });
    fireEvent.change(screen.getByPlaceholderText(/exchange name/i), {
      target: { value: "ex" },
    });
    fireEvent.change(screen.getByPlaceholderText(/routing key/i), {
      target: { value: "routing.key" },
    });
    await waitFor(() => {
      expect(
        screen.getByText(
          /Fields "Exchange Name" and "Queue Name" must be specified/i,
        ),
      ).toBeInTheDocument();
    });
  });
});
