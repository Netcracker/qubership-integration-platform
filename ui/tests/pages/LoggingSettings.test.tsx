/**
 * @jest-environment jsdom
 */
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { LoggingSettings } from "../../src/pages/LoggingSettings";
import {
  ChainLoggingProperties,
  ChainLoggingSettings,
  LogLoggingLevel,
  LogPayload,
  SessionLogDetails,
  SessionsLoggingLevel,
} from "../../src/api/apiTypes";

const mockGetLoggingSettings = jest.fn<
  Promise<ChainLoggingSettings>,
  [string]
>();
const mockSetLoggingProperties = jest.fn<
  Promise<void>,
  [string, ChainLoggingProperties]
>();
const mockDeleteLoggingSettings = jest.fn<Promise<void>, [string]>();
const mockRequestFailed = jest.fn<void, unknown[]>();
let mockCanUpdate = true;

jest.mock("../../src/api/api.ts", () => ({
  api: {
    getLoggingSettings: (...args: [string]) => mockGetLoggingSettings(...args),
    setLoggingProperties: (...args: [string, ChainLoggingProperties]) =>
      mockSetLoggingProperties(...args),
    deleteLoggingSettings: (...args: [string]) =>
      mockDeleteLoggingSettings(...args),
  },
}));

const mockNotificationService = { requestFailed: mockRequestFailed };
jest.mock("../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => mockNotificationService,
}));

jest.mock("react-router", () => ({
  ...jest.requireActual<typeof import("react-router")>("react-router"),
  useParams: () => ({ chainId: "chain-1" }),
}));

jest.mock("../../src/permissions/usePermissions.tsx", () => ({
  usePermissions: () => ({}),
}));

jest.mock("../../src/permissions/funcs.ts", () => ({
  hasPermissions: () => mockCanUpdate,
}));

const properties: ChainLoggingProperties = {
  sessionsLoggingLevel: SessionsLoggingLevel.INFO,
  sessionLogDetails: SessionLogDetails.FULL,
  logLoggingLevel: LogLoggingLevel.INFO,
  logPayload: [LogPayload.HEADERS],
  dptEventsEnabled: false,
  maskingEnabled: true,
};

const customSettings: ChainLoggingSettings = {
  fallbackDefault: properties,
  custom: properties,
};

const labels = [
  "Override default properties",
  "Session level",
  "Session log details",
  "Log level",
  "Log payload",
  "Produce DPT Events",
  "Enable logging masking",
];

const fields = () => labels.map((label) => screen.getByLabelText(label));

const submit = () =>
  fireEvent.submit(document.getElementById("logging-settings-form")!);

async function renderLoaded() {
  mockGetLoggingSettings.mockResolvedValue(customSettings);
  render(<LoggingSettings />);
  await screen.findByText("Custom");
}

describe("LoggingSettings", () => {
  beforeEach(() => {
    mockCanUpdate = true;
  });

  it("should disable every field while the custom settings save", async () => {
    mockSetLoggingProperties.mockReturnValue(new Promise<void>(() => {}));
    await renderLoaded();

    submit();

    await waitFor(() => expect(mockSetLoggingProperties).toHaveBeenCalled());
    fields().forEach((field) => expect(field).toBeDisabled());
  });

  it("should disable every field while the custom settings are removed", async () => {
    mockDeleteLoggingSettings.mockReturnValue(new Promise<void>(() => {}));
    await renderLoaded();
    fireEvent.click(screen.getByLabelText("Override default properties"));

    submit();

    await waitFor(() => expect(mockDeleteLoggingSettings).toHaveBeenCalled());
    fields().forEach((field) => expect(field).toBeDisabled());
  });

  it("should disable every field of custom settings without the update permission", async () => {
    mockCanUpdate = false;
    await renderLoaded();

    fields().forEach((field) => expect(field).toBeDisabled());
  });

  it("should disable every field while the settings load", async () => {
    let finishLoad!: (settings: ChainLoggingSettings) => void;
    mockGetLoggingSettings.mockReturnValue(
      new Promise((resolve) => (finishLoad = resolve)),
    );
    render(<LoggingSettings />);

    fields().forEach((field) => expect(field).toBeDisabled());
    await act(async () => finishLoad(customSettings));
    fields().forEach((field) => expect(field).toBeEnabled());
  });

  it("should notify when the settings fail to load", async () => {
    const error = new Error("load failed");
    mockGetLoggingSettings.mockRejectedValue(error);
    render(<LoggingSettings />);

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to get logging settings",
        error,
      ),
    );
  });
});
