import React, { useState, useMemo, useCallback, useEffect, useRef } from "react";
import { Spin, Empty } from "antd";
import { UsedProperty } from "../api/apiTypes.ts";
import { useLibraryContext } from "./LibraryContext.tsx";
import { OverridableIcon, IconName } from "../icons/IconProvider.tsx";
import styles from "./UsedPropertiesList.module.css";
import { SidebarSearch } from "./elements_library/SidebarSearch.tsx";
import { MenuItem } from "./elements_library/ElementsLibrarySidebar";
import {
  analyzeUsedProperties,
  AnalyzableElement,
} from "../misc/used-properties-analyzer.ts";
import { useUsedProperties } from "../hooks/useUsedProperties.tsx";

const USED_PROPERTY_SOURCE_LABEL_MAPPING: { [key: string]: string } = {
  HEADER: "H",
  EXCHANGE_PROPERTY: "P",
};

const USED_PROPERTY_TYPE_LABEL_MAPPING: { [key: string]: string } = {
  STRING: "string",
  NUMBER: "number",
  BOOLEAN: "boolean",
  OBJECT: "object",
  UNKNOWN_TYPE: "—",
};

const usedPropertyElementOperationColorMapping: {
  [key: string]: "green" | "blue";
} = {
  GET: "green",
  SET: "blue",
};

interface ParsedElement {
  id: string;
  elementId: string;
  name: string;
  type: string;
  typeTitle: string;
  operations: Array<{
    operation: "GET" | "SET";
    operationColor: "green" | "blue";
  }>;
}

type UsedPropertiesListProps = {
  onElementSingleClick?: (elementId: string) => void;
  onElementDoubleClick?: (elementId: string) => void;
} & (
  | { elements: AnalyzableElement[]; chainId?: never }
  | { chainId: string; elements?: never }
);

