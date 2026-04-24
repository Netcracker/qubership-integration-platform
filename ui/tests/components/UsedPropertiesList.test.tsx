/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";

jest.mock("../../src/hooks/useUsedProperties", () => ({
  useUsedProperties: jest.fn(),
}));

jest.mock("../../src/components/LibraryContext", () => ({
  useLibraryContext: jest.fn(),
}));

jest.mock("../../src/icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid="icon" data-icon={name} />
  ),
}));

jest.mock("../../src/components/elements_library/SidebarSearch", () => ({
  SidebarSearch: ({
    items,
    onSearch,
    onClear,
  }: {
    items: Array<{ key: string; name: string; children?: Array<{ key: string }> }>;
    onSearch: (filtered: typeof items, openKeys: string[]) => void;
    onClear: () => void;
  }) => (
    <div data-testid="sidebar-search">
      <button
        data-testid="search-trigger"
        onClick={() => {
          const first = items.slice(0, 1);
          onSearch(first, first.map((i) => i.key));
        }}
      />
      <button
        data-testid="search-trigger-empty"
        onClick={() => onSearch([], [])}
      />
      <button data-testid="clear-trigger" onClick={onClear} />
    </div>
  ),
}));

import { UsedPropertiesList } from "../../src/components/UsedPropertiesList";
import { useUsedProperties } from "../../src/hooks/useUsedProperties";
import { useLibraryContext } from "../../src/components/LibraryContext";
import {
  UsedPropertySource,
  UsedPropertyType,
  UsedPropertyElementOperation,
  UsedProperty,
} from "../../src/api/apiTypes";

const makeProperty = (overrides?: Partial<UsedProperty>): UsedProperty => ({
  name: "myProp",
  source: UsedPropertySource.HEADER,
  type: UsedPropertyType.STRING,
  isArray: false,
  relatedElements: {
    "el-1": {
      id: "el-1",
      name: "Script1",
      type: "script",
      operations: [UsedPropertyElementOperation.GET],
    },
  },
  ...overrides,
});

