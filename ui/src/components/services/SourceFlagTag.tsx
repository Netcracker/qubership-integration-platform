import React from 'react';
import { Tag } from 'antd';

const sourceColors: Record<string, string> = {
  manual: 'blue',
  discovered: 'green',
  updated: 'blue',
  created: 'green',
  empty: 'gray',
  http: 'blue',
  kafka: 'green',
  amqp: 'orange',
  graphql: 'red',
  metamodel: 'violet',
  grpc: 'lightblue',
  soap: 'red',
  New: 'blue',
  use: 'green',
  deprecated: 'red',
  POSTGRESQL: 'blue',
};

const colorMap: Record<string, string> = {
  blue: '#1677ff',
  green: '#52c41a',
  orange: '#fa8c16',
  red: '#ff4d4f',
  gray: '#bfbfbf',
  violet: '#9012fe',
  lightblue: '#4FC0F8',
};

const displayNameOverrides: Record<string, string> = {
  grpc: 'gRPC',
  graphql: 'GraphQL',
  asyncapi: 'AsyncAPI',
};

function capitalize(str: string) {
  if (!str) return '';
  return str.charAt(0).toUpperCase() + str.slice(1).toLowerCase();
}

export const SourceFlagTag: React.FC<{ source?: string, toUpperCase?: boolean }> = ({ source, toUpperCase }) => {
  if (!source) return null;
  const key = source.toLowerCase();
  const colorName = sourceColors[key] || 'gray';
  const color = colorMap[colorName] || colorName;
  //const label = source + " " + key + " " + displayNameOverrides[key];
  const label = toUpperCase
    ? source.toUpperCase()
    : displayNameOverrides[key] || capitalize(source);
  return (
    <Tag style={{ background: color, color: '#fff', borderRadius: 12, border: 'none' }}>
      {label}
    </Tag>
  );
};
