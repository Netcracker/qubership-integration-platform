import { Badge, Button, Tooltip } from "antd";
import { Icon } from "../../../IconProvider.tsx";

export type FilterButtonProps = {
  count: number;
  onClick: () => void;
}

export const FilterButton = (props: FilterButtonProps) => {
  return (
    <Tooltip title="Filters">
      <Badge count={props.count}>
        <Button icon={<Icon name="filter" />} onClick={props.onClick} />
      </Badge>
    </Tooltip>
  );
}
