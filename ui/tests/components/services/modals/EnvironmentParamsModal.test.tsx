/**
 * @jest-environment jsdom
 */
import React, { useEffect } from "react";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import "@testing-library/jest-dom";
import type { EditorProps } from "@monaco-editor/react";
import {
  Environment,
  EnvironmentRequest,
  EnvironmentSourceType,
} from "../../../../src/api/apiTypes";
import { EnvironmentParamsModal } from "../../../../src/components/services/modals/EnvironmentParamsModal";
import { ServiceContext } from "../../../../src/components/services/detail/ServiceParametersPage";
import { Modals, useModalsContext } from "../../../../src/Modals.tsx";

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

jest.mock("@monaco-editor/react", () => {
  const actualReact = jest.requireActual<typeof import("react")>("react");
  return {
    __esModule: true,
    Editor: (props: EditorProps) => {
      actualReact.useEffect(() => {
        props.onMount?.({ getModel: () => ({}) } as never, {
          MarkerSeverity: { Error: 8 },
          editor: { setModelMarkers: jest.fn() },
          languages: {
            getLanguages: () => [],
            register: jest.fn(),
            setLanguageConfiguration: jest.fn(),
            setMonarchTokensProvider: jest.fn(),
          },
        });
      }, []);
      return actualReact.createElement("textarea", {
        "data-testid": "key-value-pairs-editor",
        value: props.value,
        onChange: (e: React.ChangeEvent<HTMLTextAreaElement>) =>
          props.onChange?.(e.target.value, undefined as never),
      });
    },
  };
});

jest.mock("../../../../src/hooks/useMonacoTheme", () => ({
  useMonacoTheme: () => "vs",
  applyVSCodeThemeToMonaco: jest.fn(),
}));

const ENVIRONMENT: Environment = {
  id: "env-1",
  systemId: "svc-1",
  name: "Env 1",
  address: "localhost",
  labels: [],
  sourceType: EnvironmentSourceType.MANUAL,
  properties: { key: "value", authMethod: "SASL_SSL" },
};

const ShowOnMount: React.FC<{ component: React.ReactNode }> = ({
  component,
}) => {
  const { showModal } = useModalsContext();
  useEffect(() => showModal({ component }), [component, showModal]);
  return null;
};

function renderModal(
  protocol: string,
  overrides: Partial<Environment> = {},
  onSubmit: jest.Mock<Promise<void>, [EnvironmentRequest]> = jest
    .fn()
    .mockResolvedValue(undefined),
) {
  render(
    <ServiceContext.Provider value={{ id: "svc-1", protocol } as never}>
      <Modals>
        <ShowOnMount
          component={
            <EnvironmentParamsModal
              action="edit"
              environment={{ ...ENVIRONMENT, ...overrides }}
              onSubmit={onSubmit}
            />
          }
        />
      </Modals>
    </ServiceContext.Provider>,
  );
  return { onSubmit };
}

function expandProperties() {
  fireEvent.click(screen.getByRole("button", { name: "Expand row" }));
}

function save() {
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
}

