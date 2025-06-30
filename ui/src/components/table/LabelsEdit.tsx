import React, { useContext, useState } from "react";
import { Form, Select, SelectProps } from "antd";
import { InlineEditContext } from "../InlineEdit.tsx";

export type LabelsEditorProps = {
  name: string;
};

export const LabelsEdit: React.FC<LabelsEditorProps> = ({ name }) => {
  const [options, setOptions] = useState<SelectProps["options"]>([]);
  const inlineEditContext = useContext(InlineEditContext);
  const form = Form.useFormInstance();

  return (
    <Form.Item name={name} style={{ marginBottom: 0 }}>
      <Select
        autoFocus
        mode="tags"
        style={{ width: "100%" }}
        onChange={(_, opts) => {
          setOptions(opts as SelectProps["options"]);
        }}
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            form.submit();
          }
        }}
        tokenSeparators={[" "]}
        options={options}
        onBlur={() => inlineEditContext?.toggle()}
        classNames={{ popup: { root: "not-displayed" } }}
        suffixIcon={<></>}
      />
    </Form.Item>
  );
};
