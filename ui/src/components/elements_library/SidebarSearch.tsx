import Search from "antd/lib/input/Search";
import React, { useCallback } from "react";
import { MenuItem } from "./ElementsLibrarySidebar";

type SidebarSearchProps = {
  items: readonly MenuItem[];
  onSearch: (filtered: MenuItem[], openKeys: string[]) => void;
  onClear: () => void;
};

export const SidebarSearch: React.FC<SidebarSearchProps> = ({
  items,
  onSearch,
  onClear,
}) => {
  const filterChildren = useCallback(
    (
      folderNameIncludes: boolean,
      itemsInFolder: readonly MenuItem[],
      search?: string,
    ): MenuItem[] => {
      const filtered = itemsInFolder.filter((item) => {
        return includes(item.name, search);
      });

      return folderNameIncludes
        ? filtered.length > 0
          ? filtered
          : [...itemsInFolder]
        : filtered;
    },
    [],
  );

  const handleSearch = useCallback(
    (value?: string) => {
      const filteredItems: MenuItem[] = [];
      for (const item of items) {
        const filteredChildren = filterChildren(
          includes(item.name, value),
          item.children ?? [],
          value,
        );

        if (filteredChildren.length > 0) {
          filteredItems.push({ ...item, children: filteredChildren });
        }
      }

      const openKeys: string[] = filteredItems.map((item) => item.key);

      onSearch(filteredItems, openKeys);
    },
    [items, onSearch, filterChildren],
  );

  const includes = (name: string, search?: string) => {
    return !search || name.toLowerCase().includes(search.toLowerCase());
  };
  return (
    <Search
      allowClear
      onSearch={handleSearch}
      onChange={(event) => {
        const value = event.target.value;
        if (!value || value.length === 0) {
          onClear();
        } else {
          handleSearch(event.target.value);
        }
      }}
      onClear={onClear}
    />
  );
};
