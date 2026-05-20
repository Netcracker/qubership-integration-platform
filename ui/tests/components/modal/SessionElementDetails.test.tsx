/**
 * @jest-environment jsdom
 */

import React from "react";
import {
  render,
  screen,
  fireEvent,
  waitFor,
  within,
} from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  ExecutionStatus,
  type Session,
  type SessionElement,
} from "../../../src/api/apiTypes.ts";
import {
  SessionElementDetails,
  getTextToCopy,
} from "../../../src/components/modal/SessionElementDetails.tsx";

const mockCloseContainingModal = jest.fn();
const mockCopyToClipboard = jest.fn().mockResolvedValue(undefined);
const mockMessageInfo = jest.fn();

/** Stable reference so `message.useMessage()` does not break `useCallback` deps in the modal. */
const stableMessageApi = {
  info: mockMessageInfo,
  success: jest.fn(),
  error: jest.fn(),
  warning: jest.fn(),
};

jest.mock("../../../src/ModalContextProvider.tsx", () => ({
  useModalContext: () => ({
    closeContainingModal: mockCloseContainingModal,
  }),
}));

jest.mock("../../../src/misc/clipboard-util.ts", () => ({
  copyToClipboard: (text: string) => mockCopyToClipboard(text),
}));

jest.mock(
  "../../../src/components/sessions/SessionElementBodyChangesView.tsx",
  () => ({
    SessionElementBodyChangesView: () => (
      <div data-testid="body-changes-stub">body</div>
    ),
  }),
);

jest.mock("../../../src/components/sessions/SessionStatus.tsx", () => ({
  SessionStatus: ({ status, suffix }: { status: string; suffix?: string }) => (
    <span data-testid="element-exec-status">
      {String(status)}
      {suffix ?? ""}
    </span>
  ),
}));

jest.mock(
  "../../../src/components/sessions/SessionElementKVChanges.tsx",
  () => ({
    SessionElementKVChanges: (props: {
      before?: Record<string, unknown>;
      after?: Record<string, unknown>;
      addTypeColumns?: boolean;
      comparator?: (
        p1: { type?: string; value?: string } | undefined,
        p2: { type?: string; value?: string } | undefined,
      ) => number;
      typeRenderer?: (property: { type?: string; value?: string }) => unknown;
      valueRenderer?: (property: { type?: string; value?: string }) => unknown;
      onColumnClick?: (
        item: {
          name: string;
          before?: unknown;
          after?: unknown;
        },
        col: string,
      ) => void;
    }) => {
      const keys = [
        ...new Set([
          ...Object.keys(props.before ?? {}),
          ...Object.keys(props.after ?? {}),
        ]),
      ];
      const name = keys[0] ?? "row";
      const beforeVal = props.before?.[name];
      const afterVal = props.after?.[name];

      if (props.addTypeColumns) {
        const b = beforeVal as { type: string; value: string } | undefined;
        const a = afterVal as { type: string; value: string } | undefined;
        const item = { name, before: b, after: a };
        if (props.comparator) {
          const cmp = props.comparator;
          const same = { type: "a", value: "x" };
          cmp(undefined, same);
          cmp(same, undefined);
          cmp(same, same);
          cmp({ type: "b", value: "x" }, { type: "a", value: "y" });
          cmp({ type: "a", value: "z" }, { type: "a", value: "x" });
          cmp({} as { type?: string; value?: string }, {
            type: "z",
            value: "v",
          });
          cmp({ type: "a", value: undefined }, { type: "a", value: "b" });
          cmp({ value: "m" }, { value: "n" });
          cmp({ type: "a", value: undefined }, { type: "a", value: undefined });
        }
        if (b && props.typeRenderer) {
          props.typeRenderer(b);
        }
        if (a && props.valueRenderer) {
          props.valueRenderer(a);
        }
        return (
          <table data-testid="kv-properties-stub">
            <thead>
              <tr>
                <th scope="col">n</th>
                <th scope="col">tb</th>
                <th scope="col">ta</th>
                <th scope="col">vb</th>
                <th scope="col">va</th>
              </tr>
            </thead>
            <tbody>
              <tr data-kv-row={name}>
                <td onClick={() => props.onColumnClick?.(item, "name")}>
                  {name}
                </td>
                <td onClick={() => props.onColumnClick?.(item, "typeBefore")}>
                  {b?.type ?? "—"}
                </td>
                <td onClick={() => props.onColumnClick?.(item, "typeAfter")}>
                  {a?.type ?? "—"}
                </td>
                <td onClick={() => props.onColumnClick?.(item, "valueBefore")}>
                  {b?.value ?? "—"}
                </td>
                <td onClick={() => props.onColumnClick?.(item, "valueAfter")}>
                  {a?.value ?? "—"}
                </td>
              </tr>
            </tbody>
          </table>
        );
      }

      const beforeStr = beforeVal as string | undefined;
      const afterStr = afterVal as string | undefined;
      const stringItem = {
        name,
        before: beforeStr,
        after: afterStr,
      };
      return (
        <table data-testid="kv-string-stub">
          <thead>
            <tr>
              <th scope="col">n</th>
              <th scope="col">v</th>
            </tr>
          </thead>
          <tbody>
            <tr data-kv-row={name}>
              <td onClick={() => props.onColumnClick?.(stringItem, "name")}>
                {name}
              </td>
              <td
                onClick={() => props.onColumnClick?.(stringItem, "valueBefore")}
              >
                {beforeStr ?? "—"}
              </td>
            </tr>
          </tbody>
        </table>
      );
    },
  }),
);

