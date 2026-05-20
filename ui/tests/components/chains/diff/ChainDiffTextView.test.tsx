/**
 * @jest-environment jsdom
 */

import { render } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { Chain, Dependency, Element } from "../../../../src/api/apiTypes";

jest.mock("../../../../src/hooks/useMonacoTheme", () => ({
  useMonacoTheme: jest.fn(() => "vs"),
}));

jest.mock("../../../../src/components/chains/diff/compare/compare", () => ({
  ...jest.requireActual("../../../../src/components/chains/diff/compare/compare"),
  buildElementMap: jest.fn(() => new Map()),
}));

type DiffEditorProps = {
  original?: string;
  modified?: string;
  originalLanguage?: string;
  modifiedLanguage?: string;
  theme?: string;
  className?: string;
  options?: Record<string, unknown>;
};

let capturedDiffEditorProps: DiffEditorProps | null = null;

jest.mock("@monaco-editor/react", () => {
  const actualReact = jest.requireActual<typeof import("react")>("react");
  return {
    __esModule: true,
    DiffEditor: (props: DiffEditorProps) => {
      capturedDiffEditorProps = props;
      return actualReact.createElement("div", { "data-testid": "diff-editor" });
    },
  };
});

import { useMonacoTheme } from "../../../../src/hooks/useMonacoTheme";
import { buildElementMap } from "../../../../src/components/chains/diff/compare/compare";
import {
  ChainDiffTextView,
  dumpYaml,
} from "../../../../src/components/chains/diff/ChainDiffTextView";

const mockUseMonacoTheme = useMonacoTheme as jest.Mock;
const mockBuildElementMap = buildElementMap as jest.Mock;

function makeChain(overrides: Partial<Chain> = {}): Chain {
  return {
    id: "chain-1",
    name: "Chain",
    description: "",
    navigationPath: [],
    elements: [],
    dependencies: [],
    deployments: [],
    labels: [],
    defaultSwimlaneId: "swimlane-1",
    reuseSwimlaneId: "swimlane-2",
    unsavedChanges: false,
    businessDescription: "",
    assumptions: "",
    outOfScope: "",
    containsDeprecatedContainers: false,
    containsDeprecatedElements: false,
    containsUnsupportedElements: false,
    ...overrides,
  };
}

function makeElement(overrides: Partial<Element> = {}): Element {
  return {
    id: "e1",
    name: "Element",
    description: "",
    type: "script",
    chainId: "chain-1",
    properties: {} as never,
    mandatoryChecksPassed: true,
    ...overrides,
  };
}

function makeDependency(id: string, from: string, to: string): Dependency {
  return { id, from, to };
}

