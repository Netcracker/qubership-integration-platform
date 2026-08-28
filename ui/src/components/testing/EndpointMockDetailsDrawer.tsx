import React from "react";
import { EndpointMock } from "../../api/apiTypes.ts";
import { EMPTY } from "./testingAudit.tsx";
import { EnabledTag } from "./TestingTags.tsx";
import { formatMockNumber } from "./endpointMocks.ts";
import {
  auditSection,
  chainItem,
  elementItem,
  idItem,
  TestingDetailsDrawer,
} from "./TestingDetailsDrawer.tsx";

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
    <TestingDetailsDrawer
      title="Endpoint Mock Details"
      open={open}
      onClose={onClose}
      sections={
        !endpointMock
          ? []
          : [
              [
                idItem(endpointMock.id),
                { label: "Name", children: endpointMock.name || EMPTY },
                {
                  label: "Description",
                  children: endpointMock.description || EMPTY,
                },
              ],
              [
                chainItem(chainId, chainName),
                elementItem("Endpoint", chainId, elementId, elementName),
              ],
              [
                {
                  label: "Status",
                  children: <EnabledTag enabled={endpointMock.enabled} />,
                },
                {
                  label: "Response status",
                  children: formatMockNumber(
                    endpointMock.responseSettings?.status,
                  ),
                },
                {
                  label: "Response delay, ms",
                  children: formatMockNumber(
                    endpointMock.responseSettings?.delay,
                  ),
                },
                { label: "Rules", children: matchers.length },
                {
                  label: "Active rules",
                  children: matchers.filter((matcher) => matcher.enabled)
                    .length,
                },
              ],
              auditSection(endpointMock),
            ]
      }
    />
  );
};
