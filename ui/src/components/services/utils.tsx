import React from "react";
import { Tag } from "antd";
import { isSpecification, ServiceEntity } from "./ServicesTreeTable";
import { Specification } from "../../api/apiTypes.ts";

export type UsageStatus = 'Deprecated' | 'In use' | 'New';

export function getUsageStatus(element:Specification): UsageStatus {
  if (element.deprecated) return 'Deprecated';
  if (element.chains && element.chains.length > 0) return 'In use';
  return 'New';
}

export const UsageStatusTag: React.FC<{ element: ServiceEntity }> = ({ element }) => {
  if (!isSpecification(element)) return null;
  const status = getUsageStatus(element);
  let color: string = 'blue';
  if (status === 'Deprecated') color = 'red';
  else if (status === 'In use') color = 'green';
  return <Tag color={color} style={{ borderRadius: 12 }}>{status}</Tag>;
};

export function prepareFile(file: File) {
  const now = new Date();
  const pad = (n: number) => n.toString().padStart(2, '0');
  const fileName = `export-${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours())}_${pad(now.getMinutes())}_${pad(now.getSeconds())}.zip`;
  return new File([file], fileName, { type: file.type });
}