describe("UsedPropertiesList", () => {
  beforeEach(() => {
    (useUsedProperties as jest.Mock).mockReturnValue({
      properties: [],
      isLoading: false,
    });
    (useLibraryContext as jest.Mock).mockReturnValue({
      libraryElements: [{ name: "script", title: "Script", type: "script" }],
    });
  });

  describe("loading state", () => {
    it("does not render sidebar-search or empty message when loading with chainId", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [],
        isLoading: true,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      expect(screen.queryByTestId("sidebar-search")).not.toBeInTheDocument();
      expect(screen.queryByText("Properties not found")).not.toBeInTheDocument();
    });

    it("does not show loading state when elements prop is provided even if isLoading", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [],
        isLoading: true,
      });
      render(
        <UsedPropertiesList
          elements={[
            {
              id: "e1",
              name: "E1",
              type: "script",
              properties: { body: "${header.tok}" },
            },
          ]}
        />,
      );
      expect(screen.getByText("tok")).toBeInTheDocument();
    });
  });

  describe("empty state", () => {
    it("shows empty message when chainId has no properties", () => {
      render(<UsedPropertiesList chainId="chain-1" />);
      expect(screen.getByText("Properties not found")).toBeInTheDocument();
    });

    it("shows empty message when elements prop produces no properties", () => {
      render(
        <UsedPropertiesList
          elements={[{ id: "e1", name: "E1", type: "script" }]}
        />,
      );
      expect(screen.getByText("Properties not found")).toBeInTheDocument();
    });

    it("shows empty message when elements prop is empty array", () => {
      render(<UsedPropertiesList elements={[]} />);
      expect(screen.getByText("Properties not found")).toBeInTheDocument();
    });
  });

  describe("renders with properties from chainId (apiProperties)", () => {
    it("renders property name from api", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty()],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      expect(screen.getByText("myProp")).toBeInTheDocument();
    });

    it("renders the sidebar search component when properties exist", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty()],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      expect(screen.getByTestId("sidebar-search")).toBeInTheDocument();
    });

    it("shows H source code for HEADER source", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty({ source: UsedPropertySource.HEADER })],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      expect(screen.getByText("H")).toBeInTheDocument();
    });

    it("shows P source code for EXCHANGE_PROPERTY source", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [
          makeProperty({ source: UsedPropertySource.EXCHANGE_PROPERTY }),
        ],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      expect(screen.getByText("P")).toBeInTheDocument();
    });

    it("shows string type label", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty({ type: UsedPropertyType.STRING })],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      expect(screen.getByText(/\[string\]/)).toBeInTheDocument();
    });

    it("shows number type label", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty({ type: UsedPropertyType.NUMBER })],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      expect(screen.getByText(/\[number\]/)).toBeInTheDocument();
    });

    it("shows boolean type label", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty({ type: UsedPropertyType.BOOLEAN })],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      expect(screen.getByText(/\[boolean\]/)).toBeInTheDocument();
    });

    it("shows object type label", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty({ type: UsedPropertyType.OBJECT })],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      expect(screen.getByText(/\[object\]/)).toBeInTheDocument();
    });

    it("shows em-dash for UNKNOWN_TYPE", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty({ type: UsedPropertyType.UNKNOWN_TYPE })],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      expect(screen.getByText(/—/)).toBeInTheDocument();
    });

    it("shows 'array of' prefix when isArray is true", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty({ isArray: true })],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      expect(screen.getByText(/array of/)).toBeInTheDocument();
    });

    it("shows 99+ when childrenCount exceeds 99", () => {
      const manyElements: UsedProperty["relatedElements"] = {};
      for (let i = 0; i < 100; i++) {
        manyElements[`el-${i}`] = {
          id: `el-${i}`,
          name: `Element${i}`,
          type: "script",
          operations: [UsedPropertyElementOperation.GET],
        };
      }
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty({ relatedElements: manyElements })],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      expect(screen.getByText("99+")).toBeInTheDocument();
    });

    it("shows exact count when childrenCount is <= 99", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty()],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      expect(screen.getByText("1")).toBeInTheDocument();
    });

    it("sorts properties alphabetically", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [
          makeProperty({ name: "zProp" }),
          makeProperty({ name: "aProp" }),
        ],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      const all = screen.getAllByText(/Prop/);
      expect(all[0]).toHaveTextContent("aProp");
      expect(all[1]).toHaveTextContent("zProp");
    });
  });

  describe("renders with properties from elements prop", () => {
    it("renders property from elements prop using analyzeUsedProperties", () => {
      render(
        <UsedPropertiesList
          elements={[
            {
              id: "e1",
              name: "E1",
              type: "script",
              properties: { body: "${header.authToken}" },
            },
          ]}
        />,
      );
      expect(screen.getByText("authToken")).toBeInTheDocument();
    });

    it("renders exchange property from elements prop", () => {
      render(
        <UsedPropertiesList
          elements={[
            {
              id: "e1",
              name: "E1",
              type: "script",
              properties: { body: "${exchangeProperty.myKey}" },
            },
          ]}
        />,
      );
      expect(screen.getByText("myKey")).toBeInTheDocument();
    });
  });

  describe("expand and collapse", () => {
    it("does not show element children before expanding", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty()],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      expect(screen.queryByText("Script1")).not.toBeInTheDocument();
    });

    it("shows element children after clicking property row to expand", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty()],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByText("myProp"));
      expect(screen.getByText("Script1")).toBeInTheDocument();
    });

    it("hides element children after clicking again to collapse", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty()],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByText("myProp"));
      expect(screen.getByText("Script1")).toBeInTheDocument();
      fireEvent.click(screen.getByText("myProp"));
      expect(screen.queryByText("Script1")).not.toBeInTheDocument();
    });

    it("shows caretDownFilled icon when expanded", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty()],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByText("myProp"));
      const icons = screen.getAllByTestId("icon");
      const caretDown = icons.find(
        (i) => i.getAttribute("data-icon") === "caretDownFilled",
      );
      expect(caretDown).toBeInTheDocument();
    });

    it("shows GET operation chip when expanded", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty()],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByText("myProp"));
      expect(screen.getByText("GET")).toBeInTheDocument();
    });

    it("shows SET operation chip when element has SET operation", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [
          makeProperty({
            relatedElements: {
              "el-1": {
                id: "el-1",
                name: "Script1",
                type: "script",
                operations: [UsedPropertyElementOperation.SET],
              },
            },
          }),
        ],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByText("myProp"));
      expect(screen.getByText("SET")).toBeInTheDocument();
    });

    it("shows both GET and SET chips when element has both operations", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [
          makeProperty({
            relatedElements: {
              "el-1": {
                id: "el-1",
                name: "Script1",
                type: "script",
                operations: [
                  UsedPropertyElementOperation.GET,
                  UsedPropertyElementOperation.SET,
                ],
              },
            },
          }),
        ],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByText("myProp"));
      expect(screen.getByText("GET")).toBeInTheDocument();
      expect(screen.getByText("SET")).toBeInTheDocument();
    });

    it("uses library element title as type display when type matches library", () => {
      (useLibraryContext as jest.Mock).mockReturnValue({
        libraryElements: [
          { name: "script", title: "My Script Element", type: "script" },
        ],
      });
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty()],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByText("myProp"));
      expect(screen.getByText("My Script Element")).toBeInTheDocument();
    });

    it("falls back to element type name when no matching library element", () => {
      (useLibraryContext as jest.Mock).mockReturnValue({
        libraryElements: [],
      });
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty()],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByText("myProp"));
      expect(screen.getByText("script")).toBeInTheDocument();
    });

    it("falls back to element type when libraryElements is null", () => {
      (useLibraryContext as jest.Mock).mockReturnValue({
        libraryElements: null,
      });
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty()],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByText("myProp"));
      expect(screen.getByText("script")).toBeInTheDocument();
    });

    it("sorts element children alphabetically within a property", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [
          makeProperty({
            relatedElements: {
              "el-b": {
                id: "el-b",
                name: "Zeta",
                type: "script",
                operations: [UsedPropertyElementOperation.GET],
              },
              "el-a": {
                id: "el-a",
                name: "Alpha",
                type: "script",
                operations: [UsedPropertyElementOperation.GET],
              },
            },
          }),
        ],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByText("myProp"));
      const elementNames = screen.getAllByText(/Alpha|Zeta/);
      expect(elementNames[0]).toHaveTextContent("Alpha");
      expect(elementNames[1]).toHaveTextContent("Zeta");
    });
  });

  describe("click interactions on elements", () => {
    it("calls onElementSingleClick with element id when element row is clicked", () => {
      const onSingleClick = jest.fn();
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty()],
        isLoading: false,
      });
      render(
        <UsedPropertiesList
          chainId="chain-1"
          onElementSingleClick={onSingleClick}
        />,
      );
      fireEvent.click(screen.getByText("myProp"));
      fireEvent.click(screen.getByText("Script1"));
      expect(onSingleClick).toHaveBeenCalledWith("el-1");
    });

    it("calls onElementDoubleClick with element id on double-click", () => {
      const onDoubleClick = jest.fn();
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty()],
        isLoading: false,
      });
      render(
        <UsedPropertiesList
          chainId="chain-1"
          onElementDoubleClick={onDoubleClick}
        />,
      );
      fireEvent.click(screen.getByText("myProp"));
      fireEvent.doubleClick(screen.getByText("Script1"));
      expect(onDoubleClick).toHaveBeenCalledWith("el-1");
    });

    it("does not throw when onElementSingleClick is not provided", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty()],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByText("myProp"));
      expect(() => {
        fireEvent.click(screen.getByText("Script1"));
      }).not.toThrow();
    });

    it("does not throw when onElementDoubleClick is not provided", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty()],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByText("myProp"));
      expect(() => {
        fireEvent.doubleClick(screen.getByText("Script1"));
      }).not.toThrow();
    });

    it("element single-click does not toggle property expand/collapse", () => {
      const onSingleClick = jest.fn();
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty()],
        isLoading: false,
      });
      render(
        <UsedPropertiesList
          chainId="chain-1"
          onElementSingleClick={onSingleClick}
        />,
      );
      fireEvent.click(screen.getByText("myProp"));
      expect(screen.getByText("Script1")).toBeInTheDocument();
      fireEvent.click(screen.getByText("Script1"));
      expect(screen.getByText("Script1")).toBeInTheDocument();
    });
  });

  describe("search functionality", () => {
    it("filters properties when search is triggered", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [
          makeProperty({ name: "alphaKey" }),
          makeProperty({ name: "betaKey" }),
        ],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByTestId("search-trigger"));
      expect(screen.getByText("alphaKey")).toBeInTheDocument();
      expect(screen.queryByText("betaKey")).not.toBeInTheDocument();
    });

    it("restores all properties after clearing search", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [
          makeProperty({ name: "alphaKey" }),
          makeProperty({ name: "betaKey" }),
        ],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByTestId("search-trigger"));
      fireEvent.click(screen.getByTestId("clear-trigger"));
      expect(screen.getByText("alphaKey")).toBeInTheDocument();
      expect(screen.getByText("betaKey")).toBeInTheDocument();
    });

    it("shows no properties when search returns empty results", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [makeProperty({ name: "myProp" })],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByTestId("search-trigger-empty"));
      expect(screen.queryByText("myProp")).not.toBeInTheDocument();
    });

    it("calling search multiple times updates state correctly", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [
          makeProperty({ name: "alphaKey" }),
          makeProperty({ name: "betaKey" }),
        ],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByTestId("search-trigger"));
      fireEvent.click(screen.getByTestId("search-trigger"));
      expect(screen.getByText("alphaKey")).toBeInTheDocument();
    });
  });

  describe("multiple properties", () => {
    it("renders multiple properties", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [
          makeProperty({ name: "propA" }),
          makeProperty({
            name: "propB",
            source: UsedPropertySource.EXCHANGE_PROPERTY,
          }),
        ],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      expect(screen.getByText("propA")).toBeInTheDocument();
      expect(screen.getByText("propB")).toBeInTheDocument();
    });

    it("expanding one property does not expand another", () => {
      (useUsedProperties as jest.Mock).mockReturnValue({
        properties: [
          makeProperty({
            name: "propA",
            relatedElements: {
              "el-a": {
                id: "el-a",
                name: "ElementA",
                type: "script",
                operations: [UsedPropertyElementOperation.GET],
              },
            },
          }),
          makeProperty({
            name: "propB",
            source: UsedPropertySource.EXCHANGE_PROPERTY,
            relatedElements: {
              "el-b": {
                id: "el-b",
                name: "ElementB",
                type: "script",
                operations: [UsedPropertyElementOperation.GET],
              },
            },
          }),
        ],
        isLoading: false,
      });
      render(<UsedPropertiesList chainId="chain-1" />);
      fireEvent.click(screen.getByText("propA"));
      expect(screen.getByText("ElementA")).toBeInTheDocument();
      expect(screen.queryByText("ElementB")).not.toBeInTheDocument();
    });
  });
});
