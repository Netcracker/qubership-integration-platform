import React from "react";
import { Tag } from "antd";
import { SystemOperation } from "../../../api/apiTypes";
import { SourceFlagTag } from "./SourceFlagTag";

// Keeps the badge fields resolved the same way in both the component and the
// `hasOperationBadges` predicate.
function resolveBadgeFields(operation: SystemOperation) {
  return {
    protocol: operation.binding ?? operation.operationType,
  };
}

/**
 * Shared operation badges (protocol, rpc method, deprecated flag). The channel
 * is deliberately absent: it already has a column of its own in the operations
 * table. Rendered as a fragment so each caller supplies its own container and
 * any extra slots (HTTP method, summary). Each badge renders only when its
 * field is set.
 */
export const OperationBadges: React.FC<{ operation: SystemOperation }> = ({
  operation,
}) => {
  const { protocol } = resolveBadgeFields(operation);

  return (
    <>
      {protocol && <SourceFlagTag source={protocol} kind="protocol" />}
      {operation.rpcMethod && <Tag>{operation.rpcMethod}</Tag>}
      {operation.isDeprecated && <Tag color="warning">Deprecated</Tag>}
    </>
  );
};

/** True when at least one shared operation badge would render. */
export function hasOperationBadges(operation: SystemOperation): boolean {
  const { protocol } = resolveBadgeFields(operation);
  return !!protocol || !!operation.rpcMethod || !!operation.isDeprecated;
}
