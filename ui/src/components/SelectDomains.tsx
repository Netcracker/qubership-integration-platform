import React, { useCallback, useEffect, useState } from "react";
import { Form, Select } from "antd";
import type { Rule } from "antd/lib/form/index";
import { useDomains } from "../hooks/useDomains.tsx";
import type { LabelRenderProps, OptionRenderProps } from "../types/antd.ts";
import { DomainType, EngineDomain } from "../api/apiTypes.ts";
import { getConfig, onConfigChange } from "../appConfig.ts";
import { EngineDomainItem } from "./EngineDomainItem.tsx";

export type Domain = {
  name: string;
  type: DomainType;
};

export const K8S_RESOURCE_NAME_MAX_LENGTH = 63;

export const K8S_RESOURCE_NAME_PATTERN_SOURCE = "[a-z](-*[a-z0-9])*";

export const K8S_RESOURCE_NAME_REGEX = new RegExp(
  `^${K8S_RESOURCE_NAME_PATTERN_SOURCE}$`,
);

export function getDomainNameError(name: string): string | undefined {
  if (name && name.length > K8S_RESOURCE_NAME_MAX_LENGTH) {
    return `Name exceeds maximum length of ${K8S_RESOURCE_NAME_MAX_LENGTH}: ${name}`;
  }
  if (!name || !K8S_RESOURCE_NAME_REGEX.test(name)) {
    return `Resource name should match pattern: ${K8S_RESOURCE_NAME_PATTERN_SOURCE}: ${name}`;
  }
  return undefined;
}

export function getInvalidDomainName(
  domains: Domain[] | undefined,
): string | undefined {
  const invalid = (domains ?? []).find(
    (domain) => getDomainNameError(domain.name) !== undefined,
  );
  return invalid ? getDomainNameError(invalid.name) : undefined;
}

export function isDomainSelectionInvalid(
  domains: Domain[] | undefined,
): boolean {
  return !domains?.length || getInvalidDomainName(domains) !== undefined;
}

export const domainNamesRule: Rule = {
  validator: (_, value: Domain[]) => {
    const message = getInvalidDomainName(value);
    return message ? Promise.reject(new Error(message)) : Promise.resolve();
  },
};

export type SelectDomainsProperties = {
  value?: Domain[];
  onChange?: (value: Domain[]) => void;
};

export type DomainsFormItemProperties = {
  label?: string;
};

export const DomainsFormItem: React.FC<DomainsFormItemProperties> = ({
  label = "Domains",
}) => {
  return (
    <Form.Item
      label={label}
      name="domains"
      rules={[
        { required: true, message: "Please specify at least one domain" },
        domainNamesRule,
      ]}
    >
      <SelectDomains />
    </Form.Item>
  );
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
