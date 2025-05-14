import Navigation from "./components/Navigation.tsx";
import { BrowserRouter, Route, Routes } from "react-router";
import Chains from "./pages/Chains";
import Admin from "./pages/Admin";
import Chain from "./pages/Chain";
import { Layout } from "antd";

import styles from "./App.module.css";
import { Modals } from "./Modals.tsx";
import { Snapshots } from "./pages/Snapshots.tsx";
import { Deployments } from "./pages/Deployments.tsx";
import ChainGraph from "./pages/ChainGraph.tsx";
import NotFound from "./pages/NotFound.tsx";
import { Content } from "antd/es/layout/layout";
import { LoggingSettings } from "./pages/LoggingSettings.tsx";
import { Sessions } from "./pages/Sessions.tsx";
import { NotImplemented } from "./pages/NotImplemented.tsx";

const { Header } = Layout;

const App = () => (
  <Layout className={styles.layout}>
    <Modals>
      <Header className={styles.header}>
        <Navigation />
      </Header>
      <Content className={styles.content}>
        <BrowserRouter>
          <Routes>
            <Route path="/admin" element={<Admin />} />
            <Route index path="/" element={<Chains />} />
            <Route index path="/chains" element={<Chains />} />
            <Route path="/chains/:chainId" element={<Chain />}>
              <Route index element={ <ChainGraph />} />
              <Route index path="graph" element={ <ChainGraph />} />
              <Route path="snapshots" element={<Snapshots />} />
              <Route path="deployments" element={<Deployments />} />
              <Route path="sessions" element={<Sessions />} />
              <Route path="sessions/:sessionId" element={<NotImplemented/>} />
              <Route path="logging-settings" element={ <LoggingSettings />} />
            </Route>
            <Route path="*" element={<NotFound />} />
          </Routes>
        </BrowserRouter>
      </Content>
    </Modals>
  </Layout>
);

export default App;
