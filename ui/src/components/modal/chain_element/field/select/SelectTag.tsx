import { Tooltip } from "antd";
import styles from "./selectOptionValue.module.css";

type SelectTagProps = {
  value: string;
};

// Quiet facet chip shown before a select value (service type / spec group /
// operation name). Truncation and theming live in the CSS module.
export const SelectTag: React.FC<SelectTagProps> = (props: SelectTagProps) => (
  <Tooltip title={props.value}>
    <span className={styles.chip}>{props.value}</span>
  </Tooltip>
);
