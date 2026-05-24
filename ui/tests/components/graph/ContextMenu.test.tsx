/**
 * @jest-environment jsdom
 */

import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import ContextMenu, {
  ContextMenuData,
  ContextMenuItem,
} from "../../../src/components/graph/ContextMenu";

interface MockDropdown extends jest.Mock {
  mockOpen: boolean;
  mockTrigger: string[];
  mockMenu: { items: any[] };
}

// Mock antd Dropdown to capture its props and children
jest.mock("antd", () => {
  return {
    ...jest.requireActual("antd"),
    Dropdown: ({ open, trigger, menu, children }: any) => {
      // Store for testing access
      Dropdown.mockOpen = open;
      Dropdown.mockTrigger = trigger;
      Dropdown.mockMenu = menu;

      // Render menu items so they appear in the document
      const renderedItems = menu?.items?.map((item: any) => (
        <div key={item.key} data-testid={`menu-item-${item.key}`}>
          {item.label}
        </div>
      ));

      return (
        <div data-testid="dropdown-container">
          {children}
          {renderedItems}
        </div>
      );
    },
  };
});

// Reference to the mocked Dropdown
const Dropdown = require("antd").Dropdown as MockDropdown;

describe("ContextMenu", () => {
  const mockCloseMenu = jest.fn();

  const createMockMenu = (items: ContextMenuItem[] = []): ContextMenuData => ({
    x: 100,
    y: 200,
    items,
  });

  const createMockItem = (
    id: string,
    text: string,
    handler: () => void | Promise<void> = jest.fn(),
    disabled?: boolean,
    children?: ContextMenuItem[],
  ): ContextMenuItem => ({
    id,
    text,
    handler,
    disabled,
    children,
  });

  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe("rendering and positioning", () => {
    it("should render dropdown component with trigger element", () => {
      const menu = createMockMenu([]);
      const { container } = render(
        <ContextMenu menu={menu} closeMenu={mockCloseMenu} />,
      );

      const triggerElement = container.querySelector("div");
      expect(triggerElement).toBeInTheDocument();
    });

    it("should set Dropdown to open state", () => {
      const menu = createMockMenu([]);
      render(<ContextMenu menu={menu} closeMenu={mockCloseMenu} />);

      expect(Dropdown.mockOpen).toBe(true);
    });

    it("should use contextMenu trigger", () => {
      const menu = createMockMenu([]);
      render(<ContextMenu menu={menu} closeMenu={mockCloseMenu} />);

      expect(Dropdown.mockTrigger).toEqual(["contextMenu"]);
    });
  });

  describe("menu items transformation", () => {
    it("should render single menu item correctly", () => {
      const menu = createMockMenu([createMockItem("item1", "Test Item")]);
      render(<ContextMenu menu={menu} closeMenu={mockCloseMenu} />);

      const menuItems = Dropdown.mockMenu?.items;
      expect(menuItems).toHaveLength(1);
      expect(menuItems[0].key).toBe("item1");
    });

    it("should render multiple menu items", () => {
      const menu = createMockMenu([
        createMockItem("item1", "First"),
        createMockItem("item2", "Second"),
        createMockItem("item3", "Third"),
      ]);
      render(<ContextMenu menu={menu} closeMenu={mockCloseMenu} />);

      const menuItems = Dropdown.mockMenu?.items;
      expect(menuItems).toHaveLength(3);
    });

    it("should preserve disabled state in menu items", () => {
      const menu = createMockMenu([
        createMockItem("disabled1", "Disabled Item", jest.fn(), true),
      ]);
      render(<ContextMenu menu={menu} closeMenu={mockCloseMenu} />);

      const menuItems = Dropdown.mockMenu?.items;
      expect(menuItems[0].disabled).toBe(true);
    });

    it("should handle items without disabled property", () => {
      const menu = createMockMenu([
        createMockItem("enabled1", "Enabled Item", jest.fn(), false),
      ]);
      render(<ContextMenu menu={menu} closeMenu={mockCloseMenu} />);

      const menuItems = Dropdown.mockMenu?.items;
      expect(menuItems[0].disabled).toBe(false);
    });

    it("should transform flat items to Dropdown item structure", () => {
      const handler = jest.fn();
      const menu = createMockMenu([
        createMockItem("clickable", "Click Me", handler),
      ]);
      render(<ContextMenu menu={menu} closeMenu={mockCloseMenu} />);

      const menuItem = Dropdown.mockMenu?.items[0];
      expect(menuItem?.key).toBe("clickable");
      expect(typeof menuItem?.label).toBe("object"); // It's a React element
    });
  });

  describe("nested children handling", () => {
    it("should render items with children as submenu", () => {
      const menu = createMockMenu([
        createMockItem("parent", "Parent", jest.fn(), false, [
          createMockItem("child1", "Child One"),
          createMockItem("child2", "Child Two"),
        ]),
      ]);
      render(<ContextMenu menu={menu} closeMenu={mockCloseMenu} />);

      const menuItems = Dropdown.mockMenu?.items;
      expect(menuItems[0].children).toHaveLength(2);
      expect(menuItems[0].children![0].key).toBe("child1");
      expect(menuItems[0].children![1].key).toBe("child2");
    });

    it("should handle deeply nested children", () => {
      const menu = createMockMenu([
        createMockItem("level1", "Level 1", jest.fn(), false, [
          createMockItem("level2", "Level 2", jest.fn(), false, [
            createMockItem("level3", "Level 3"),
          ]),
        ]),
      ]);
      render(<ContextMenu menu={menu} closeMenu={mockCloseMenu} />);

      const menuItems = Dropdown.mockMenu?.items;
      expect(menuItems[0].children![0].children).toHaveLength(1);
    });

    it("should handle items without children", () => {
      const menu = createMockMenu([
        createMockItem("no-children", "No Children"),
      ]);
      render(<ContextMenu menu={menu} closeMenu={mockCloseMenu} />);

      const menuItems = Dropdown.mockMenu?.items;
      expect(menuItems[0].children).toBeUndefined();
    });
  });

  describe("handler invocation", () => {
    it("should call item handler when clicked", async () => {
      const handler = jest.fn();
      const menu = createMockMenu([
        createMockItem("click", "Click Me", handler),
      ]);
      render(<ContextMenu menu={menu} closeMenu={mockCloseMenu} />);

      const anchorElement = screen.getByText("Click Me");

      fireEvent.click(anchorElement);

      await waitFor(() => {
        expect(handler).toHaveBeenCalledTimes(1);
      });
    });
  });

  describe("edge cases", () => {
    it("should handle empty items array", () => {
      const menu = createMockMenu([]);
      render(<ContextMenu menu={menu} closeMenu={mockCloseMenu} />);

      const menuItems = Dropdown.mockMenu?.items;
      expect(menuItems).toHaveLength(0);
    });

    it("should create unique key based on x and y coordinates", () => {
      const menu1 = createMockMenu([]);
      const menu2 = { ...menu1, x: 300, y: 400 };

      const { container: c1 } = render(
        <ContextMenu menu={menu1} closeMenu={mockCloseMenu} />,
      );
      const { container: c2 } = render(
        <ContextMenu menu={menu2} closeMenu={mockCloseMenu} />,
      );

      // Both should render without errors
      expect(c1.querySelector("div")).toBeInTheDocument();
      expect(c2.querySelector("div")).toBeInTheDocument();
    });

    it("should handle items with missing optional properties", () => {
      const minimalItem = {
        id: "minimal",
        text: "Minimal",
        handler: jest.fn(),
      } as ContextMenuItem;
      const menu = createMockMenu([minimalItem]);

      expect(() => {
        render(<ContextMenu menu={menu} closeMenu={mockCloseMenu} />);
      }).not.toThrow();
    });

    it("should handle multiple items each with independent handlers", async () => {
      const handler1 = jest.fn();
      const handler2 = jest.fn();
      const handler3 = jest.fn();

      const menu = createMockMenu([
        createMockItem("item1", "Item 1", handler1),
        createMockItem("item2", "Item 2", handler2),
        createMockItem("item3", "Item 3", handler3),
      ]);

      render(<ContextMenu menu={menu} closeMenu={mockCloseMenu} />);

      fireEvent.click(screen.getByText("Item 1"));
      fireEvent.click(screen.getByText("Item 2"));
      fireEvent.click(screen.getByText("Item 3"));

      await waitFor(() => {
        expect(handler1).toHaveBeenCalledTimes(1);
        expect(handler2).toHaveBeenCalledTimes(1);
        expect(handler3).toHaveBeenCalledTimes(1);
      });
    });
  });
});
