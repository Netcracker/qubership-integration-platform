import { Dropdown, DropdownProps, MenuProps } from "antd";
import { RequiredPermissions, UserPermissions } from "./types.ts";
import React, { ReactNode, useEffect, useState } from "react";
import { OnDenied } from "./ProtectedButton.tsx";
import { usePermissions } from "./usePermissions.tsx";
import { hasPermissions } from "./funcs.ts";
import { ItemType } from "antd/es/menu/interface";

export type ProtectedMenuItem = ItemType & {
  onDenied?: OnDenied;
  require?: RequiredPermissions;
};

export type ProtectedDropdownProps = Omit<DropdownProps, "menu"> & {
  menu: Omit<DropdownProps["menu"], "items"> & { items: ProtectedMenuItem[] };
  onItemDenied?: OnDenied;
  onAllDenied?: OnDenied;
};

export function squashSubsequentDividers(items: ItemType[]): ItemType[] {
  const result: ItemType[] = [];
  let prevItem: ItemType = null;
  for (const item of items) {
    if (
      item?.type !== "divider" ||
      (prevItem && prevItem?.type !== "divider")
    ) {
      result.push(item);
      prevItem = item;
    }
  }
  // If items list ends with divider then removing it.
  if (prevItem?.type === "divider") {
    result.pop();
  }
  return result;
}

export function protectMenuItems(
  items: ProtectedMenuItem[],
  permissions: UserPermissions,
  onItemDenied: OnDenied,
): ItemType[] {
  return squashSubsequentDividers(
    items
      .map((item) => {
        return hasPermissions(permissions, item.require ?? {})
          ? item
          : (item.onDenied ?? onItemDenied) === "disable"
            ? ({ ...item, disabled: true } as ItemType)
            : null;
      })
      .filter((item) => !!item),
  );
}

export const ProtectedDropdown: React.FC<ProtectedDropdownProps> = ({
  menu,
  children,
  onItemDenied = "hide",
  onAllDenied = "hide",
  ...rest
}): ReactNode => {
  const permissions = usePermissions();
  const [menuItems, setMenuItems] = useState<MenuProps["items"]>([]);

  useEffect(() => {
    setMenuItems(protectMenuItems(menu.items, permissions, onItemDenied));
  }, [permissions, menu.items, onItemDenied]);

  return menuItems?.length === 0 && onAllDenied === "hide" ? null : (
    <Dropdown
      menu={{
        ...menu,
        items: menuItems,
      }}
      {...rest}
    >
      {children}
    </Dropdown>
  );
};
