import { Badge, Button, Tooltip } from "antd";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";

export type FilterButtonProps = {
  count: number;
  onClick: () => void;
};

export const FilterButton = (props: FilterButtonProps) => {
  return (
    <Tooltip title="Filters">
      <Badge count={props.count}>
        <Button
          icon={<OverridableIcon name="filter" />}
          onClick={props.onClick}
        />
      </Badge>
    </Tooltip>
  );
};