describe("dumpYaml", () => {
  it("should omit all IGNORED_PROPERTIES from the output", () => {
    const element = makeElement({ swimlaneId: "swim-1" });
    const chain: Chain = {
      ...makeChain({
        parentId: "parent-1",
        deployments: [{ id: "dep-1" } as never],
        containsDeprecatedContainers: true,
        containsDeprecatedElements: true,
        containsUnsupportedElements: true,
        unsavedChanges: true,
        elements: [element],
      }),
      createdBy: "user" as never,
      createdWhen: 1000,
      modifiedBy: "user" as never,
      modifiedWhen: 2000,
    };

    const result = dumpYaml(chain, new Map());

    const ignoredKeys = [
      "deployments",
      "containsDeprecatedContainers",
      "containsDeprecatedElements",
      "containsUnsupportedElements",
      "unsavedChanges",
      "navigationPath",
      "createdBy",
      "createdWhen",
      "modifiedBy",
      "modifiedWhen",
      "chainId",
      "mandatoryChecksPassed",
      "swimlaneId",
      "parentId",
    ];
    for (const key of ignoredKeys) {
      expect(result).not.toContain(`${key}:`);
    }
    expect(result).not.toMatch(/\bid:/m);
  });

  it("should replace the from field value with 'name (type)' when the referenced element exists", () => {
    const element = makeElement({ id: "e1", name: "HTTP Trigger", type: "http-trigger" });
    const chain = makeChain({
      elements: [element],
      dependencies: [makeDependency("conn-1", "e1", "e2")],
    });

    const result = dumpYaml(chain, new Map());

    expect(result).toContain("from: HTTP Trigger (http-trigger)");
  });

  it("should replace the to field value with 'name (type)' when the referenced element exists", () => {
    const element = makeElement({ id: "e2", name: "Service Call", type: "service-call" });
    const chain = makeChain({
      elements: [element],
      dependencies: [makeDependency("conn-1", "e1", "e2")],
    });

    const result = dumpYaml(chain, new Map());

    expect(result).toContain("to: Service Call (service-call)");
  });

  it("should keep the original value for from when no element with that id is found", () => {
    const chain = makeChain({
      elements: [],
      dependencies: [makeDependency("conn-1", "non-existent-id", "e2")],
    });

    const result = dumpYaml(chain, new Map());

    expect(result).toContain("from: non-existent-id");
  });

  it("should sort the elements array using the elementMap ordering", () => {
    const elemZ = makeElement({ id: "elem-z", name: "ZZZ-element", type: "script" });
    const elemA = makeElement({ id: "elem-a", name: "AAA-element", type: "script" });
    const chain = makeChain({ elements: [elemZ, elemA] });
    // elem-z maps to "aaa" (sorts first), elem-a maps to "zzz" (sorts second)
    const elementMap = new Map([
      ["elem-z", "aaa"],
      ["elem-a", "zzz"],
    ]);

    const result = dumpYaml(chain, elementMap);

    expect(result.indexOf("ZZZ-element")).toBeLessThan(result.indexOf("AAA-element"));
  });

  it("should fall back to element id for sorting when element is not in the elementMap", () => {
    const elemZ = makeElement({ id: "elem-z", name: "ZZZ-element", type: "script" });
    const elemA = makeElement({ id: "elem-a", name: "AAA-element", type: "script" });
    const chain = makeChain({ elements: [elemZ, elemA] });

    const result = dumpYaml(chain, new Map());

    // "elem-a" < "elem-z" alphabetically → AAA-element appears first
    expect(result.indexOf("AAA-element")).toBeLessThan(result.indexOf("ZZZ-element"));
  });

  it("should produce YAML with keys sorted alphabetically", () => {
    const chain = makeChain({
      name: "My Chain",
      description: "A description",
      businessDescription: "Business Desc",
    });

    const result = dumpYaml(chain, new Map());

    // businessDescription < description < name (alphabetical order)
    expect(result.indexOf("businessDescription:")).toBeLessThan(result.indexOf("description:"));
    expect(result.indexOf("description:")).toBeLessThan(result.indexOf("name:"));
  });
});

