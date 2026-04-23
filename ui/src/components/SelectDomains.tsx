import React, { useCallback } from "react";
import { Select, Space, Tag } from "antd";
import { useDomains } from "../hooks/useDomains.tsx";
import { LabelInValueType } from "rc-select/lib/Select";
import type { FlattenOptionData } from "rc-select/lib/interface";
import { DomainType, EngineDomain } from "../api/apiTypes.ts";

export type Domain = {
  name: string;
  type: DomainType;
};

export type SelectDomainsProperties = {
  value?: Domain[];
  onChange?: (value: Domain[]) => void;
};

function getDomainType(
  domainId: string,
  domains: EngineDomain[],
): DomainType {
  return (
    domains.find((domain) => domainId === domain.id)?.type ?? DomainType.MICRO
  );
}

export const SelectDomains: React.FC<SelectDomainsProperties> = ({
  value,
  onChange,
}) => {
  const { isLoading: isDomainsLoading, domains } = useDomains();

  const renderOption = useCallback(
    (props: LabelInValueType | FlattenOptionData<unknown>) => {
      const domainType = getDomainType(props.value?.toString() ?? "", domains);
      return domainType === DomainType.MICRO ? (
        <Space size={"small"}>
          <Tag>micro</Tag>
          <span>{props.value}</span>
        </Space>
      ) : (
        props.label
      );
    },
    [domains],
  );

  return (
    <Select
      value={value?.map(domain => domain.name)}
      loading={isDomainsLoading}
      mode="tags"
      allowClear
      labelRender={renderOption}
      optionRender={renderOption}
      options={domains.map((domain) => ({
        value: domain.id,
        label: domain.name
      }))}
      onChange={(values) => {
        onChange?.(values.map(name =>({ name, type: getDomainType(name, domains )})));
      }}
    ></Select>
  );
};
