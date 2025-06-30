import { useState } from "react";
import { Divider, Layout } from "antd";
import { useLocation, Outlet } from "react-router-dom";
import { AnimatePresence, motion } from "framer-motion";
import { DoubleLeftOutlined, DoubleRightOutlined } from "@ant-design/icons";

import styles from "./AdminToolsPage.module.css";
import { AdminToolsSidebar } from "../../components/admin_tools/AdminToolsSidebar";

const { Sider, Content } = Layout;

export const AdminTools = () => {
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(false);

  return (
    <Layout className={styles.container}>
      <Sider
        width={240}
        collapsedWidth={80}
        collapsible
        collapsed={collapsed}
        trigger={null}
        className={styles.sidebar}
      >
        <div className={styles.sidebarInner}>
          <div className={styles.sidebarMenu}>
            <AdminToolsSidebar collapsed={collapsed} />
          </div>
          <div style={{ padding: "0px 24px" }}>
            <Divider style={{ margin: 0 }} />
          </div>
          <div
            className={styles.sidebarToggleBar}
            onClick={() => setCollapsed(!collapsed)}
          >
            {collapsed ? <DoubleRightOutlined /> : <DoubleLeftOutlined />}
          </div>
        </div>
      </Sider>
      <Divider
        type="vertical"
        size="small"
        style={{ height: "100%", margin: 0 }}
      />
      <Content className={styles.contentArea}>
        <AnimatePresence mode="wait" initial={false}>
          <motion.div
            key={location.pathname}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.25 }}
            className={styles.moduleContainer}
          >
            <Outlet />
          </motion.div>
        </AnimatePresence>
      </Content>
    </Layout>
  );
};
