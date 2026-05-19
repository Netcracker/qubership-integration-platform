import React from "react";
import { useLocationHash } from "./useLocationHash.tsx";
import { ServicesList } from "./ServicesList.tsx";
import { NotFound } from "../../pages/NotFound.tsx";
import { McpServiceList } from "./mcp/McpServiceList.tsx";
import { ContextServiceList } from "./context/ContextServiceList.tsx";

export const ServiceListPage: React.FC = () => {
  const [tab] = useLocationHash("external");
  switch (tab) {
    case "mcp":
      return <McpServiceList />;
    case "external":
    case "internal":
    case "implemented":
      return <ServicesList tab={tab} />;
    case "context":
      return <ContextServiceList />;
    default:
      return <NotFound />;
  }
};
