import { Checkbox, Col, Form, FormItemProps, Input, Row } from "antd";
import React, { useEffect } from "react";

export type TimestampFormatParametersProps = {
  offset: number;
  caption: string;
  layout: FormItemProps["layout"];
};

export const TimestampFormatParameters: React.FC<
  TimestampFormatParametersProps
> = ({ offset, caption, layout }) => {
  const [isUnixEpoch, setIsUnixEpoch] = React.useState<boolean>(false);
  const form = Form.useFormInstance();

  useEffect(() => {
    const value = form.getFieldValue(["parameters", offset]) as string;
    setIsUnixEpoch(!!value && value === "true");
  }, [form, offset]);

  return (
    <>
      <span>{caption}</span>
      <Form.Item
        name={["parameters", offset]}
        valuePropName="checked"
        getValueProps={(value) => ({ checked: value === "true" })}
        normalize={(value) => String(value)}
      >
        <Checkbox
          onChange={(event) => {
            setIsUnixEpoch(event.target.checked);
          }}
        >
          Unix epoch
        </Checkbox>
      </Form.Item>
      <Form.Item
        labelCol={layout === "vertical" ? { flex: "0" } : undefined}
        layout={layout}
        hidden={isUnixEpoch}
        name={["parameters", offset + 1]}
        label="Format"
        rules={[
          {
            required: !isUnixEpoch,
            message: "Format is required",
          },
        ]}
      >
        <Input style={{ width: "100%" }} />
      </Form.Item>
      <Form.Item
        labelCol={layout === "vertical" ? { flex: "0" } : undefined}
        layout={layout}
        hidden={isUnixEpoch}
        name={["parameters", offset + 2]}
        label="Locale"
      >
        <Input />
      </Form.Item>
      <Form.Item
        labelCol={layout === "vertical" ? { flex: "0" } : undefined}
        layout={layout}
        hidden={isUnixEpoch}
        name={["parameters", offset + 3]}
        label="Time zone"
      >
        <Input />
      </Form.Item>
    </>
  );
};

export const FormatDateTimeParameters: React.FC = () => {
  return (
    <Row style={{ width: "100%" }} gutter={16}>
      <Col span={12}>
        <TimestampFormatParameters
          offset={0}
          caption="Input"
          layout="vertical"
        />
      </Col>
      <Col span={12}>
        <TimestampFormatParameters
          offset={4}
          caption="Output"
          layout="vertical"
        />
      </Col>
    </Row>
  );
};