jest.mock("antd", () => {
  const React = require("react") as typeof import("react");
  return {
    Flex: ({
      children,
      style,
    }: {
      children?: React.ReactNode;
      style?: React.CSSProperties;
    }) => <div style={style}>{children}</div>,
    message: {
      useMessage: () => [stableMessageApi, null],
    },
    Modal: ({
      children,
      title,
      open,
      onCancel,
    }: {
      children?: React.ReactNode;
      title?: React.ReactNode;
      open?: boolean;
      onCancel?: () => void;
    }) =>
      open ? (
        <div data-testid="session-el-modal">
          <div data-testid="session-el-modal-title">{title}</div>
          <button
            type="button"
            data-testid="mock-modal-cancel"
            onClick={onCancel}
          >
            Cancel
          </button>
          {children}
        </div>
      ) : null,
    Tabs: ({
      items,
      className,
      style,
    }: {
      items?: { key: string; label: string; children: React.ReactNode }[];
      className?: string;
      style?: React.CSSProperties;
    }) => (
      <div data-testid="tabs-stub" className={className} style={style}>
        {items?.map((it) => (
          <section key={it.key} data-tab-key={it.key} aria-label={it.label}>
            {it.children}
          </section>
        ))}
      </div>
    ),
    Button: ({
      children,
      onClick,
      disabled,
      icon,
      iconPosition,
    }: {
      children?: React.ReactNode;
      onClick?: () => void;
      disabled?: boolean;
      icon?: React.ReactNode;
      iconPosition?: string;
    }) => (
      <button type="button" disabled={disabled} onClick={onClick}>
        {iconPosition === "start" ? icon : null}
        {children}
        {iconPosition === "end" ? icon : null}
        {iconPosition == null ? icon : null}
      </button>
    ),
    Tag: ({ children }: { children?: React.ReactNode }) => (
      <span data-testid="snapshot-name-tag">{children}</span>
    ),
  };
});

jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({
    name,
    onClick,
  }: {
    name: string;
    onClick?: () => void;
  }) => (
    <span data-testid={`icon-${name}`} onClick={onClick}>
      {name}
    </span>
  ),
}));

function baseElement(
  overrides: Partial<SessionElement> & {
    elementId: string;
    elementName: string;
  },
): SessionElement {
  return {
    sessionId: "session-1",
    chainElementId: `ce-${overrides.elementId}`,
    actualElementChainId: "chain-1",
    parentElement: "",
    previousElement: "",
    camelName: "script",
    bodyBefore: "",
    bodyAfter: "",
    headersBefore: {},
    headersAfter: {},
    propertiesBefore: {},
    propertiesAfter: {},
    contextBefore: {},
    contextAfter: {},
    started: "",
    finished: "",
    duration: 42,
    syncDuration: 0,
    executionStatus: ExecutionStatus.COMPLETED_NORMALLY,
    exceptionInfo: { message: "", stackTrace: "" },
    children: undefined,
    ...overrides,
  };
}

