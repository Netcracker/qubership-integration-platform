import { useMemo } from "react";
import { Route, createMemoryRouter, createRoutesFromElements, RouterProvider } from "react-router";
import ChainPage from "./pages/ChainPage.tsx";
import { ConfigProvider, Layout } from "antd";
import styles from "./App.module.css";
import { Modals } from "./Modals.tsx";
import { ChainGraph } from "./pages/ChainGraph.tsx";
import { Content } from "antd/es/layout/layout";
import { ChainProperties } from "./pages/ChainProperties.tsx";
import { EventNotification } from "./components/notifications/EventNotification.tsx";
import DefaultExtensionPage from "./pages/DefaultExtensionPage.tsx";
import { NotImplemented } from "./pages/NotImplemented.tsx";
import { Masking } from "./pages/Masking.tsx";
import { STARTUP_EVENT, VSCodeExtensionApi } from "./api/rest/vscodeExtensionApi.ts";
import { api } from "./api/api.ts";

import { ServiceParametersPage } from "./components/services/ServiceParametersPage.tsx";
import { IconProvider } from "./IconProvider.tsx";
import { getIcons } from "./appConfig.ts";
import { useVSCodeTheme } from "./hooks/useVSCodeTheme.ts";
import { getAntdThemeConfig } from "./theme/antdTokens.ts";

const router = createMemoryRouter(
  createRoutesFromElements(
    <>
      <Route path="/" element={<DefaultExtensionPage />} />
      <Route path="/chains/:chainId" element={<ChainPage />}>
        <Route index element={<ChainGraph />} />
        <Route index path="graph" element={<ChainGraph />} />
        <Route path="graph/:elementId" element={<ChainGraph />} />
        <Route path="masking" element={<Masking />} />
        <Route path="properties" element={<ChainProperties />} />
      </Route>
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
      <Route path="*" element={<NotImplemented />} />
    </>,
  ),
);

const AppExtension = () => {
  const { isDark, palette } = useVSCodeTheme();
  const antdConfig = useMemo(() => getAntdThemeConfig(isDark), [isDark, palette]);

  if (api instanceof VSCodeExtensionApi) {
    void api.sendMessageToExtension(STARTUP_EVENT);
  }

  return (
    <ConfigProvider theme={antdConfig}>
      <IconProvider icons={getIcons()}>
        <Layout className={styles.layout}>
          <EventNotification>
            <Modals>
              <Content className={styles.content}>
                <RouterProvider router={router}/>
              </Content>
            </Modals>
          </EventNotification>
        </Layout>
      </IconProvider>
    </ConfigProvider>
  );
};

export default AppExtension;
