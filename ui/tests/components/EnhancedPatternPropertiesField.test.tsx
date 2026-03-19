/**
 * @jest-environment jsdom
 */

Object.defineProperty(window, "matchMedia", {
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

import { describe, it, expect, beforeEach } from "@jest/globals";
import {
  render,
  screen,
  fireEvent,
  waitFor,
  act,
} from "@testing-library/react";
import "@testing-library/jest-dom";
import type { FieldProps, RJSFSchema } from "@rjsf/utils";
import type { FormContext } from "../../src/components/modal/chain_element/ChainElementModificationContext";

// --- Mocks ---

const mockGetOperationInfo = jest.fn();
const mockGetService = jest.fn();
const mockGetEnvironments = jest.fn();

jest.mock("../../src/api/api", () => ({
  api: {
    getOperationInfo: (...args: unknown[]) => mockGetOperationInfo(...args),
    getService: (...args: unknown[]) => mockGetService(...args),
    getEnvironments: (...args: unknown[]) => mockGetEnvironments(...args),
  },
}));

jest.mock("../../src/icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

jest.mock(
  "../../src/components/modal/chain_element/field/EnhancedPatternPropertiesField.module.css",
  () => ({
    __esModule: true,
    default: {
      header: "header",
      leftHeader: "leftHeader",
      iconWrapper: "iconWrapper",
      badge: "badge",
      table: "table",
      th: "th",
      td: "td",
      nameCell: "nameCell",
      paramName: "paramName",
      labels: "labels",
      overriddenTag: "overriddenTag",
      deprecatedTag: "deprecatedTag",
      noEntries: "noEntries",
      actions: "actions",
      restoreBtn: "restoreBtn",
      deleteBtn: "deleteBtn",
      requiredRow: "requiredRow",
    },
  }),
);

jest.mock(
  "../../src/components/modal/chain_element/DescriptionTooltipFieldTemplate",
  () => ({
    DescriptionTooltipIcon: ({ description }: { description: string }) => (
      <span data-testid="desc-tooltip">{description}</span>
    ),
  }),
);

jest.mock(
  "../../src/components/modal/chain_element/field/QueryParametersCheckbox",
  () => ({
    QueryParametersCheckbox: () => <div data-testid="query-checkbox" />,
  }),
);

import EnhancedPatternPropertiesField from "../../src/components/modal/chain_element/field/EnhancedPatternPropertiesField";

// --- Helpers ---

type EnhancedFieldProps = FieldProps<
  Record<string, string>,
  RJSFSchema,
  FormContext
>;

function makeProps(
  overrides: Partial<EnhancedFieldProps> & {
    formContext?: Partial<FormContext>;
  } = {},
): EnhancedFieldProps {
  const { formContext: fcOverrides, ...rest } = overrides;
  const mockUpdateContext = jest.fn();
  return {
    name: "integrationOperationAsyncProperties",
    formData: {},
    onChange: jest.fn(),
    disabled: false,
    readonly: false,
    schema: { type: "object" } as RJSFSchema,
    uiSchema: {},
    registry: {
      formContext: {
        elementType: "service-call",
        integrationOperationProtocolType: "kafka",
        integrationOperationId: "op-1",
        integrationSystemId: "sys-1",
        updateContext: mockUpdateContext,
        reportMissingRequiredParams: jest.fn(),
        ...fcOverrides,
      } as FormContext,
    } as EnhancedFieldProps["registry"],
    fieldPathId: {
      $id: "root_integrationOperationAsyncProperties",
      path: "root_integrationOperationAsyncProperties",
    },
    ...rest,
  } as EnhancedFieldProps;
}

function expandSection(container: HTMLElement) {
  const header = container.querySelector(".leftHeader");
  if (header) {
    fireEvent.click(header);
  }
}

// --- Tests ---

describe("EnhancedPatternPropertiesField", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.useFakeTimers();
    mockGetOperationInfo.mockResolvedValue({
      specification: {
        topic: "spec-topic",
        channel: null,
        exchange: null,
        queues: null,
        maasClassifierName: null,
        groupId: null,
      },
    });
    mockGetService.mockResolvedValue({
      id: "sys-1",
      name: "TestService",
      activeEnvironmentId: "env-1",
    });
    mockGetEnvironments.mockResolvedValue([
      {
        id: "env-1",
        sourceType: "MANUAL",
        properties: {},
      },
    ]);
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  describe("pathMapped parameters", () => {
    it("should render topic with value from integrationOperationPath for kafka", async () => {
      const props = makeProps({
        formContext: {
          integrationOperationProtocolType: "kafka",
          integrationOperationPath: "my-topic-value",
        },
      });

      let container!: HTMLElement;
      await act(async () => {
        const result = render(<EnhancedPatternPropertiesField {...props} />);
        container = result.container;
      });

      expandSection(container);

      await waitFor(() => {
        expect(screen.getByDisplayValue("my-topic-value")).toBeTruthy();
      });
    });

    it("should render exchange with value from integrationOperationPath for amqp", async () => {
      const props = makeProps({
        formContext: {
          integrationOperationProtocolType: "amqp",
          integrationOperationPath: "my-exchange-value",
        },
      });

      mockGetOperationInfo.mockResolvedValue({
        specification: {
          exchange: "spec-exchange",
          queues: null,
          maasClassifierName: null,
        },
      });

      let container!: HTMLElement;
      await act(async () => {
        const result = render(<EnhancedPatternPropertiesField {...props} />);
        container = result.container;
      });

      expandSection(container);

      await waitFor(() => {
        expect(screen.getByDisplayValue("my-exchange-value")).toBeTruthy();
      });
    });

    it("should call updateContext with integrationOperationPath when topic value changes", async () => {
      const props = makeProps({
        formContext: {
          integrationOperationProtocolType: "kafka",
          integrationOperationPath: "old-topic",
        },
      });
      const updateContext = props.registry.formContext.updateContext!;

      let container!: HTMLElement;
      await act(async () => {
        const result = render(<EnhancedPatternPropertiesField {...props} />);
        container = result.container;
      });

      expandSection(container);

      await waitFor(() => {
        expect(screen.getByDisplayValue("old-topic")).toBeTruthy();
      });

      const topicInput = screen.getByDisplayValue("old-topic");
      fireEvent.change(topicInput, { target: { value: "new-topic" } });

      // Flush debounce timer
      act(() => {
        jest.advanceTimersByTime(300);
      });

      expect(updateContext).toHaveBeenCalledWith({
        integrationOperationPath: "new-topic",
      });
      // Should NOT call onChange (formData should not change for pathMapped params)
      expect(props.onChange).not.toHaveBeenCalled();
    });

    it("should call updateContext with integrationOperationPath when exchange value changes", async () => {
      const props = makeProps({
        formContext: {
          integrationOperationProtocolType: "amqp",
          integrationOperationPath: "old-exchange",
        },
      });
      const updateContext = props.registry.formContext.updateContext!;

      mockGetOperationInfo.mockResolvedValue({
        specification: {
          exchange: "spec-exchange",
          queues: null,
          maasClassifierName: null,
        },
      });

      let container!: HTMLElement;
      await act(async () => {
        const result = render(<EnhancedPatternPropertiesField {...props} />);
        container = result.container;
      });

      expandSection(container);

      await waitFor(() => {
        expect(screen.getByDisplayValue("old-exchange")).toBeTruthy();
      });

      const exchangeInput = screen.getByDisplayValue("old-exchange");
      fireEvent.change(exchangeInput, {
        target: { value: "new-exchange" },
      });

      act(() => {
        jest.advanceTimersByTime(300);
      });

      expect(updateContext).toHaveBeenCalledWith({
        integrationOperationPath: "new-exchange",
      });
    });

    it("should not allow deleting pathMapped parameters", async () => {
      const props = makeProps({
        formContext: {
          integrationOperationProtocolType: "kafka",
          integrationOperationPath: "my-topic",
        },
      });

      let container!: HTMLElement;
      await act(async () => {
        const result = render(<EnhancedPatternPropertiesField {...props} />);
        container = result.container;
      });

      expandSection(container);

      await waitFor(() => {
        expect(screen.getByDisplayValue("my-topic")).toBeTruthy();
      });

      // topic row should not have a delete button
      const topicRow = screen.getByDisplayValue("my-topic").closest("tr");
      const deleteBtn = topicRow?.querySelector('[data-testid="icon-delete"]');
      expect(deleteBtn).toBeNull();
    });

    it("should hide topic in MaaS environment", async () => {
      const props = makeProps({
        formContext: {
          integrationOperationProtocolType: "kafka",
          integrationOperationPath: "my-topic",
        },
      });

      mockGetEnvironments.mockResolvedValue([
        {
          id: "env-1",
          sourceType: "MAAS_BY_CLASSIFIER",
          properties: {},
        },
      ]);

      let container!: HTMLElement;
      await act(async () => {
        const result = render(<EnhancedPatternPropertiesField {...props} />);
        container = result.container;
      });

      expandSection(container);

      // Wait for environment to load and topic to be hidden
      await waitFor(() => {
        expect(screen.queryByDisplayValue("my-topic")).toBeNull();
      });
    });
  });

  describe("auto-fill for pathMapped parameters", () => {
    it("should auto-fill integrationOperationPath with topic from spec for kafka", async () => {
      const props = makeProps({
        formContext: {
          integrationOperationProtocolType: "kafka",
          integrationOperationPath: "",
          integrationOperationId: "op-1",
        },
      });
      const updateContext = props.registry.formContext.updateContext!;

      mockGetOperationInfo.mockResolvedValue({
        specification: {
          topic: "auto-topic",
          channel: null,
          maasClassifierName: null,
          groupId: null,
        },
      });

      await act(async () => {
        render(<EnhancedPatternPropertiesField {...props} />);
      });

      await waitFor(() => {
        expect(updateContext).toHaveBeenCalledWith({
          integrationOperationPath: "auto-topic",
        });
      });
    });

    it("should auto-fill integrationOperationPath with exchange from spec for amqp", async () => {
      const props = makeProps({
        formContext: {
          integrationOperationProtocolType: "amqp",
          integrationOperationPath: "",
          integrationOperationId: "op-1",
        },
      });
      const updateContext = props.registry.formContext.updateContext!;

      mockGetOperationInfo.mockResolvedValue({
        specification: {
          exchange: "auto-exchange",
          queues: null,
          maasClassifierName: null,
        },
      });

      await act(async () => {
        render(<EnhancedPatternPropertiesField {...props} />);
      });

      await waitFor(() => {
        expect(updateContext).toHaveBeenCalledWith({
          integrationOperationPath: "auto-exchange",
        });
      });
    });

    it("should not overwrite existing integrationOperationPath on auto-fill", async () => {
      const props = makeProps({
        formContext: {
          integrationOperationProtocolType: "kafka",
          integrationOperationPath: "existing-topic",
          integrationOperationId: "op-1",
        },
      });
      const updateContext = props.registry.formContext.updateContext!;

      mockGetOperationInfo.mockResolvedValue({
        specification: {
          topic: "new-topic-from-spec",
          maasClassifierName: null,
          groupId: null,
        },
      });

      await act(async () => {
        render(<EnhancedPatternPropertiesField {...props} />);
      });

      // updateContext should NOT be called with integrationOperationPath
      // since it already has a value
      await waitFor(() => {
        const calls = (updateContext as jest.Mock).mock.calls;
        const pathCalls = calls.filter(
          (c: unknown[]) =>
            c[0] &&
            typeof c[0] === "object" &&
            "integrationOperationPath" in (c[0] as Record<string, unknown>),
        );
        expect(pathCalls).toHaveLength(0);
      });
    });
  });

  describe("non-pathMapped parameters", () => {
    it("should store groupId in formData (not in context)", async () => {
      const props = makeProps({
        formData: {},
        formContext: {
          elementType: "async-api-trigger",
          integrationOperationProtocolType: "kafka",
          integrationOperationPath: "topic",
          integrationOperationId: "op-1",
        },
      });

      mockGetOperationInfo.mockResolvedValue({
        specification: {
          topic: "t",
          groupId: "auto-group",
          maasClassifierName: null,
        },
      });

      await act(async () => {
        render(<EnhancedPatternPropertiesField {...props} />);
      });

      await waitFor(() => {
        expect(props.onChange).toHaveBeenCalled();
        const lastCall = (props.onChange as jest.Mock).mock.calls.at(-1);
        const formDataArg = lastCall?.[0] as Record<string, string>;
        expect(formDataArg).toHaveProperty("groupId", "auto-group");
        // topic should NOT be in formData
        expect(formDataArg).not.toHaveProperty("topic");
      });
    });

    it("should use handleValueChange (emitChange) for regular params like queues", async () => {
      const onChangeFn = jest.fn();
      const props = makeProps({
        formData: { queues: "old-queue" },
        onChange: onChangeFn,
        formContext: {
          integrationOperationProtocolType: "amqp",
          integrationOperationPath: "exchange-val",
          integrationOperationId: "op-1",
        },
      });

      mockGetOperationInfo.mockResolvedValue({
        specification: {
          exchange: "ex",
          queues: null,
          maasClassifierName: null,
        },
      });

      let container!: HTMLElement;
      await act(async () => {
        const result = render(<EnhancedPatternPropertiesField {...props} />);
        container = result.container;
      });

      // Section should be expanded because formData has entries
      await waitFor(() => {
        expect(screen.getByDisplayValue("old-queue")).toBeTruthy();
      });

      // Clear any onChange calls from auto-fill
      onChangeFn.mockClear();

      const queuesInput = screen.getByDisplayValue("old-queue");
      fireEvent.change(queuesInput, { target: { value: "new-queue" } });

      act(() => {
        jest.advanceTimersByTime(300);
      });

      // Should call onChange (emitChange) for regular params
      expect(onChangeFn).toHaveBeenCalled();
      const lastCall = onChangeFn.mock.calls.at(-1);
      const formDataArg = lastCall?.[0] as Record<string, string>;
      expect(formDataArg).toHaveProperty("queues", "new-queue");
    });
  });

  describe("MaaS cleanup", () => {
    it("should not delete topic from formData in MaaS mode (topic is pathMapped, not in formData)", async () => {
      const props = makeProps({
        formData: { "maas.classifier.name": "cls" },
        formContext: {
          integrationOperationProtocolType: "kafka",
          integrationOperationPath: "topic-val",
          integrationOperationId: "op-1",
        },
      });

      mockGetEnvironments.mockResolvedValue([
        {
          id: "env-1",
          sourceType: "MAAS_BY_CLASSIFIER",
          properties: {},
        },
      ]);

      await act(async () => {
        render(<EnhancedPatternPropertiesField {...props} />);
      });

      // onChange should not be called to delete topic (since topic is not in formData)
      await waitFor(() => {
        const calls = (props.onChange as jest.Mock).mock.calls;
        const deleteCalls = calls.filter((c: unknown[]) => {
          const data = c[0] as Record<string, string>;
          return data && "topic" in data;
        });
        expect(deleteCalls).toHaveLength(0);
      });
    });
  });
});
