/**
 * @jest-environment jsdom
 */

import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { Chain, Element, EntityLabel } from "../../../../src/api/apiTypes";
import type { JSONSchema7 } from "json-schema";

jest.mock("antd", () => {
  return {
    Descriptions: ({ items }: any) => (
      <dl>
        {(items ?? []).map((item: any) => (
          <div key={item.key} data-testid={`desc-item-${item.key}`}>
            <dt>{item.label}</dt>
            <dd>{item.children}</dd>
          </div>
        ))}
      </dl>
    ),
    Space: ({ children }: any) => <div data-testid="space">{children}</div>,
  };
});

jest.mock("../../../../src/components/labels/EntityLabels.tsx", () => ({
  EntityLabels: ({ labels }: { labels: EntityLabel[] }) => (
    <div data-testid="entity-labels" data-label-count={labels?.length} />
  ),
}));

jest.mock("../../../../src/components/chains/diff/ElementSchemasProvider.tsx", () => {
  const { createContext } = require("react");
  return {
    ElementSchemasContext: createContext({ getSchema: () => undefined }),
  };
});

import {
  getElement,
  LinkToChain,
  LinkToElement,
  resolveChainPropertyTitle,
  ChainProperty,
  getElementPropertyTitle,
  getElementPropertyTitleFromSchema,
  ElementProperty,
  ConnectionView,
  ChangedEntityView,
} from "../../../../src/components/chains/diff/ChangedEntityView";
import { ElementSchemasContext } from "../../../../src/components/chains/diff/ElementSchemasProvider";

const makeElement = (
  id: string,
  name: string,
  chainId = "chain-1",
  type = "http-trigger",
): Element => ({ id, name, chainId, type } as unknown as Element);

const makeChain = (id: string, elements: Element[] = []): Chain =>
  ({ id, name: `Chain ${id}`, elements } as unknown as Chain);

// ---

describe("getElement", () => {
  it("should return the element when found by id", () => {
    const elem = makeElement("e1", "My Element");
    const chain = makeChain("chain-1", [elem]);

    expect(getElement("e1", chain)).toBe(elem);
  });

  it("should return undefined when no element with that id exists", () => {
    const chain = makeChain("chain-1", [makeElement("e1", "Element A")]);

    expect(getElement("missing", chain)).toBeUndefined();
  });
});

// ---

describe("LinkToChain", () => {
  it("should render an anchor with chain name and /chains/{id} href when chain is provided", () => {
    render(<LinkToChain chain={makeChain("chain-1")} />);

    const link = screen.getByRole("link", { name: "Chain chain-1" });
    expect(link).toHaveAttribute("href", "/chains/chain-1");
  });

  it("should render nothing when chain is undefined", () => {
    const { container } = render(<LinkToChain />);

    expect(container.firstChild).toBeNull();
  });
});

// ---

