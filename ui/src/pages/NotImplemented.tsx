import React from "react";
import { Result } from "antd";
import { ResultProps } from "antd/es/result";

export const NotImplemented: React.FC<ResultProps> = (props: ResultProps) => {
  return <Result {...props} title="Not implemented yet" />;
};
