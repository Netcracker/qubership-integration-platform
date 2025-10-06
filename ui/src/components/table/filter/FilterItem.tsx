import { Button, Col, Dropdown, Form, MenuProps, Row, Select, SelectProps } from "antd"
import { ItemType } from "antd/es/menu/interface";
import { FilterCondition, FilterColumn, FilterConditions, FilterValueType } from "./filter";
import { useCallback, useEffect, useState } from "react";
import { FilterValue } from "./value/FilterValue";
import { Icon } from "../../../IconProvider.tsx";


export type FilterItemState = {
  id: string;
  columnValue?: string;
  conditionValue?: string;
  value?: string;
}

export type FilterItemProps = {
  state: FilterItemState;
  filterColumns: FilterColumn[];
  onRemove: (key: string) => void;
  onDuplicate: (key: string) => void;
  onValueChange: (state: FilterItemState) => void;
}

export const FilterItem = (props: FilterItemProps) => {
  const [columnData, setColumnData] = useState<{
    column: FilterColumn | undefined;
    conditionOptions: SelectProps["options"];
    conditions: FilterConditions | undefined;
    condition: FilterCondition | undefined;
  }>({ column: undefined, conditionOptions: [], conditions: undefined, condition: undefined });
  const [state, setState] = useState<FilterItemState>(props.state);
  const propsOnValueChange = props.onValueChange;

  const updateCurrentAndParentStates = useCallback((newState: FilterItemState) => {
    setState(newState);
    propsOnValueChange(newState);
  }, [propsOnValueChange]);

  const setColumnAndCondition = useCallback((columnValue: string, conditionValue?: string) => {
    const column: FilterColumn = props.filterColumns.find((col: FilterColumn) => col.id === columnValue)!;

    const conditions = column.conditions;
    const conditionOptions = conditions.allowedConditions
      .map((condition: FilterCondition) => ({ value: condition.id, label: condition.name }));

    const condition: FilterCondition = conditionValue ? getCondition(conditions, conditionValue) : conditions.defaultCondition;

    setColumnData({ column: column, conditionOptions: conditionOptions, conditions: conditions, condition: condition });
    updateCurrentAndParentStates({ id: state.id, columnValue: columnValue, conditionValue: condition.id, value: conditionValue ? state.value : undefined });
  }, [props.filterColumns, state.id, state.value, updateCurrentAndParentStates]);

  useEffect(() => {
    if (state.columnValue) {
      setColumnAndCondition(state.columnValue, state.conditionValue);
    }
  }, [state.columnValue, state.conditionValue, setColumnAndCondition]);

  const actionItems: ItemType[] = [
    {
      key: 'remove',
      label: <><Icon name="delete" /> Remove</>,
    },
    {
      key: 'duplicate',
      label: <><Icon name="copy" /> Duplicate</>,
    },
  ];

  const onActionClick: MenuProps['onClick'] = ({ key }) => {
    switch (key) {
      case 'remove':
        props.onRemove(props.state.id);
        break;
      case 'duplicate':
        props.onDuplicate(props.state.id);
        break;
    }
  };

  const actionProps: MenuProps = {
    items: actionItems,
    onClick: onActionClick,
  };

  const columnOptions = (): SelectProps["options"] => {
    return props.filterColumns.map(filter => ({value: filter.id, label: filter.name}));
  }

  const changeColumn = (value: string) => {
    setColumnAndCondition(value);
  };

  const changeCondition = (value: string) => {
    setColumnData({...columnData, condition: getCondition(columnData.conditions!, value)});
    updateCurrentAndParentStates({ ...state, conditionValue: value });
  }

  const handleStringValue = (value: string | undefined) => {
    updateCurrentAndParentStates({...state, value: value});
  }

  function getCondition(conditions: FilterConditions, conditionValue: string): FilterCondition {
    return conditions.allowedConditions.find(allowedCondition => allowedCondition.id == conditionValue)!;
  }

  const itemStyle = { marginBottom: 8 };

  return <Row gutter={4}>
    <Col span={6}>
      <Form.Item style={itemStyle}>
        <Select placeholder="Column" options={columnOptions()} onChange={changeColumn} value={state.columnValue}/>
      </Form.Item>
    </Col>
    <Col span={6}>
      <Form.Item style={itemStyle}>
        <Select disabled={!columnData.column} placeholder="Condition" options={columnData.conditionOptions} onChange={changeCondition} value={state.conditionValue} />
      </Form.Item>
    </Col>
    <Col span={10}>
      <Form.Item style={itemStyle}>
        <FilterValue type={columnData.column?.conditions?.valueType ?? FilterValueType.STRING}
          condition={columnData.condition}
          handleStringValue={handleStringValue}
          allowedValues={columnData.column?.allowedValues??[]}
          value={state.value}
        />
      </Form.Item>
    </Col>
    <Col>
      <Dropdown menu={actionProps}>
        <Button icon={<Icon name="ellipsis" />} />
      </Dropdown>
    </Col>
  </Row>
}
