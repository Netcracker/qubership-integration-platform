import Navigation from "./components/Navigation.tsx";
import {
  createBrowserRouter,
  createRoutesFromElements,
  Navigate,
  Route,
  RouterProvider,
} from "react-router";
import Chains from "./pages/Chains";
import ChainPage from "./pages/ChainPage.tsx";
import { App as AntdApp, ConfigProvider, Layout } from "antd";

import styles from "./App.module.css";
import "./styles/theme-variables.css";
import "./index.css";
import "./styles/reactflow-theme.css";
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
import { AccessControl } from "./components/admin_tools/access-control/AccessControl.tsx"
import { NotImplemented } from "./pages/NotImplemented.tsx";
import { SessionsPage } from "./pages/SessionsPage.tsx";
import Services from "./pages/Services.tsx";
import { ServiceParametersPage } from "./components/services/ServiceParametersPage.tsx";
import AdminTools from "./pages/AdminTools.tsx";
import { Masking } from "./pages/Masking.tsx";
import { DocumentationPage } from "./pages/DocumentationPage.tsx";
import {
  initializeBrowserTheme,
  setupThemeListener,
  ThemeMode,
  applyThemeToDOM,
} from "./theme/themeInit.ts";
import { getAntdThemeConfig } from "./theme/antdTokens.ts";
import { IconProvider } from "./icons/IconProvider.tsx";
import { useEffect, useState } from "react";
import { getConfig } from "./appConfig.ts";
import { reapplyCssVariables } from "./config/initConfig.ts";
import { LiveExchanges } from "./components/admin_tools/exchanges/LiveExchanges.tsx";
import { ContextServiceParametersPage } from "./components/services/context/ContextServiceParametersPage.tsx";
import DevTools from "./pages/DevTools.tsx";
import { DiagnosticValidationPage } from "./components/dev_tools/DiagnosticValidationPage.tsx";

const { Header } = Layout;

const router = createBrowserRouter(
  createRoutesFromElements(
    <>
      <Route path="/devtools" element={<DevTools />}>
        <Route path="" element={<Navigate to="diagnostic/validations" />} />
        <Route
          path="diagnostic/validations"
          element={<DiagnosticValidationPage />}
        />
      </Route>
      <Route path="/admintools" element={<AdminTools />}>
        <Route path="" element={<Navigate to="domains" />} />
        <Route path="domains" element={<Domains />} />
        <Route
          path="engine-list"
          element={<Navigate to="../domains" relative={"path"} />}
        />
        <Route path="variables/common" element={<CommonVariables />} />
        <Route path="variables/secured" element={<SecuredVariables />} />
        <Route path="audit" element={<ActionsLog />} />
        <Route path="sessions" element={<SessionsPage />} />
        <Route path="access-control" element={<AccessControl />} />
        <Route path="exchanges" element={<LiveExchanges />} />
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
      <Route
        path="/services/systems/:systemId/parameters"
        element={<ServiceParametersPage />}
      />
      <Route
        path="/services/systems/:systemId/specificationGroups"
        element={<ServiceParametersPage />}
      />
      <Route
        path="/services/systems/:systemId/specificationGroups/:groupId/specifications"
        element={<ServiceParametersPage />}
      />
      <Route
        path="/services/systems/:systemId/specificationGroups/:groupId/specifications/:specId/operations"
        element={<ServiceParametersPage />}
      />
      <Route
        path="/services/systems/:systemId/specificationGroups/:groupId/specifications/:specId/operations/:operationId"
        element={<ServiceParametersPage />}
      />
      <Route
        path="/services/systems/:systemId/environments"
        element={<ServiceParametersPage />}
      />
      <Route
        path="/services/context/:systemId/parameters"
        element={<ContextServiceParametersPage />}
      />
      <Route path="/doc/*" element={<DocumentationPage />} />
      <Route path="*" element={<NotFound />} />
      <Route path="/not-implemented" element={<NotImplemented />} />
    </>,
  ),
);

const App = () => {
  const [theme, setTheme] = useState<ThemeMode>(() => {
    const initialTheme = initializeBrowserTheme();
    return initialTheme;
  });
  const [, setThemeUpdateKey] = useState(0);

  useEffect(() => {
    applyThemeToDOM(theme);
    const timeoutId = setTimeout(() => {
      setThemeUpdateKey((prev) => prev + 1);
    }, 300);
    return () => clearTimeout(timeoutId);
  }, [theme]);

  useEffect(() => {
    return setupThemeListener(setTheme);
  }, []);

  useEffect(() => {
    const handleThemeVariablesUpdated = () => {
      setThemeUpdateKey((prev) => prev + 1);
      reapplyCssVariables();
    };

    window.addEventListener(
      "theme-variables-updated",
      handleThemeVariablesUpdated,
    );
    return () => {
      window.removeEventListener(
        "theme-variables-updated",
        handleThemeVariablesUpdated,
      );
    };
  }, []);

  const isDark = theme === "dark" || theme === "high-contrast";
  const config = getConfig();

  useEffect(() => {
    if (config.themeOverrides) {
      setThemeUpdateKey((prev) => prev + 1);
    }
    if (config.cssVariables) {
      reapplyCssVariables();
    }
  }, [config.themeOverrides, config.cssVariables]);

  const antdConfig = getAntdThemeConfig(isDark, config.themeOverrides);

  return (
    <ConfigProvider theme={antdConfig}>
      <AntdApp>
        <IconProvider>
          <Layout className={styles.layout}>
            <EventNotification>
              <Modals>
                <Header className={styles.header}>
                  <Navigation
                    showThemeSwitcher
                    currentTheme={theme}
                    onThemeChange={(newTheme) => {
                      setTheme(newTheme);
                    }}
                  />
                </Header>
                <Content className={styles.content}>
                  <RouterProvider router={router} />
                </Content>
              </Modals>
            </EventNotification>
          </Layout>
        </IconProvider>
      </AntdApp>
    </ConfigProvider>
  );
};
export default App;