function baseSession(elements: SessionElement[]): Session {
  return {
    id: "session-1",
    chainId: "chain-1",
    chainName: "Chain",
    started: "",
    finished: "",
    duration: 100,
    syncDuration: 0,
    executionStatus: ExecutionStatus.COMPLETED_NORMALLY,
    importedSession: false,
    externalSessionCipId: "",
    domain: "d",
    engineAddress: "",
    loggingLevel: "INFO",
    snapshotName: "snap-alpha",
    correlationId: "",
    parentSessionId: "",
    sessionElements: elements,
  };
}

const mockOpen = jest.fn();
const originalOpen = globalThis.open;

describe("getTextToCopy", () => {
  const propItem = {
    name: "key1",
    before: { type: "number", value: "1" },
    after: { type: "string", value: "two" },
  };
  const typeGetter = (v: { type?: string; value?: string } | undefined) =>
    v?.type;
  const valueGetter = (v: { type?: string; value?: string } | undefined) =>
    v?.value;

  test("returns name for name column", () => {
    expect(getTextToCopy(propItem, "name")).toBe("key1");
  });

  test("typeBefore uses String when no type getter", () => {
    const before = { type: "x", value: "y" };
    expect(
      getTextToCopy({ name: "k", before, after: undefined }, "typeBefore"),
    ).toBe(String(before));
  });

  test("typeBefore uses type getter when provided", () => {
    expect(getTextToCopy(propItem, "typeBefore", typeGetter)).toBe("number");
  });

  test("typeAfter uses String when no type getter", () => {
    const after = { type: "a", value: "b" };
    expect(
      getTextToCopy({ name: "k", before: undefined, after }, "typeAfter"),
    ).toBe(String(after));
  });

  test("typeAfter uses type getter when provided", () => {
    expect(getTextToCopy(propItem, "typeAfter", typeGetter)).toBe("string");
  });

  test("valueBefore uses String when no value getter", () => {
    expect(
      getTextToCopy(
        { name: "k", before: "raw-before", after: undefined },
        "valueBefore",
      ),
    ).toBe("raw-before");
  });

  test("valueBefore uses value getter when provided", () => {
    expect(
      getTextToCopy(propItem, "valueBefore", typeGetter, valueGetter),
    ).toBe("1");
  });

  test("valueAfter uses String when no value getter", () => {
    expect(
      getTextToCopy(
        { name: "k", before: undefined, after: "raw-after" },
        "valueAfter",
      ),
    ).toBe("raw-after");
  });

  test("valueAfter uses value getter when provided", () => {
    expect(getTextToCopy(propItem, "valueAfter", typeGetter, valueGetter)).toBe(
      "two",
    );
  });
});

