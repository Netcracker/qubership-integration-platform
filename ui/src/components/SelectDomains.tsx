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

/**
 * Resolves domain IDs against the domains the catalog lists. An ID it doesn't list is a
 * micro-domain that a deploy creates, so it is kept as one while micro-domains are enabled and
 * dropped otherwise.
 */
export function resolveDomains(
  ids: string[],
  domains: EngineDomain[],
  microDomainsEnabled: boolean,
): EngineDomain[] {
  return ids.flatMap((id) => {
    const known = domains.find((domain) => domain.id === id);
    if (known) {
      return [known];
    }
    return microDomainsEnabled
      ? [{ id, name: id, type: DomainType.MICRO, replicas: 0, namespace: "" }]
      : [];
  });
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

/** The domain types the app config enables, kept current when the config changes. */
export function useDomainTypes(): {
  loaded: boolean;
  domainTypes: DomainType[];
} {
  const [loaded, setLoaded] = useState<boolean>(false);
  const [domainTypes, setDomainTypes] = useState<DomainType[]>([]);

  useEffect(() => {
    const updateDomainTypes = (cfg: ReturnType<typeof getConfig>) => {
      setDomainTypes(cfg.domainTypes ?? [DomainType.CLASSIC, DomainType.MICRO]);
      setLoaded(true);
    };
    updateDomainTypes(getConfig());
    return onConfigChange((config) => updateDomainTypes(config));
  }, []);

  return { loaded, domainTypes };
}

export const SelectDomains: React.FC<SelectDomainsProperties> = ({
  value,
  onChange,
}) => {
  const { isLoading: isDomainsLoading, domains } = useDomains();
  const { loaded: domainTypesLoaded, domainTypes } = useDomainTypes();

  const renderOption = useCallback(
    (props: LabelRenderProps | OptionRenderProps) => {
      return getDomainOptionNode(props, domains);
    },
    [domains],
  );

  return (
    <Select
      value={value?.map((domain) => domain.name)}
      loading={!domainTypesLoaded || isDomainsLoading}
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