describe("EnvironmentParamsModal", () => {
  it("should show the edit title when action is edit", () => {
    renderModal("http");

    expect(screen.getByText("Edit Environment")).toBeInTheDocument();
  });

  it("should submit the form values with properties as an object", async () => {
    const { onSubmit } = renderModal("kafka");

    save();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "Env 1",
        address: "localhost",
        sourceType: EnvironmentSourceType.MANUAL,
        properties: { key: "value", authMethod: "SASL_SSL" },
      }),
    );
    await waitFor(() =>
      expect(screen.queryByText("Edit Environment")).not.toBeInTheDocument(),
    );
  });

  it("should submit MAAS_BY_CLASSIFIER without the address when MaaS is selected", async () => {
    const { onSubmit } = renderModal("kafka");

    fireEvent.click(screen.getByText("MAAS"));
    expect(
      screen.queryByRole("textbox", { name: "Address" }),
    ).not.toBeInTheDocument();
    save();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].sourceType).toBe(
      EnvironmentSourceType.MAAS_BY_CLASSIFIER,
    );
    expect(onSubmit.mock.calls[0][0].address).toBeUndefined();
  });

  it.each([
    EnvironmentSourceType.MAAS,
    EnvironmentSourceType.MAAS_BY_CLASSIFIER,
  ])(
    "should hide the address and submit MAAS_BY_CLASSIFIER when the stored source type is %s",
    async (sourceType) => {
      const { onSubmit } = renderModal("kafka", { sourceType, address: "" });

      expect(screen.getByRole("radio", { name: /MAAS/ })).toBeChecked();
      expect(
        screen.queryByRole("textbox", { name: "Address" }),
      ).not.toBeInTheDocument();
      save();

      await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
      expect(onSubmit.mock.calls[0][0].sourceType).toBe(
        EnvironmentSourceType.MAAS_BY_CLASSIFIER,
      );
    },
  );

  it("should disable the source type when the protocol is not async", () => {
    renderModal("http");

    expect(screen.getByRole("radio", { name: "Manual" })).toBeDisabled();
  });

  it("should not submit when name is empty", async () => {
    const { onSubmit } = renderModal("http");

    fireEvent.change(screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "" },
    });
    save();

    expect(await screen.findByText("Name is required")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByText("Edit Environment")).toBeInTheDocument();
  });

  it("should stay open when submit fails", async () => {
    const onSubmit = jest.fn().mockRejectedValue(new Error("boom"));
    renderModal("http", {}, onSubmit);

    save();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(screen.getByText("Edit Environment")).toBeInTheDocument();
  });

  it("should not warn about the properties form item while the properties are collapsed", () => {
    const consoleError = jest.spyOn(console, "error");

    renderModal("kafka");

    expect(consoleError.mock.calls.flat().join(" ")).not.toContain(
      "`name` is only used for validate React element",
    );
    consoleError.mockRestore();
  });

  it("should show the property count and the properties table when expanded", () => {
    renderModal("kafka");

    expect(screen.getByText("2")).toBeInTheDocument();
    expandProperties();

    expect(
      screen.getByRole("button", { name: "authMethod" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "SASL_SSL" }),
    ).toBeInTheDocument();
  });

  it("should show the text tab after collapsing and expanding the properties when Text is selected", async () => {
    renderModal("kafka");

    expandProperties();
    fireEvent.click(screen.getByRole("radio", { name: "Text" }));
    fireEvent.click(screen.getByRole("button", { name: "Collapse row" }));
    expandProperties();

    expect(screen.getByRole("radio", { name: "Text" })).toBeChecked();
    expect(
      await screen.findByTestId("key-value-pairs-editor"),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add property" })).toBeDisabled();
  });

  it("should submit properties edited as text", async () => {
    const { onSubmit } = renderModal("kafka");

    expandProperties();
    fireEvent.click(screen.getByRole("radio", { name: "Text" }));
    fireEvent.change(await screen.findByTestId("key-value-pairs-editor"), {
      target: { value: "bootstrap.servers=broker:9092;\nclient.id=cip;" },
    });
    save();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].properties).toEqual({
      "bootstrap.servers": "broker:9092",
      "client.id": "cip",
    });
  });

  it("should add an empty property row when the add button is clicked", () => {
    renderModal("kafka", { properties: {} });

    expandProperties();
    fireEvent.click(screen.getByRole("button", { name: "Add property" }));

    expect(screen.getByText("1")).toBeInTheDocument();
  });

  it("should close without a prompt on Cancel when nothing changed", async () => {
    renderModal("http");

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    await waitFor(() =>
      expect(screen.queryByText("Edit Environment")).not.toBeInTheDocument(),
    );
    expect(screen.queryByText("Unsaved Changes")).not.toBeInTheDocument();
  });

  it("should close without a prompt on Cancel when a property is committed unchanged", async () => {
    renderModal("kafka");
    expandProperties();

    for (const text of ["authMethod", "SASL_SSL"]) {
      fireEvent.click(screen.getByRole("button", { name: text }));
      const input = screen.getByDisplayValue(text);
      await act(async () => {
        fireEvent.keyDown(input, { key: "Enter", code: "Enter", keyCode: 13 });
      });
      expect(
        await screen.findByRole("button", { name: text }),
      ).toBeInTheDocument();
    }
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    await waitFor(() =>
      expect(screen.queryByText("Edit Environment")).not.toBeInTheDocument(),
    );
    expect(screen.queryByText("Unsaved Changes")).not.toBeInTheDocument();
  });

  it("should discard the changes when Yes is clicked in the unsaved prompt", async () => {
    const { onSubmit } = renderModal("http");

    fireEvent.change(screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Renamed" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(await screen.findByText("Unsaved Changes")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Yes" }));

    await waitFor(() =>
      expect(screen.queryByText("Edit Environment")).not.toBeInTheDocument(),
    );
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("should keep the environment modal open when No is clicked in the unsaved prompt", async () => {
    const { onSubmit } = renderModal("http");

    fireEvent.change(screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "Renamed" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    const prompt = (await screen.findByText("Unsaved Changes")).closest(
      '[role="dialog"]',
    ) as HTMLElement;
    fireEvent.click(within(prompt).getByRole("button", { name: "No" }));

    await waitFor(() =>
      expect(screen.queryByText("Unsaved Changes")).not.toBeInTheDocument(),
    );
    expect(screen.getByText("Edit Environment")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });
});
