import { FilterOutlined } from "@ant-design/icons";
import { Badge, Button, Tooltip } from "antd";

export type FilterButtonProps = {
  count: number;
  onClick: () => void;
}

export const FilterButton = (props: FilterButtonProps) => {
  return (
    <Tooltip title="Filters">
      <Badge count={props.count}>
        <Button icon={<FilterOutlined />} onClick={props.onClick} />
      </Badge>
    </Tooltip>
  );
}
