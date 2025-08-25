import { Form, Select } from "antd";

export const TrimParameters: React.FC = () => {
  return <>
    <Form.Item
        name={["parameters", 0]}
        label="Side"
        rules={[{ required: true, message: "Side is required" }]}
    >
        <Select<string>
            options={[
                { label: 'Both', value: 'both' },
                { label: 'Left', value: 'left' },
                { label: 'Right', value: 'right' },
            ]}
        />
    </Form.Item>
  </>;
};
