import TextArea from "antd/lib/input/TextArea";
import React from "react";

export type ExpressionEditorProps = {
  value?: string;
  onChange?: (value: string) => void;
};

export const ExpressionEditor: React.FC<ExpressionEditorProps> = ({
  value,
  onChange,
}) => {
  return (
    // TODO expression grammar, annotations, completion, etc.
    <TextArea
      style={{ height: "100%", resize: "none" }}
      value={value}
      autoSize={false}
      onChange={(event) => {
        onChange?.(event.target.value);
      }}
    />
  );
};
