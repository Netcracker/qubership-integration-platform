import { CopyOutlined, DeleteOutlined, EllipsisOutlined } from "@ant-design/icons";
import { Button, Col, Dropdown, Form, MenuProps, Row, Select, SelectProps } from "antd"
import { ItemType } from "antd/es/menu/interface";
import { FilterCondition, FilterColumn, FilterConditions, FilterValueType } from "./filter";
import { useEffect, useState } from "react";
import { FilterValue } from "./value/FilterValue";


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
  }>({ column: undefined, conditionOptions: [], conditions: undefined });
  const [condition, setCondition] = useState<FilterCondition>();
  const [state, setState] = useState<FilterItemState>(props.state);

  useEffect(() => {
    if (props.state.columnValue) {
      changeColumn(props.state.columnValue);
    }
    if (props.state.conditionValue) {
      changeCondition(props.state.conditionValue);
    }
  }, []);

  const actionItems: ItemType[] = [
    {
      key: 'remove',
      label: <><DeleteOutlined /> Remove</>,
    },
    {
      key: 'duplicate',
      label: <><CopyOutlined /> Duplicate</>,
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
    const column: FilterColumn | null = props.filterColumns.find((filter: FilterColumn) => filter.id === value)??null;

    if (column) {
      const conditionOptions = column.conditions.allowedConditions
        .map((condition: FilterCondition) => ({ value: condition.id, label: condition.name }));

      setColumnData({column: column, conditionOptions: conditionOptions, conditions: column.conditions});

      updateCurrentAndParentStates({...state, columnValue: value, conditionValue: column.conditions.defaultCondition.id, value: undefined});
    }
  };

  const changeCondition = (value: string) => {
    const currentCondition: FilterCondition | undefined = columnData.conditions?.allowedConditions.find(allowedCondition => allowedCondition.id == value);

    setCondition(currentCondition);

    updateCurrentAndParentStates({ ...state, conditionValue: value });
  }

  const handleStringValue = (value: string | undefined) => {
    updateCurrentAndParentStates({...state, value: value});
  }

  function updateCurrentAndParentStates(newState: FilterItemState) {
    setState(newState);
    props.onValueChange(newState);
  }

  const itemStyle = { marginBottom: 8 };

  return <Row gutter={8}>
    <Col span={7}>
      <Form.Item style={itemStyle}>
        <Select placeholder="Column" options={columnOptions()} onChange={changeColumn} value={state.columnValue}/>
      </Form.Item>
    </Col>
    <Col span={7}>
      <Form.Item style={itemStyle}>
        <Select disabled={!columnData.column} placeholder="Condition" options={columnData.conditionOptions} onChange={changeCondition} value={state.conditionValue} />
      </Form.Item>
    </Col>
    <Col span={7}>
      <Form.Item style={itemStyle}>
        <FilterValue type={columnData.column?.conditions?.valueType ?? FilterValueType.STRING}
          condition={condition}
          handleStringValue={handleStringValue}
          allowedValues={columnData.column?.allowedValues??[]}
          value={state.value}
        />
      </Form.Item>
    </Col>
    <Col span={3}>
      <Dropdown menu={actionProps}>
        <Button icon={<EllipsisOutlined />} />
      </Dropdown>
    </Col>
  </Row>
}
