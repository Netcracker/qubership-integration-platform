import {
  Button,
  Card,
  DatePicker,
  Dropdown,
  Flex,
  GetProps,
  Space,
  theme,
  Typography,
} from "antd";
import dayjs, { Dayjs } from "dayjs";
import React, { useState } from "react";

type DateRangePickerProps = {
  onRangeApply: (from: Date, to: Date) => void;
  trigger: React.ReactNode;
};

const { Text } = Typography;

type RangePickerProps = GetProps<typeof DatePicker.RangePicker>;
const { RangePicker } = DatePicker;

const DateRangePicker: React.FC<DateRangePickerProps> = ({
  onRangeApply,
  trigger,
}) => {
  const { token } = theme.useToken();
  const [open, setOpen] = useState(false);
  const [fromDate, setFromDate] = useState<Dayjs>(
    dayjs().subtract(1, "day").startOf("day"),
  );
  const [toDate, setToDate] = useState<Dayjs>(
    dayjs().subtract(1, "day").endOf("day"),
  );

  const isValidRange = fromDate.isBefore(toDate);

  const handleQuickApply = (durationMinutes: number) => {
    const now = new Date();
    const from = dayjs(now).subtract(durationMinutes, "minute").toDate();
    onRangeApply(from, now);
    setFromDate(dayjs().subtract(1, "day").startOf("day"));
    setToDate(dayjs().subtract(1, "day").endOf("day"));
    setOpen(false);
  };

  const handleApply = () => {
    if (!isValidRange) {
      return;
    }
    onRangeApply(fromDate.toDate(), toDate.toDate());
    setFromDate(dayjs().subtract(1, "day").startOf("day"));
    setToDate(dayjs().subtract(1, "day").endOf("day"));
    setOpen(false);
  };

  const onOk = () => {
    if (!isValidRange) {
      return;
    }
  };

  const dropdownContent = (
    <div
      style={{
        background: token.colorBgElevated,
        borderRadius: token.borderRadiusLG,
        boxShadow: token.boxShadowSecondary,
      }}
    >
      <Card title="Export Time Range">
        <Flex
          vertical
          gap={8}
          style={{
            alignItems: "flex-start",
            marginBottom: 12,
          }}
        >
          <Text type="secondary">Time Range</Text>
          <RangePicker
            showTime={{ format: "HH:mm" }}
            format="YYYY-MM-DD HH:mm"
            onChange={(value: RangePickerProps["value"], dateString) => {
              console.log("Selected Time: ", value);
              console.log("Formatted Selected Time: ", dateString);
              setFromDate(dayjs(dateString[0]));
              setToDate(dayjs(dateString[1]));
            }}
            style={{ width: "100%", marginBottom: 8, marginTop: 8 }}
            onOk={onOk}
          />
          <Button type="link" size="small" onClick={() => handleQuickApply(15)}>
            Last 15 min
          </Button>
          <Button type="link" size="small" onClick={() => handleQuickApply(30)}>
            Last 30 min
          </Button>
          <Button type="link" size="small" onClick={() => handleQuickApply(60)}>
            Last 1 hour
          </Button>
          <Button
            type="link"
            size="small"
            onClick={() => handleQuickApply(360)}
          >
            Last 6 hours
          </Button>
          <Button
            type="link"
            size="small"
            onClick={() => handleQuickApply(1440)}
          >
            Last 24 hours
          </Button>
        </Flex>

        <Space>
          <Button onClick={() => setOpen(false)}>Cancel</Button>
          <Button type="primary" onClick={handleApply} disabled={!isValidRange}>
            Apply
          </Button>
        </Space>
      </Card>
    </div>
  );

  return (
    <Dropdown
      open={open}
      onOpenChange={setOpen}
      popupRender={() => dropdownContent}
      trigger={["click"]}
    >
      <a onClick={(e) => e.preventDefault()}>{trigger}</a>
    </Dropdown>
  );
};

export default DateRangePicker;
