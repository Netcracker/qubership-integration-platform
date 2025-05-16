import React from "react";
import { Flex } from "antd";
import TextArea from "antd/lib/input/TextArea";

type SessionElementBodyViewProps = React.HTMLAttributes<HTMLElement> & {
  title: string;
  headers: Record<string, string>;
  body: string;
};

export const SessionElementBodyView: React.FC<SessionElementBodyViewProps> = ({
  title,
  headers,
  body,
  ...rest
}) => {
  return (
    <Flex {...rest} vertical gap={8}>
      <div>{title}</div>
      <TextArea
        style={{ flexGrow: 1, flexShrink: 1, resize: "none" }}
        value={body}
      />
    </Flex>
  );
};
