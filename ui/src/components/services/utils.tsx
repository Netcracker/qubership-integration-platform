import React from "react";
import { Tag } from "antd";
import { isSpecification, ServiceEntity } from "./ServicesTreeTable";
import {ContextSystem, IntegrationSystem, Specification} from "../../api/apiTypes.ts";
import { RcFile } from "antd/es/upload";

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

export function validateFiles(
  fileList: RcFile[],
  supportedExtensions: string[]
): { valid: boolean; message?: string } {
  if (fileList.length === 0) {
    return { valid: false, message: "Choose at least one file" };
  }

  const maxSize = 25 * 1024 * 1024; // 25MB in bytes
  const oversizedFiles = fileList.filter(file => file.size > maxSize);
  if (oversizedFiles.length > 0) {
    return {
      valid: false,
      message: `File(s) too large: ${oversizedFiles.map(f => f.name).join(', ')}. Maximum size is 25MB per file.`
    };
  }

  const invalidFiles = fileList.filter(file => {
    const extension = file.name.toLowerCase().substring(file.name.lastIndexOf('.'));
    return !supportedExtensions.includes(extension);
  });

  if (invalidFiles.length > 0) {
    return {
      valid: false,
      message: `Unsupported file type(s): ${invalidFiles.map(f => f.name).join(', ')}. Supported: ${supportedExtensions.join(', ')}`
    };
  }

  return { valid: true };
}

export const environmentLabels = {
    'DEVELOPMENT': 'Development',
    'DEVELOPMENT1': 'Development1',
    'DEVELOPMENT2': 'Development2',
    'QA': 'QA',
    'QA1': 'QA1',
    'QA2': 'QA2',
    'PRODUCTION': 'Production',
    'PRODUCTION1': 'Production1',
    'PRODUCTION2': 'Production2',
    'STAGING': 'Staging',
    'STAGING1': 'Staging1',
    'STAGING2': 'Staging2',
    'SVT': 'SVT',
    'SVT1': 'SVT1',
    'SVT2': 'SVT2',
};

export const environmentLabelOptions = Object.entries(environmentLabels).map(
    ([key, value]) => ({ label: value, value: key })
);

export const serviceCache: { [key: string]: IntegrationSystem } = {};

export const invalidateServiceCache = (systemId: string) => {
    delete serviceCache[systemId];
    console.log(`Service cache invalidated for: ${systemId}`);
};

export const contextServiceCache: { [key: string]: ContextSystem } = {};
