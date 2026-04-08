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
import { EnvironmentSourceType } from "../../../../src/api/apiTypes";
import { EnvironmentParamsModal } from "../../../../src/components/services/modals/EnvironmentParamsModal";
import { ServiceContext } from "../../../../src/components/services/detail/ServiceParametersPage";

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

Object.defineProperty(globalThis, "ResizeObserver", {
  writable: true,
  value: class ResizeObserverMock {
    observe = jest.fn();
    disconnect = jest.fn();
  },
});

function renderModal(
  protocol: string,
  overrides: Partial<
    React.ComponentProps<typeof EnvironmentParamsModal>["environment"]
  > = {},
) {
  const onSave = jest.fn().mockResolvedValue(undefined);
  const onClose = jest.fn();

  render(
    React.createElement(
      ServiceContext.Provider,
      { value: { id: "svc-1", protocol } as never },
      React.createElement(EnvironmentParamsModal, {
        open: true,
        environment: {
          id: "env-1",
          name: "Env 1",
          address: "localhost",
          labels: [],
          sourceType: EnvironmentSourceType.MANUAL,
          properties: {
            key: "value",
            authMethod: "SASL_SSL",
          },
          ...overrides,
        },
        onClose,
        onSave,
        saving: false,
      }),
    ),
  );

  return { onSave, onClose };
}

describe("EnvironmentParamsModal", () => {
  it("restores environment properties when the modal is reopened", async () => {
    const onSave = jest.fn().mockResolvedValue(undefined);
    const onClose = jest.fn();
    const environment = {
      id: "env-1",
      name: "Env 1",
      address: "localhost",
      labels: [],
      sourceType: EnvironmentSourceType.MANUAL,
      properties: {
        key: "value",
        authMethod: "SASL_SSL",
      },
    };

    const { rerender } = render(
      React.createElement(
        ServiceContext.Provider,
        { value: { id: "svc-1", protocol: "kafka" } as never },
        React.createElement(EnvironmentParamsModal, {
          open: true,
          environment,
          onClose,
          onSave,
          saving: false,
        }),
      ),
    );

    fireEvent.click(screen.getByLabelText("Expand properties"));
    fireEvent.click(screen.getByRole("switch"));

    let panel = await screen.findByTestId("environment-properties-panel");
    let textArea = within(panel).getByRole("textbox");

    fireEvent.focus(textArea);
    fireEvent.change(textArea, {
      target: { value: "bootstrap.servers=broker:9092;" },
    });
    fireEvent.blur(textArea);

    rerender(
      React.createElement(
        ServiceContext.Provider,
        { value: { id: "svc-1", protocol: "kafka" } as never },
        React.createElement(EnvironmentParamsModal, {
          open: false,
          environment,
          onClose,
          onSave,
          saving: false,
        }),
      ),
    );

    rerender(
      React.createElement(
        ServiceContext.Provider,
        { value: { id: "svc-1", protocol: "kafka" } as never },
        React.createElement(EnvironmentParamsModal, {
          open: true,
          environment,
          onClose,
          onSave,
          saving: false,
        }),
      ),
    );

    const expandPropertiesButton = screen.queryByLabelText("Expand properties");
    if (expandPropertiesButton) {
      fireEvent.click(expandPropertiesButton);
    }

    panel = await screen.findByTestId("environment-properties-panel");
    textArea = within(panel).queryByRole("textbox");
    if (!textArea) {
      fireEvent.click(screen.getByRole("switch"));
      textArea = within(panel).getByRole("textbox");
    }

    expect(textArea).toHaveValue("key=value;\nauthMethod=SASL_SSL;");
  });

  it("renders the properties panel with flex layout when expanded", async () => {
    renderModal("kafka");

    fireEvent.click(screen.getByLabelText("Expand properties"));

    const panel = await screen.findByTestId("environment-properties-panel");
    expect(panel).toHaveStyle({
      display: "flex",
      flexDirection: "column",
      flex: "1",
      minHeight: "0",
    });
  });

  it("resets MaaS source type to manual for non-async protocols", async () => {
    const { onSave } = renderModal("http", {
      sourceType: EnvironmentSourceType.MAAS_BY_CLASSIFIER,
    });

    expect(
      screen.getAllByText(
        "MaaS is only available for Kafka and AMQP protocols.",
      )[0],
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        sourceType: EnvironmentSourceType.MANUAL,
        address: "localhost",
      }),
    );
  });

  it("switches source type to MaaS for async protocols before save", async () => {
    const { onSave } = renderModal("kafka");

    fireEvent.click(screen.getByText("MaaS"));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        sourceType: EnvironmentSourceType.MAAS_BY_CLASSIFIER,
        address: undefined,
      }),
    );
  });

  it("parses key-value properties text and ignores invalid lines on save", async () => {
    const { onSave } = renderModal("kafka");

    fireEvent.click(screen.getByLabelText("Expand properties"));
    fireEvent.click(screen.getByRole("switch"));

    const panel = await screen.findByTestId("environment-properties-panel");
    const textArea = within(panel).getByRole("textbox");

    fireEvent.focus(textArea);
    fireEvent.change(textArea, {
      target: {
        value: "bootstrap.servers=broker:9092;\ninvalid line\nclient.id=qip",
      },
    });
    fireEvent.blur(textArea);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        properties: {
          "bootstrap.servers": "broker:9092",
          "client.id": "qip",
        },
      }),
    );
  });
});
