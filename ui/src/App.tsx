import Navigation from "./components/Navigation.tsx";
import { BrowserRouter, Route, Routes } from "react-router";
import Chains from "./pages/Chains";
import Admin from "./pages/Admin";
import Chain from "./pages/Chain";
import { Layout } from "antd";

import styles from "./App.module.css";
import { Modals } from "./Modals.tsx";

const { Header } = Layout;

const App = () => (
  <Layout className={styles.layout}>
    <Modals>
      <Header className={styles.header}>
        <Navigation />
      </Header>
      <BrowserRouter>
        <Routes>
          <Route path="/admin" element={<Admin />} />
          <Route index path="/" element={<Chains />} />
          <Route index path="/chains" element={<Chains />} />
          <Route path="/chains/:chainId" element={<Chain />} />
        </Routes>
      </BrowserRouter>
    </Modals>
  </Layout>
);

export default App;
