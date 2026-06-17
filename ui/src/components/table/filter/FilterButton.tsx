import { Badge, Button, Tooltip } from "antd";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import styles from "./FilterButton.module.css";

export type FilterButtonProps = {
  count: number;
  onClick: () => void;
};

export const FilterButton = (props: FilterButtonProps) => {
  return (
    <Tooltip title="Filters">
      <span className={styles.wrapper}>
        <Badge count={props.count} size="small" offset={[-2, 2]}>
          <Button
            icon={<OverridableIcon name="filter" />}
            onClick={props.onClick}
          />
        </Badge>
      </span>
    </Tooltip>
  );
};