describe("LinkToElement", () => {
  it("should render an anchor with element name and /chains/{chainId}/graph/{id} href when element is provided", () => {
    render(<LinkToElement element={makeElement("e1", "My Element", "chain-1")} />);

    const link = screen.getByRole("link", { name: "My Element" });
    expect(link).toHaveAttribute("href", "/chains/chain-1/graph/e1");
  });

  it("should render empty content when element is undefined", () => {
    render(<LinkToElement />);

    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
});

// ---

describe("resolveChainPropertyTitle", () => {
  it.each([
    ["labels", "Labels"],
    ["description", "Description"],
    ["deployAction", "Deploy action"],
    ["businessDescription", "Business description"],
    ["assumptions", "Assumptions"],
    ["outOfScope", "Out of scope"],
    ["overriddenByChainId", "Overridden by chain ID"],
    ["overriddenByChainName", "Overridden by chain name"],
    ["overridesChainId", "Overrides chain ID"],
    ["overridesChainName", "Overrides chain name"],
    ["name", "Name"],
  ])('should map "%s" → "%s"', (input, expected) => {
    expect(resolveChainPropertyTitle(input)).toBe(expected);
  });

  it("should return the raw property name for an unknown name", () => {
    expect(resolveChainPropertyTitle("someUnknownProp")).toBe("someUnknownProp");
  });
});

// ---

describe("ChainProperty", () => {
  it("should render the resolved human-readable title in the Name description row", () => {
    render(<ChainProperty name="deployAction" value="upgrade" />);

    expect(screen.getByTestId("desc-item-name")).toHaveTextContent("Deploy action");
  });

  it("should render EntityLabels when name is 'labels'", () => {
    const labels: EntityLabel[] = [{ name: "tag1", technical: false }];
    render(<ChainProperty name="labels" value={labels} />);

    expect(screen.getByTestId("entity-labels")).toBeInTheDocument();
  });

  it("should render JSON.stringify(value) for non-label properties", () => {
    render(<ChainProperty name="description" value="hello world" />);

    expect(screen.getByTestId("desc-item-value")).toHaveTextContent('"hello world"');
  });
});

// ---

describe("getElementPropertyTitleFromSchema", () => {
  it("should return the title from schema.properties[name] when present", () => {
    const schema: JSONSchema7 = { properties: { myField: { title: "My Field" } } };

    expect(getElementPropertyTitleFromSchema(schema, "myField")).toBe("My Field");
  });

  it("should return undefined when the matching property has no title", () => {
    const schema: JSONSchema7 = { properties: { myField: { type: "string" } } };

    expect(getElementPropertyTitleFromSchema(schema, "myField")).toBeUndefined();
  });

  it("should return undefined when the property is absent from the schema", () => {
    const schema: JSONSchema7 = { properties: {} };

    expect(getElementPropertyTitleFromSchema(schema, "missing")).toBeUndefined();
  });

  it("should return the title found inside an allOf sub-schema", () => {
    const schema: JSONSchema7 = {
      allOf: [{ properties: { myField: { title: "From AllOf" } } }],
    };

    expect(getElementPropertyTitleFromSchema(schema, "myField")).toBe("From AllOf");
  });

  it("should return the title found inside an anyOf sub-schema", () => {
    const schema: JSONSchema7 = {
      anyOf: [{ properties: { myField: { title: "From AnyOf" } } }],
    };

    expect(getElementPropertyTitleFromSchema(schema, "myField")).toBe("From AnyOf");
  });

  it("should return the title found inside a oneOf sub-schema", () => {
    const schema: JSONSchema7 = {
      oneOf: [{ properties: { myField: { title: "From OneOf" } } }],
    };

    expect(getElementPropertyTitleFromSchema(schema, "myField")).toBe("From OneOf");
  });

  it("should return the title found inside a then or else sub-schema", () => {
    const schema: JSONSchema7 = {
      then: { properties: { myField: { title: "From Then" } } },
    };

    expect(getElementPropertyTitleFromSchema(schema, "myField")).toBe("From Then");
  });

  it("should return undefined when the property is not found in any nested schema", () => {
    const schema: JSONSchema7 = {
      allOf: [{ properties: { otherField: { title: "Other" } } }],
    };

    expect(getElementPropertyTitleFromSchema(schema, "missing")).toBeUndefined();
  });
});

// ---

describe("getElementPropertyTitle", () => {
  it.each([
    ["name", "Name"],
    ["description", "Description"],
    ["type", "Type"],
    ["swimlaneId", "Swimlane"],
    ["parentElementId", "Parent"],
  ])('should map "%s" → "%s"', (input, expected) => {
    expect(getElementPropertyTitle({}, input)).toBe(expected);
  });

  it("should return the title from schema properties for an unrecognized name", () => {
    const schema: JSONSchema7 = { properties: { myProp: { title: "My Prop" } } };

    expect(getElementPropertyTitle(schema, "myProp")).toBe("My Prop");
  });

  it("should return the raw property name when not hardcoded and not in schema", () => {
    expect(getElementPropertyTitle({}, "unknownField")).toBe("unknownField");
  });
});

// ---

describe("ElementProperty", () => {
  it("should render an Element description item with a link when element is provided", () => {
    const elem = makeElement("e1", "Element A", "chain-1");
    const chain = makeChain("chain-1", [elem]);
    render(<ElementProperty element={elem} name="timeout" value={30} chain={chain} />);

    expect(screen.getByTestId("desc-item-element")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Element A" })).toBeInTheDocument();
  });

  it("should not render the Element description item when element is undefined", () => {
    render(<ElementProperty element={undefined} name="timeout" value={30} chain={makeChain("chain-1")} />);

    expect(screen.queryByTestId("desc-item-element")).not.toBeInTheDocument();
  });

  it("should render EntityLabels when name is 'labels'", () => {
    render(<ElementProperty name="labels" value={[]} chain={makeChain("chain-1")} />);

    expect(screen.getByTestId("entity-labels")).toBeInTheDocument();
  });

  it("should render LinkToElement for 'parentElementId' pointing to the resolved chain element", () => {
    const parent = makeElement("e-parent", "Parent Element", "chain-1");
    const chain = makeChain("chain-1", [parent]);
    render(<ElementProperty name="parentElementId" value="e-parent" chain={chain} />);

    expect(screen.getByRole("link", { name: "Parent Element" })).toBeInTheDocument();
  });

  it("should render LinkToElement for 'swimlaneId' pointing to the resolved chain element", () => {
    const swimlane = makeElement("sw-1", "Swimlane A", "chain-1");
    const chain = makeChain("chain-1", [swimlane]);
    render(<ElementProperty name="swimlaneId" value="sw-1" chain={chain} />);

    expect(screen.getByRole("link", { name: "Swimlane A" })).toBeInTheDocument();
  });

  it("should render JSON.stringify(value) for generic properties", () => {
    render(<ElementProperty name="timeout" value={42} chain={makeChain("chain-1")} />);

    expect(screen.getByTestId("desc-item-value")).toHaveTextContent("42");
  });

  it("should resolve the title from the element schema when element and schema are available", () => {
    const elem = makeElement("e1", "Element A", "chain-1", "my-type");
    const chain = makeChain("chain-1", [elem]);
    const mockGetSchema = jest.fn().mockReturnValue({
      properties: {
        properties: {
          properties: { timeout: { title: "Timeout (ms)" } },
        },
      },
    });

    render(
      <ElementSchemasContext.Provider value={{ getSchema: mockGetSchema }}>
        <ElementProperty element={elem} name="timeout" value={30} chain={chain} />
      </ElementSchemasContext.Provider>,
    );

    expect(screen.getByTestId("desc-item-name")).toHaveTextContent("Timeout (ms)");
  });

  it("should fall back to the raw property name when element is undefined", () => {
    render(
      <ElementProperty element={undefined} name="myCustomField" value="x" chain={makeChain("chain-1")} />,
    );

    expect(screen.getByTestId("desc-item-name")).toHaveTextContent("myCustomField");
  });
});

// ---

describe("ConnectionView", () => {
  it("should render the from-element link, arrow, and to-element link", () => {
    const e1 = makeElement("e1", "Source", "chain-1");
    const e2 = makeElement("e2", "Target", "chain-1");
    const chain = makeChain("chain-1", [e1, e2]);
    const connection = { id: "conn-1", from: "e1", to: "e2" } as any;

    render(<ConnectionView connection={connection} chain={chain} />);

    expect(screen.getByRole("link", { name: "Source" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Target" })).toBeInTheDocument();
    expect(screen.getByTestId("space")).toHaveTextContent("→");
  });

  it("should render empty element links when connection references ids not present in chain", () => {
    const chain = makeChain("chain-1", []);
    const connection = { id: "conn-1", from: "missing-1", to: "missing-2" } as any;

    render(<ConnectionView connection={connection} chain={chain} />);

    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
});

// ---

describe("ChangedEntityView", () => {
  it("should return 'N/A' when change[side] is nullish", () => {
    const change = { id: "c1", kind: "element" } as any;
    const { container } = render(
      <ChangedEntityView change={change} side="one" chain={makeChain("chain-1")} />,
    );

    expect(container).toHaveTextContent("N/A");
  });

  it("should render LinkToElement for 'element' kind", () => {
    const elem = makeElement("e1", "My Element", "chain-1");
    const change = { id: "c1", kind: "element", one: elem } as any;

    render(<ChangedEntityView change={change} side="one" chain={makeChain("chain-1")} />);

    expect(screen.getByRole("link", { name: "My Element" })).toBeInTheDocument();
  });

  it("should render ChainProperty for 'chain-property' kind", () => {
    const change = {
      id: "c2",
      kind: "chain-property",
      one: { entityId: "chain-1", name: "description", value: "My Chain" },
    } as any;

    render(<ChangedEntityView change={change} side="one" chain={makeChain("chain-1")} />);

    expect(screen.getByTestId("desc-item-name")).toHaveTextContent("Description");
    expect(screen.getByTestId("desc-item-value")).toHaveTextContent('"My Chain"');
  });

  it("should render ElementProperty for 'element-property' kind", () => {
    const elem = makeElement("e1", "Element A", "chain-1");
    const chain = makeChain("chain-1", [elem]);
    const change = {
      id: "c3",
      kind: "element-property",
      one: { entityId: "e1", name: "timeout", value: 30 },
    } as any;

    render(<ChangedEntityView change={change} side="one" chain={chain} />);

    expect(screen.getByTestId("desc-item-element")).toBeInTheDocument();
    expect(screen.getByTestId("desc-item-name")).toBeInTheDocument();
  });

  it("should render ConnectionView for 'connection' kind", () => {
    const e1 = makeElement("e1", "Source", "chain-1");
    const e2 = makeElement("e2", "Target", "chain-1");
    const chain = makeChain("chain-1", [e1, e2]);
    const change = {
      id: "c4",
      kind: "connection",
      one: { id: "conn-1", from: "e1", to: "e2" },
    } as any;

    render(<ChangedEntityView change={change} side="one" chain={chain} />);

    expect(screen.getByTestId("space")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Source" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Target" })).toBeInTheDocument();
  });

  it("should render empty content for an unknown kind", () => {
    const change = { id: "c5", kind: "unknown", one: { id: "x" } } as any;
    const { container } = render(
      <ChangedEntityView change={change} side="one" chain={makeChain("chain-1")} />,
    );

    expect(container.textContent).toBe("");
  });
});