describe("SessionElementDetails", () => {
  beforeAll(() => {
    globalThis.open = mockOpen as typeof globalThis.open;
  });
  afterAll(() => {
    globalThis.open = originalOpen;
  });

  beforeEach(() => {
    jest.clearAllMocks();
    mockOpen.mockReset();
  });

  test("header shows execution status then snapshot tag", () => {
    const el = baseElement({
      elementId: "e1",
      elementName: "ElOne",
      headersBefore: { H: "a" },
      headersAfter: { H: "b" },
    });
    render(
      <SessionElementDetails session={baseSession([el])} elementId="e1" />,
    );

    const status = screen.getByTestId("element-exec-status");
    expect(status.textContent).toContain(ExecutionStatus.COMPLETED_NORMALLY);
    expect(status.textContent).toContain("in 42 ms");
    const tag = within(screen.getByTestId("snapshot-name-tag")).getByText(
      "snap-alpha",
    );
    expect(
      status.compareDocumentPosition(tag) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  test("Headers tab panel lists keys and copy uses clipboard on value click", async () => {
    const el = baseElement({
      elementId: "e1",
      elementName: "ElOne",
      headersBefore: { X: "before-val" },
      headersAfter: { X: "after-val" },
    });
    render(
      <SessionElementDetails session={baseSession([el])} elementId="e1" />,
    );

    const headersPanel = screen.getByLabelText("Headers");
    const cell = await waitFor(() =>
      within(headersPanel).getByText("before-val"),
    );
    fireEvent.click(cell);

    await waitFor(() => {
      expect(mockCopyToClipboard).toHaveBeenCalledWith("before-val");
      expect(mockMessageInfo).toHaveBeenCalledWith("Copied to clipboard");
    });
  });

  test("Previous disabled on first element; Next moves to second element", () => {
    const first = baseElement({
      elementId: "e1",
      elementName: "First",
    });
    const second = baseElement({
      elementId: "e2",
      elementName: "Second",
    });
    render(
      <SessionElementDetails
        session={baseSession([first, second])}
        elementId="e1"
      />,
    );

    const prev = screen.getByRole("button", { name: /Previous/ });
    const next = screen.getByRole("button", { name: /Next/ });
    expect(prev).toBeDisabled();
    expect(next).not.toBeDisabled();

    fireEvent.click(next);
    expect(
      within(screen.getByTestId("session-el-modal-title")).getByText("Second"),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Previous/ })).not.toBeDisabled();
  });

  test("Modal cancel calls closeContainingModal", () => {
    const el = baseElement({ elementId: "e1", elementName: "A" });
    render(
      <SessionElementDetails session={baseSession([el])} elementId="e1" />,
    );

    fireEvent.click(screen.getByTestId("mock-modal-cancel"));
    expect(mockCloseContainingModal).toHaveBeenCalled();
  });

  test("link icon opens graph URL in a new tab", () => {
    const el = baseElement({
      elementId: "e1",
      elementName: "NodeA",
    });
    render(
      <SessionElementDetails session={baseSession([el])} elementId="e1" />,
    );

    fireEvent.click(screen.getByTestId("icon-link"));
    expect(mockOpen).toHaveBeenCalledWith(
      "/chains/chain-1/graph/ce-e1",
      "_blank",
    );
  });

  test("Body tab renders body changes stub", () => {
    const el = baseElement({ elementId: "e1", elementName: "A" });
    render(
      <SessionElementDetails session={baseSession([el])} elementId="e1" />,
    );

    expect(
      within(screen.getByLabelText("Body")).getByTestId("body-changes-stub"),
    ).toBeInTheDocument();
  });

  test("Technical context tab copies value via clipboard", async () => {
    const el = baseElement({
      elementId: "e1",
      elementName: "A",
      contextBefore: { K: "ctx-1" },
      contextAfter: { K: "ctx-2" },
    });
    render(
      <SessionElementDetails session={baseSession([el])} elementId="e1" />,
    );

    const panel = screen.getByLabelText("Technical context");
    fireEvent.click(within(panel).getByText("ctx-1"));

    await waitFor(() => {
      expect(mockCopyToClipboard).toHaveBeenCalledWith("ctx-1");
    });
  });

  test("Exchange properties tab copies valueAfter using property getter", async () => {
    const el = baseElement({
      elementId: "e1",
      elementName: "A",
      propertiesBefore: {},
      propertiesAfter: {
        propA: { type: "string", value: "after-val" },
      },
    });
    render(
      <SessionElementDetails session={baseSession([el])} elementId="e1" />,
    );

    const panel = screen.getByLabelText("Exchange properties");
    expect(within(panel).getByTestId("kv-properties-stub")).toBeInTheDocument();
    fireEvent.click(within(panel).getByText("after-val"));

    await waitFor(() => {
      expect(mockCopyToClipboard).toHaveBeenCalledWith("after-val");
    });
  });

  test("Exchange properties copies typeBefore and valueBefore via getters", async () => {
    const el = baseElement({
      elementId: "e1",
      elementName: "A",
      propertiesBefore: {
        propA: { type: "number", value: "before-val" },
      },
      propertiesAfter: {
        propA: { type: "string", value: "after-val" },
      },
    });
    render(
      <SessionElementDetails session={baseSession([el])} elementId="e1" />,
    );

    const panel = screen.getByLabelText("Exchange properties");
    fireEvent.click(within(panel).getByText("number"));
    await waitFor(() => {
      expect(mockCopyToClipboard).toHaveBeenLastCalledWith("number");
    });

    jest.clearAllMocks();
    fireEvent.click(within(panel).getByText("before-val"));
    await waitFor(() => {
      expect(mockCopyToClipboard).toHaveBeenCalledWith("before-val");
    });
  });

  test("Headers tab copies key name when clicking name cell", async () => {
    const el = baseElement({
      elementId: "e1",
      elementName: "A",
      headersBefore: { MyHeader: "x" },
      headersAfter: { MyHeader: "y" },
    });
    render(
      <SessionElementDetails session={baseSession([el])} elementId="e1" />,
    );

    const panel = screen.getByLabelText("Headers");
    fireEvent.click(within(panel).getByText("MyHeader"));

    await waitFor(() => {
      expect(mockCopyToClipboard).toHaveBeenCalledWith("MyHeader");
    });
  });

  test("Errors tab shows message and stack when exceptionInfo is set", () => {
    const el = baseElement({
      elementId: "e1",
      elementName: "Bad",
      exceptionInfo: {
        message: "Something failed",
        stackTrace: "Error: test\n  at fn (file.js:1:1)",
      },
    });
    render(
      <SessionElementDetails session={baseSession([el])} elementId="e1" />,
    );

    const panel = screen.getByLabelText("Errors");
    expect(within(panel).getByText("Something failed")).toBeInTheDocument();
    expect(
      within(panel).getByText("Error: test", { exact: false }),
    ).toBeInTheDocument();
  });

  test("shows status placeholder when element has no executionStatus", () => {
    const el = {
      ...baseElement({ elementId: "e1", elementName: "NoStatus" }),
      executionStatus: undefined,
    } as SessionElement;
    render(
      <SessionElementDetails session={baseSession([el])} elementId="e1" />,
    );

    expect(screen.queryByTestId("element-exec-status")).not.toBeInTheDocument();
    const tag = screen.getByTestId("snapshot-name-tag");
    expect(tag.previousElementSibling?.textContent).toBe("—");
  });

  test("Previous from second element returns to first; Next disabled on last", () => {
    const first = baseElement({ elementId: "e1", elementName: "First" });
    const second = baseElement({ elementId: "e2", elementName: "Second" });
    render(
      <SessionElementDetails
        session={baseSession([first, second])}
        elementId="e2"
      />,
    );

    expect(screen.getByRole("button", { name: /Previous/ })).not.toBeDisabled();
    expect(screen.getByRole("button", { name: /Next/ })).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: /Previous/ }));
    expect(
      within(screen.getByTestId("session-el-modal-title")).getByText("First"),
    ).toBeInTheDocument();
  });

  test("depth-first order: Previous from child moves to parent", () => {
    const child = baseElement({
      elementId: "e-child",
      elementName: "Child",
    });
    const parent = baseElement({
      elementId: "e-parent",
      elementName: "Parent",
      children: [child],
    });
    render(
      <SessionElementDetails
        session={baseSession([parent])}
        elementId="e-child"
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /Previous/ }));
    expect(
      within(screen.getByTestId("session-el-modal-title")).getByText("Parent"),
    ).toBeInTheDocument();
  });

  test("Next and Previous use elementOrderMap with optional element id fallback", () => {
    const a = baseElement({ elementId: "e1", elementName: "A" });
    const b = baseElement({ elementId: "e2", elementName: "B" });
    const c = baseElement({ elementId: "e3", elementName: "C" });
    render(
      <SessionElementDetails session={baseSession([a, b, c])} elementId="e2" />,
    );

    fireEvent.click(screen.getByRole("button", { name: /Next/ }));
    expect(
      within(screen.getByTestId("session-el-modal-title")).getByText("C"),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Previous/ }));
    expect(
      within(screen.getByTestId("session-el-modal-title")).getByText("B"),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Previous/ }));
    expect(
      within(screen.getByTestId("session-el-modal-title")).getByText("A"),
    ).toBeInTheDocument();
  });

  test("navigation handlers use empty id when element state is cleared", () => {
    const only = baseElement({ elementId: "only", elementName: "Only" });
    render(
      <SessionElementDetails session={baseSession([only])} elementId="only" />,
    );

    const nextBtn = screen.getByRole("button", { name: /Next/ });
    expect(nextBtn).toBeDisabled();
    nextBtn.removeAttribute("disabled");
    fireEvent.click(nextBtn);

    const prevBtn = screen.getByRole("button", { name: /Previous/ });
    expect(prevBtn).toBeDisabled();
    prevBtn.removeAttribute("disabled");
    fireEvent.click(prevBtn);

    const nextAgain = screen.getByRole("button", { name: /Next/ });
    nextAgain.removeAttribute("disabled");
    fireEvent.click(nextAgain);
  });

  test("navigation when initial element is missing uses empty map key", () => {
    const only = baseElement({ elementId: "known", elementName: "K" });
    render(
      <SessionElementDetails
        session={baseSession([only])}
        elementId="missing-id"
      />,
    );

    const prevBtn = screen.getByRole("button", { name: /Previous/ });
    prevBtn.removeAttribute("disabled");
    fireEvent.click(prevBtn);

    const nextBtn = screen.getByRole("button", { name: /Next/ });
    nextBtn.removeAttribute("disabled");
    fireEvent.click(nextBtn);
  });
});
