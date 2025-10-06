import Navigation from "./components/Navigation.tsx";
import { BrowserRouter, Navigate, Route, Routes } from "react-router";
import Chains from "./pages/Chains";
import ChainPage from "./pages/ChainPage.tsx";
import { Layout } from "antd";

import styles from "./App.module.css";
import { Modals } from "./Modals.tsx";
import { Snapshots } from "./pages/Snapshots.tsx";
import { Deployments } from "./pages/Deployments.tsx";
import { ChainGraph } from "./pages/ChainGraph.tsx";
import { NotFound } from "./pages/NotFound.tsx";
import { Content } from "antd/es/layout/layout";
import { LoggingSettings } from "./pages/LoggingSettings.tsx";
import { Sessions } from "./pages/Sessions.tsx";
import { SessionPage } from "./pages/SessionPage.tsx";
import { ChainProperties } from "./pages/ChainProperties.tsx";
import { EventNotification } from "./components/notifications/EventNotification.tsx";

import { CommonVariables } from "./components/admin_tools/variables/CommonVariables.tsx";
import { SecuredVariables } from "./components/admin_tools/variables/SecuredVariables.tsx";
import { Domains } from "./components/admin_tools/domains/Domains.tsx";
import { ActionsLog } from "./components/admin_tools/ActionsLog.tsx";
import { NotImplemented } from "./pages/NotImplemented.tsx";
import { SessionsPage } from "./pages/SessionsPage.tsx";
import Services from "./pages/Services.tsx";
import { ServiceParametersPage } from "./components/services/ServiceParametersPage.tsx";
import AdminTools from "./pages/AdminTools.tsx";
import { Masking } from "./pages/Masking.tsx";
import { getIcons } from "./appConfig.ts";
import { IconProvider } from "./IconProvider.tsx";

const { Header } = Layout;

const App = () => {
  return (
    <IconProvider icons={getIcons()}>
      <Layout className={styles.layout}>
        <EventNotification>
          <Modals>
            <Header className={styles.header}>
              <Navigation />
            </Header>
            <Content className={styles.content}>
              <BrowserRouter>
                <Routes>
                  <Route path="/admintools" element={<AdminTools />}>
                    <Route path="" element={<Navigate to="domains" />} />
                    <Route path="domains" element={<Domains />} />
                    <Route path="engine-list" element={<Navigate to="../domains" relative={"path"} />} />
                    <Route path="variables/common" element={<CommonVariables />} />
                    <Route path="variables/secured" element={<SecuredVariables />} />
                    <Route path="audit" element={<ActionsLog />} />
                    <Route path="sessions" element={<SessionsPage />} />
                  </Route>
                  <Route index path="/" element={<Navigate to="/chains" />} />
                  <Route index path="/chains" element={<Chains />} />
                  <Route path="/chains/:chainId" element={<ChainPage />}>
                    <Route index element={<ChainGraph />} />
                    <Route index path="graph" element={<ChainGraph />} />
                    <Route path="graph/:elementId" element={<ChainGraph />} />
                    <Route path="snapshots" element={<Snapshots />} />
                    <Route path="deployments" element={<Deployments />} />
                    <Route path="sessions" element={<Sessions />} />
                    <Route path="sessions/:sessionId" element={<SessionPage />} />
                    <Route path="logging-settings" element={<LoggingSettings />} />
                    <Route path="masking" element={<Masking />} />
                    <Route path="properties" element={<ChainProperties />} />
                  </Route>
                  <Route path="/services" element={<Services />} />
                  <Route path="/services/systems/:systemId/parameters" element={<ServiceParametersPage />} />
                  <Route path="/services/systems/:systemId/specificationGroups" element={<ServiceParametersPage />} />
                  <Route path="/services/systems/:systemId/specificationGroups/:groupId/specifications" element={<ServiceParametersPage />} />
                  <Route path="/services/systems/:systemId/specificationGroups/:groupId/specifications/:specId/operations" element={<ServiceParametersPage />} />
                  <Route path="/services/systems/:systemId/environments" element={<ServiceParametersPage />} />
                  <Route path="*" element={<NotFound/>}/>
                  <Route path="/not-implemented" element={<NotImplemented />} />
                </Routes>
              </BrowserRouter>
            </Content>
          </Modals>
        </EventNotification>
      </Layout>
    </IconProvider>
  );
}
export default App;
