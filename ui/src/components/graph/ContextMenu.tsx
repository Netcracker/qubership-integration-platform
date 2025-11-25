import { Dropdown } from "antd";

export type ContextMenuData = {
  x: number;
  y: number;
  items: ContextMenuItem[];
};

export type ContextMenuItem = {
  id: string;
  text: string;
  handler: () => Promise<void>;
};

export type ContextMenuProps = {
  menu: ContextMenuData;
  closeMenu: () => void;
};

export default function ContextMenu({ menu, closeMenu }: ContextMenuProps) {
  const dropdownItems = menu.items.map((item) => {
    return {
      key: item.id,
      label: <a onClick={() => {
        void item.handler();
      }}>{item.text}</a>,
    };
  });

  return (
      <Dropdown
        open
        key={`${menu.x}-${menu.y}`}
        trigger={["contextMenu"]}
        menu={{ items: dropdownItems, onClick: closeMenu }}
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
