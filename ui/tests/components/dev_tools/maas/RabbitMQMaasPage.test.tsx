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
import {
  render,
  screen,
  fireEvent,
  waitFor,
  act,
} from "@testing-library/react";

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

jest.mock("@ant-design/icons", () => ({
  InfoCircleOutlined: () => <span data-testid="info-icon" />,
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
    labelWithIcon: "labelWithIcon",
    infoIcon: "infoIcon",
  },
}));

jest.mock("../../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

// Mock isRabbitMQFormValid to bypass Form.useWatch propagation issues in JSDOM.
// The pure validation logic is covered exhaustively in types.test.ts.
let mockIsFormValid = false;

jest.mock("../../../../src/components/dev_tools/maas/types", () => {
  const actual: Record<string, unknown> = jest.requireActual(
    "../../../../src/components/dev_tools/maas/types",
  );
  return { ...actual, isRabbitMQFormValid: () => mockIsFormValid };
});

async function flushPromises(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

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

import { RabbitMQMaasPage } from "../../../../src/components/dev_tools/maas/RabbitMQMaasPage";

describe("RabbitMQMaasPage", () => {
  let originalWindow: unknown;

  beforeEach(() => {
    originalWindow = (globalThis as unknown as { window?: unknown }).window;
    (globalThis as unknown as { window?: unknown }).window = {
      routes: { namespace: TEST_NAMESPACE },
    };
    mockIsFormValid = false;
    mockGetMaasRabbitMQDeclarativeFile.mockResolvedValue(
      new File(["data"], "declarative.json"),
    );
    mockCreateMaasRabbitMQEntity.mockResolvedValue(undefined);
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
    mockIsFormValid = true;
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
  }, 10000);

  test("Create with routing key calls api with routing key", async () => {
    mockIsFormValid = true;
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
  }, 10000);

  test("Export calls api.getMaasRabbitMQDeclarativeFile and downloadFile", async () => {
    mockIsFormValid = true;
    render(<RabbitMQMaasPage />);
    fillValidFormForCreate("export-exchange", "export-queue");

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
  }, 10000);

  test("shows error when export fails", async () => {
    mockIsFormValid = true;
    mockGetMaasRabbitMQDeclarativeFile.mockRejectedValue(
      new Error("Export failed"),
    );
    render(<RabbitMQMaasPage />);
    fillValidFormForCreate();

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
  }, 10000);

  test("shows error when create fails", async () => {
    mockIsFormValid = true;
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
  }, 10000);

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

  test("buttons enabled when form is valid", async () => {
    mockIsFormValid = true;
    render(<RabbitMQMaasPage />);
    await flushPromises();

    expect(screen.getByRole("button", { name: /create/i })).not.toBeDisabled();
  });

  test("buttons disabled when form is invalid", async () => {
    mockIsFormValid = false;
    render(<RabbitMQMaasPage />);
    await flushPromises();

    expect(screen.getByRole("button", { name: /create/i })).toBeDisabled();
    expect(screen.getByRole("button", { name: /export/i })).toBeDisabled();
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
  }, 10000);
});
