import { Route, Routes } from "react-router";
import ChainPage from "./pages/ChainPage.tsx";
import { Layout } from "antd";

import styles from "./App.module.css";
import { Modals } from "./Modals.tsx";
import { ChainGraph } from "./pages/ChainGraph.tsx";
import { Content } from "antd/es/layout/layout";
import { ChainProperties } from "./pages/ChainProperties.tsx";
import { EventNotification } from "./components/notifications/EventNotification.tsx";
import { MemoryRouter } from "react-router-dom";
import DefaultExtensionPage from "./pages/DefaultExtensionPage.tsx";
import { NotImplemented } from "./pages/NotImplemented.tsx";
import { Masking } from "./pages/Masking.tsx";
import { AppProps, setAppName } from "./appConfig.ts";
import { STARTUP_EVENT, VSCodeExtensionApi } from "./api/rest/vscodeExtensionApi.ts";
import { api } from "./api/api.ts";


const AppExtension = ({ appName = undefined }: AppProps) => {
  setAppName(appName);
  if (api instanceof VSCodeExtensionApi) {
    void api.sendMessageToExtension(STARTUP_EVENT);
  }

  return (
    <Layout className={styles.layout}>
      <EventNotification>
        <Modals>
          <Content className={styles.content}>
            <MemoryRouter>
              <Routes>
                <Route path="/" element={<DefaultExtensionPage />} />
                <Route path="/chains/:chainId" element={<ChainPage />}>
                  <Route index element={<ChainGraph />} />
                  <Route index path="graph" element={<ChainGraph />} />
                  <Route path="graph/:elementId" element={<ChainGraph />} />
                  <Route path="masking" element={<Masking />} />
                  <Route path="properties" element={<ChainProperties />} />
                </Route>
                <Route path="*" element={<NotImplemented />} />
              </Routes>
            </MemoryRouter>
          </Content>
        </Modals>
      </EventNotification>
    </Layout>
  );
};

export default AppExtension;
