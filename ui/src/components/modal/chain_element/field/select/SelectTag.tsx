import { Tag } from "antd";

type ServiceTagProps = {
  value: string;
  width?: number;
};

export const SelectTag: React.FC<ServiceTagProps> = (props: ServiceTagProps) => {
  return (
    <Tag
      style={{
        background: "#d9d9d9",
        borderRadius: 8,
        border: "none",
        width: props.width ?? 110,
        textAlign: "center",
        fontWeight: 500,
      }}
    >
      {props.value}
    </Tag>
  );
};
