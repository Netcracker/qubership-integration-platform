import React from "react";
import { TestingMatcher } from "../../../api/apiTypes.ts";
import { useEndpointMockEditor } from "../../../pages/testing/EndpointMockPage.tsx";
import { MatchersTable } from "../MatchersTable.tsx";

export const EndpointMockRequestMatchersTab: React.FC = () => {
  const { endpointMock, readonly, onChange } = useEndpointMockEditor();

  return (
    <MatchersTable
      kind="request"
      matchers={endpointMock.requestMatchers}
      readonly={readonly}
      onChange={(requestMatchers: TestingMatcher[]) =>
        onChange({ requestMatchers })
      }
    />
  );
};