export const UsedPropertiesList: React.FC<UsedPropertiesListProps> = ({
  elements,
  chainId,
  onElementSingleClick,
  onElementDoubleClick,
}) => {
  const { properties: apiProperties, isLoading } = useUsedProperties(
    chainId ?? "",
  );

  const properties = useMemo(() => {
    if (elements) {
      return analyzeUsedProperties(elements);
    }
    return apiProperties;
  }, [elements, apiProperties]);

  const { libraryElements } = useLibraryContext();

  const allItems = useRef<MenuItem[]>([]);
  const [isSearch, setIsSearch] = useState(false);
  const openKeysBeforeSearch = useRef<string[]>([]);
  const [openKeysState, setOpenKeysState] = useState<string[]>([]);
  const [items, setItems] = useState<MenuItem[]>([]);

  const getElementTemplate = useCallback(
    (type: string): { title: string } | null => {
      if (!libraryElements) return null;
      const libraryElement = libraryElements.find((el) => el.name === type);
      return libraryElement ? { title: libraryElement.title } : null;
    },
    [libraryElements],
  );

  const buildUsedPropMapKey = (property: UsedProperty): string => {
    return property.name + property.source;
  };

  const buildUsedElementMapKey = (
    propertyName: string,
    propertySource: string,
    elementId: string,
  ): string => {
    return propertyName + propertySource + elementId;
  };

  const parsedProperties = useMemo(() => {
    if (!properties || properties.length === 0) {
      return [];
  }

    const sortedProperties = [...properties].sort((a, b) =>
      a.name.localeCompare(b.name),
    );

    return sortedProperties.map((property) => {
      const propertyId = buildUsedPropMapKey(property);

      const children: ParsedElement[] = Object.values(property.relatedElements)
        .sort((a, b) => a.name.localeCompare(b.name))
        .map((element) => {
          const elementDescriptor = getElementTemplate(element.type);

          return {
            id: buildUsedElementMapKey(
              property.name,
              property.source,
              element.id,
            ),
            elementId: element.id,
            name: element.name,
            type: element.type,
            typeTitle: elementDescriptor?.title || element.type,
            operations: element.operations.map((op) => ({
              operation: op,
              operationColor: usedPropertyElementOperationColorMapping[op],
            })),
          };
        });

      return {
        id: propertyId,
        name: property.name,
        source: property.source,
        sourceCode: USED_PROPERTY_SOURCE_LABEL_MAPPING[property.source],
        type: USED_PROPERTY_TYPE_LABEL_MAPPING[property.type],
        isArray: property.isArray,
        childrenCount: Object.values(property.relatedElements).length,
        children,
      };
    });
  }, [properties, getElementTemplate]);

  useEffect(() => {
    const menuItems: MenuItem[] = parsedProperties.map((property) => ({
       key: property.id,
       label: property.name,
       name: property.name,
       children: property.children.map((element) => ({
       key: element.elementId,
       label: element.name,
       name: element.name,
      })),
    }));

    allItems.current = menuItems;
    if (!isSearch) {
      setItems(menuItems);
    }
  }, [parsedProperties, isSearch]);

  const handleSearch = useCallback(
    (filtered: MenuItem[], openKeys: string[]) => {
      if (!isSearch) {
        setIsSearch(true);
        openKeysBeforeSearch.current = openKeysState;
      }
      setOpenKeysState(openKeys);
      setItems(filtered);
    },
    [isSearch, openKeysState],
  );

  const toggleProperty = (propertyId: string) => {
    setOpenKeysState((prev) =>
      prev.includes(propertyId)
        ? prev.filter((k) => k !== propertyId)
        : [...prev, propertyId],
    );
  };

  const displayProperties = useMemo(() => {
    if (!isSearch) return parsedProperties;
    const itemMap = new Map(items.map((item) => [item.key, item]));

    return parsedProperties
      .filter((p) => itemMap.has(p.id))
      .map((p) => {
        const matchedItem = itemMap.get(p.id);
        if (!matchedItem?.children) return p;
        const childKeys = new Set(matchedItem.children.map((c) => c.key));
        return {
          ...p,
          children: p.children.filter((el) => childKeys.has(el.elementId)),
        };
      });
  }, [isSearch, items, parsedProperties]);

  if (!elements && isLoading) {
    return (
      <div
        style={{ display: "flex", justifyContent: "center", padding: "24px" }}
      >
        <Spin />
      </div>
    );
  }

  if (parsedProperties.length === 0) {
    return (
      <Empty description="Properties not found" style={{ padding: "24px" }} />
    );
  }

  return (
    <div className={styles.usedPropertiesTree}>
      <SidebarSearch
        items={allItems.current}
        onSearch={handleSearch}
        onClear={() => {
          setItems(allItems.current);
          setIsSearch(false);
          setOpenKeysState(openKeysBeforeSearch.current);
        }}
      />
      {displayProperties.map((property) => {
        const isExpanded = openKeysState.includes(property.id);

        return (
          <div key={property.id} className={styles.propertyItem}>
            <div
              className={`${styles.propertyRow} ${styles.menuItemContainer}`}
              onClick={() => toggleProperty(property.id)}
            >
              <div className={styles.leftContent}>
                <span className={styles.propertySource}>
                  {property.sourceCode}
                </span>
                <span className={styles.propertyName}>{property.name}</span>
              </div>
              <div className={styles.rightContent}>
                <span className={styles.propertyType}>
                  [{property.isArray ? "array of " : ""}
                  {property.type}]
                </span>
                <span className={styles.propertyChildrenCount}>
                  {property.childrenCount > 99 ? "99+" : property.childrenCount}
                </span>
                <span className={styles.expandIcon}>
                  <OverridableIcon
                    name={isExpanded ? "caretDownFilled" : "caretRightFilled"}
                    style={{ fontSize: 10 }}
                  />
                </span>
              </div>
            </div>

            {isExpanded && (
              <div className={styles.elementsContainer}>
                {property.children.map((element) => (
                  <div
                    key={element.id}
                    className={`${styles.elementRow} ${styles.menuItemContainer}`}
                    onClick={(e) => {
                      e.stopPropagation();
                      onElementSingleClick?.(element.elementId);
                    }}
                    onDoubleClick={(e) => {
                      e.stopPropagation();
                      onElementDoubleClick?.(element.elementId);
                    }}
                  >
                    <div className={styles.leftContent}>
                      <OverridableIcon
                        name={element.type as IconName}
                        style={{ fontSize: 16, marginRight: 8 }}
                      />
                      <div className={styles.elementInfo}>
                        <div className={styles.elementName}>{element.name}</div>
                        <span className={styles.elementType}>
                          {element.typeTitle}
                        </span>
                      </div>
                    </div>
                    <div className={styles.rightContent}>
                      {element.operations.map((op, idx) => {
                        const colorClass =
                          op.operationColor === "green"
                            ? styles.operationGreen
                            : styles.operationBlue;
                        return (
                          <span
                            key={idx}
                            className={`${styles.operationChip} ${colorClass}`}
                          >
                            {op.operation}
                          </span>
                        );
                      })}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
};
