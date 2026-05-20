import React from "react";
import PageWithSidebar from "./PageWithSidebar";
import { Outlet } from "react-router-dom";
import { DevToolsSidebar } from "../components/dev_tools/DevToolsSidebar";

const DevTools: React.FC = () => {
  return (
    <PageWithSidebar sidebar={<DevToolsSidebar collapsed={false} />}>
      <Outlet />
    </PageWithSidebar>
  );
};

export default DevTools;
