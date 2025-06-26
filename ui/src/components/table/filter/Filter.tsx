import { Button, Form, Modal } from "antd"
import { useModalContext } from "../../../ModalContextProvider";
import { FilterItem, FilterItemState } from "./FilterItem";
import { ClearOutlined, PlusOutlined } from "@ant-design/icons";
import { useState } from "react";
import { FilterColumn } from "./filter";

export type FilterProps = {
  filterColumns: FilterColumn[];
  filterItemStates: FilterItemState[];
  onApplyFilters: (filterItems: FilterItemState[]) => void;
}

export const Filter = (props: FilterProps) => {
  function buildMapWithInitialItem(): Map<string, FilterItemState> {
    const result: Map<string, FilterItemState> = new Map();
    const initialItem: FilterItemState = { id: crypto.randomUUID() };
    result.set(initialItem.id, initialItem);
    return result;
  }

  function buildInitialItemsMap(): Map<string, FilterItemState> {
    if (props.filterItemStates.length > 0) {
      const result: Map<string, FilterItemState> = new Map();
      for (const item of props.filterItemStates) {
        result.set(item.id, item);
      }
      return result;
    } else {
      return buildMapWithInitialItem();
    }
  }

  const { closeContainingModal } = useModalContext();
  const [form] = Form.useForm();
  const [items, setItems] = useState<Map<string, FilterItemState>>(buildInitialItemsMap());

  const removeFilterItem = (id: string) => {
    const newState = new Map(items);
    newState.delete(id);
    
    setItems(newState.size === 0 ? buildMapWithInitialItem() : newState);
  }

  const duplicateFilterItem = (id: string) => {
    const newItem = {...items.get(id), id: crypto.randomUUID()};
    addNewItem(newItem);
  }

  const filterItems = Array.from(items.values()).map(itemState =>
    <FilterItem key={itemState.id}
      state={itemState}
      filterColumns={props.filterColumns}
      onRemove={() => removeFilterItem(itemState.id)}
      onDuplicate={() => duplicateFilterItem(itemState.id)}
      onValueChange={updateStateFromChildItem}
    ></FilterItem>
  );

  const addFilterItem = () => {
    const newItem: FilterItemState = {
      id: crypto.randomUUID()
    };

    addNewItem(newItem);
  }

  const apply = () => {
    const values = Array.from(clearEmptyItems().values());
    props.onApplyFilters(values);
    closeContainingModal();
  }

  const clearAll = () => {
    setItems(buildMapWithInitialItem());
  }

  function updateStateFromChildItem(item: FilterItemState) {
    items.set(item.id, item);
  }

  function addNewItem(item: FilterItemState) {
    const newMap = new Map(items);
    newMap.set(item.id, item);

    setItems(newMap);
  }

  function clearEmptyItems(): Map<string, FilterItemState> {
    const nonEmptyItems = new Map(items);
    for (const [key, value] of nonEmptyItems) {
      if (value.columnValue === undefined || value.conditionValue === undefined || value.value === undefined || value.value.trim().length === 0) {
        nonEmptyItems.delete(key);
      }
    }
    return nonEmptyItems;
  }

  return <Modal
    title="Filter"
    open={true}
    onCancel={closeContainingModal}
    footer={[
      <Button key="cancel" onClick={closeContainingModal}>
        Cancel
      </Button>,
      <Button
        key="apply"
        type="primary"
        onClick={apply}
      >
        Apply
      </Button>
    ]}
  >
    <Form
      form={form}
    >
      {filterItems}
      <Button type="link" onClick={addFilterItem}><PlusOutlined /> Add Filter</Button>
      <Button type="link" onClick={clearAll}><ClearOutlined /> Clear All</Button>
    </Form>
  </Modal>
};
