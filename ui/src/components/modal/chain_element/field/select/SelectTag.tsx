import { Tag, Tooltip } from "antd";

type ServiceTagProps = {
  value: string;
};

export const SelectTag: React.FC<ServiceTagProps> = (
  props: ServiceTagProps,
) => {
  return (
    <Tooltip title={props.value}>
      <Tag
        style={{
          background: "#d9d9d9",
          borderRadius: 8,
          border: "none",
          width: 200,
          textAlign: "center",
          fontWeight: 500,
        }}
      >
        {props.value.length > 32
          ? props.value.slice(0, 32) + "..."
          : props.value}
      </Tag>
    </Tooltip>
  );
};
