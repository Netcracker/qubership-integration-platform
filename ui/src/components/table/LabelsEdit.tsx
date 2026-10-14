import React, { useState } from "react";
import { Form, Select, SelectProps } from "antd";

export type LabelsEditorProps = {
  name: string;
};

export const LabelsEdit: React.FC<LabelsEditorProps> = ({ name }) => {
  const [options, setOptions] = useState<SelectProps["options"]>([]);
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
        classNames={{ popup: { root: "not-displayed" } }}
        suffixIcon={<></>}
      />
    </Form.Item>
  );
};