describe("ChainDiffTextView", () => {
  beforeEach(() => {
    capturedDiffEditorProps = null;
    mockUseMonacoTheme.mockReturnValue("vs");
    mockBuildElementMap.mockReturnValue(new Map());
  });

  it("should render DiffEditor with empty original and modified when neither chain is provided", () => {
    render(<ChainDiffTextView changes={[]} onSelectChange={jest.fn()} />);

    expect(capturedDiffEditorProps!.original).toBe("");
    expect(capturedDiffEditorProps!.modified).toBe("");
  });

  it("should render DiffEditor with non-empty original and empty modified when only chain1 is provided", () => {
    const chain1 = makeChain({ id: "c1", name: "Chain 1" });

    render(<ChainDiffTextView chain1={chain1} changes={[]} onSelectChange={jest.fn()} />);

    expect(capturedDiffEditorProps!.original).toBe(dumpYaml(chain1, new Map()));
    expect(capturedDiffEditorProps!.modified).toBe("");
  });

  it("should render DiffEditor with empty original and non-empty modified when only chain2 is provided", () => {
    const chain2 = makeChain({ id: "c2", name: "Chain 2" });

    render(<ChainDiffTextView chain2={chain2} changes={[]} onSelectChange={jest.fn()} />);

    expect(capturedDiffEditorProps!.original).toBe("");
    expect(capturedDiffEditorProps!.modified).toBe(dumpYaml(chain2, new Map()));
  });

  it("should render DiffEditor with non-empty original and modified when both chains are provided", () => {
    const chain1 = makeChain({ id: "c1", name: "Chain 1" });
    const chain2 = makeChain({ id: "c2", name: "Chain 2" });

    render(
      <ChainDiffTextView chain1={chain1} chain2={chain2} changes={[]} onSelectChange={jest.fn()} />,
    );

    expect(capturedDiffEditorProps!.original).not.toBe("");
    expect(capturedDiffEditorProps!.modified).not.toBe("");
  });

  it("should pass the theme returned by useMonacoTheme to DiffEditor", () => {
    mockUseMonacoTheme.mockReturnValue("vs-dark");

    render(<ChainDiffTextView changes={[]} onSelectChange={jest.fn()} />);

    expect(capturedDiffEditorProps!.theme).toBe("vs-dark");
  });

  it("should pass readOnly: true in the DiffEditor options", () => {
    render(<ChainDiffTextView changes={[]} onSelectChange={jest.fn()} />);

    expect(capturedDiffEditorProps!.options?.readOnly).toBe(true);
  });

  it("should pass yaml as both originalLanguage and modifiedLanguage to DiffEditor", () => {
    render(<ChainDiffTextView changes={[]} onSelectChange={jest.fn()} />);

    expect(capturedDiffEditorProps!.originalLanguage).toBe("yaml");
    expect(capturedDiffEditorProps!.modifiedLanguage).toBe("yaml");
  });

  it("should use the result of buildElementMap as the element map for chain2 YAML when both chains are provided", () => {
    const elemZ = makeElement({ id: "elem-z", name: "ZZZ-element", type: "script" });
    const elemA = makeElement({ id: "elem-a", name: "AAA-element", type: "script" });
    const chain1 = makeChain({ id: "c1" });
    const chain2 = makeChain({ id: "c2", elements: [elemZ, elemA] });
    // elem-z maps to "aaa" (sorts first), elem-a maps to "zzz" (sorts second)
    mockBuildElementMap.mockReturnValue(new Map([["elem-z", "aaa"], ["elem-a", "zzz"]]));

    render(
      <ChainDiffTextView chain1={chain1} chain2={chain2} changes={[]} onSelectChange={jest.fn()} />,
    );

    const modified = capturedDiffEditorProps!.modified!;
    expect(modified.indexOf("ZZZ-element")).toBeLessThan(modified.indexOf("AAA-element"));
  });

  it("should update original when chain1 prop changes", () => {
    const chain1a = makeChain({ id: "c1", name: "Chain One" });
    const chain1b = makeChain({ id: "c1", name: "Chain One Updated" });
    const { rerender } = render(
      <ChainDiffTextView chain1={chain1a} changes={[]} onSelectChange={jest.fn()} />,
    );

    rerender(<ChainDiffTextView chain1={chain1b} changes={[]} onSelectChange={jest.fn()} />);

    expect(capturedDiffEditorProps!.original).toContain("Chain One Updated");
  });

  it("should update modified when chain2 prop changes", () => {
    const chain2a = makeChain({ id: "c2", name: "Chain Two" });
    const chain2b = makeChain({ id: "c2", name: "Chain Two Updated" });
    const { rerender } = render(
      <ChainDiffTextView chain2={chain2a} changes={[]} onSelectChange={jest.fn()} />,
    );

    rerender(<ChainDiffTextView chain2={chain2b} changes={[]} onSelectChange={jest.fn()} />);

    expect(capturedDiffEditorProps!.modified).toContain("Chain Two Updated");
  });
});
