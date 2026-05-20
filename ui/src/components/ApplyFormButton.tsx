import React from "react";
import { Button } from "antd";

type ApplyFormButtonProps = {
  formId: string;
  loading?: boolean;
  disabled?: boolean;
};

export const ApplyFormButton: React.FC<ApplyFormButtonProps> = ({
  formId,
  loading,
  disabled,
}) => (
  <Button
    type="primary"
    htmlType="submit"
    form={formId}
    loading={loading}
    disabled={disabled}
  >
    Apply
  </Button>
);
