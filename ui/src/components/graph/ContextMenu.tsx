import { Dropdown } from "antd";
import { ItemType } from "antd/es/menu/interface";

export type ContextMenuData = {
  x: number;
  y: number;
  items: ContextMenuItem[];
};

export type ContextMenuItem = {
  id: string;
  text: string;
  handler: () => Promise<void> | void;
  disabled?: boolean;
  children?: ContextMenuItem[];
};

export type ContextMenuProps = {
  menu: ContextMenuData;
  closeMenu: () => void;
};

export default function ContextMenu({ menu, closeMenu }: ContextMenuProps) {
  const buildItems = (items: ContextMenuItem[]): ItemType[] => {
    return items.map((item) => {
      return {
        key: item.id,
        label: (
          <a
            onClick={() => {
              void item.handler();
            }}
          >
            {item.text}
          </a>
        ),
        disabled: item.disabled,
        ...(item.children && { children: buildItems(item.children) }),
      };
    });
  };

  return (
    <Dropdown
      open
      key={`${menu.x}-${menu.y}`}
      trigger={["contextMenu"]}
      menu={{ items: buildItems(menu.items), onClick: closeMenu }}
    >
      <div
        style={{
          position: "fixed",
          left: menu.x,
          top: menu.y,
          display: "inline-block",
          padding: 1,
          background: "transparent",
          zIndex: 9999,
        }}
      />
    </Dropdown>
  );
}
