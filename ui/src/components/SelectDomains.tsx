import React, { useCallback, useEffect, useState } from "react";
import { Select } from "antd";
import { useDomains } from "../hooks/useDomains.tsx";
import type { LabelRenderProps, OptionRenderProps } from "../types/antd.ts";
import { DomainType, EngineDomain } from "../api/apiTypes.ts";
import { getConfig, onConfigChange } from "../appConfig.ts";
import { EngineDomainItem } from "./EngineDomainItem.tsx";

export type Domain = {
  name: string;
  type: DomainType;
};

export type SelectDomainsProperties = {
  value?: Domain[];
  onChange?: (value: Domain[]) => void;
};

export function getDomainType(
  domainId: string,
  domains: EngineDomain[],
): DomainType {
  return (
    domains.find((domain) => domainId === domain.id)?.type ?? DomainType.MICRO
  );
}

export function getDomainOptionNode(
  props: LabelRenderProps | OptionRenderProps,
  domains: EngineDomain[],
) {
  const domainType = getDomainType(props.value?.toString() ?? "", domains);
  return (
    <EngineDomainItem
      type={domainType}
      name={domainType === DomainType.MICRO ? props.value : props.label}
    />
  );
}

export const SelectDomains: React.FC<SelectDomainsProperties> = ({
  value,
  onChange,
}) => {
  const { isLoading: isDomainsLoading, domains } = useDomains();
  const [domainTypes, setDomainTypes] = useState<DomainType[]>([]);

  useEffect(() => {
    const updateDomainTypes = (cfg: ReturnType<typeof getConfig>) => {
      setDomainTypes(cfg.domainTypes ?? [DomainType.CLASSIC, DomainType.MICRO]);
    };
    updateDomainTypes(getConfig());
    return onConfigChange((config) => updateDomainTypes(config));
  }, []);

  const renderOption = useCallback(
    (props: LabelRenderProps | OptionRenderProps) => {
      return getDomainOptionNode(props, domains);
    },
    [domains],
  );

  return (
    <Select
      value={value?.map((domain) => domain.name)}
      loading={isDomainsLoading}
      mode={domainTypes.includes(DomainType.MICRO) ? "tags" : "multiple"}
      allowClear
      labelRender={renderOption}
      optionRender={renderOption}
      options={domains.map((domain) => ({
        value: domain.id,
        label: domain.name,
      }))}
      onChange={(values) => {
        onChange?.(
          values.map((name) => ({ name, type: getDomainType(name, domains) })),
        );
      }}
    ></Select>
  );
};
