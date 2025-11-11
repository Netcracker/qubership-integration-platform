import { Tag } from "antd";

type HttpMethodProps = {
  value: unknown;
  width?: number;
};

export const HttpMethod: React.FC<HttpMethodProps> = (
  props: HttpMethodProps,
) => {
  const methodColors: Record<string, string> = {
    GET: "#61affe",
    POST: "#698679ff",
    PUT: "#fca130",
    DELETE: "#f93e3e",
    PATCH: "#50e3c2",
    QUERY: "#1890ff",
    MUTATION: "#52c41a",
    SUBSCRIPTION: "#722ed1",
  };

  const method = props.value as string | undefined;
  if (!method) return "-";

  const displayMethod = method.toUpperCase();

  const color = methodColors[displayMethod] || "#d9d9d9";
  return (
    <Tag
      style={{
        background: color,
        color: "#fff",
        borderRadius: 8,
        border: "none",
        textAlign: "center",
        fontWeight: 500,
        ...(props.width && {width: props.width})
      }}
    >
      {displayMethod}
    </Tag>
  );
};
