import React from "react";
import { EndpointMock } from "../../api/apiTypes.ts";
import { EnabledTag } from "./TestingTags.tsx";
import { formatMockNumber } from "./endpointMocks.ts";
import {
  auditSection,
  chainItem,
  DetailsSidebar,
  elementItem,
  idItem,
  renderOptional,
} from "../DetailsSidebar.tsx";

export type EndpointMockDetailsDrawerProps = {
  endpointMock: EndpointMock | null;
  chainName: string;
  elementName: string;
  open: boolean;
  onClose: () => void;
};

export const EndpointMockDetailsDrawer: React.FC<
  EndpointMockDetailsDrawerProps
> = ({ endpointMock, chainName, elementName, open, onClose }) => {
  const chainId = endpointMock?.endpointReference?.chainId;
  const elementId = endpointMock?.endpointReference?.elementId;
  const matchers = endpointMock?.requestMatchers ?? [];

  return (
    <DetailsSidebar
      title="Endpoint Mock Details"
      open={open}
      onClose={onClose}
      sections={[
        {
          key: "general-info",
          title: "general info",
          items: [
            idItem(endpointMock?.id),
            { label: "Name", value: renderOptional(endpointMock?.name) },
            {
              label: "Description",
              value: renderOptional(endpointMock?.description),
            },
          ],
        },
        {
          key: "endpoint",
          title: "endpoint",
          items: [
            chainItem(chainId, chainName),
            elementItem("Endpoint", chainId, elementId, elementName),
          ],
        },
        {
          key: "status",
          title: "status",
          items: [
            {
              label: "Enabled",
              value: renderOptional(endpointMock?.enabled, (v) => (
                <EnabledTag enabled={v} />
              )),
            },
            {
              label: "Response Code",
              value: renderOptional(endpointMock?.responseSettings?.status),
            },
            {
              label: "Response Delay (ms)",
              value: formatMockNumber(endpointMock?.responseSettings?.delay),
            },
            { label: "Rules", value: matchers.length },
            {
              label: "Active Rules",
              value: matchers.filter((matcher) => matcher.enabled).length,
            },
          ],
        },
        auditSection(endpointMock),
      ]}
    />
  );
};
