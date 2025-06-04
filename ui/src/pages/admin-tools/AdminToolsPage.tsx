import { useState } from "react";
import { Layout } from "antd";
import { useLocation, Outlet } from "react-router-dom";
import { AnimatePresence, motion } from "framer-motion";
import { LeftOutlined, RightOutlined } from "@ant-design/icons";

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
          <div className={styles.sidebarToggleBar} onClick={() => setCollapsed(!collapsed)}>
            {collapsed ? <RightOutlined /> : <LeftOutlined />}
          </div>
        </div>
      </Sider>

      <Layout className={styles.mainContentWrapper}>
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
    </Layout>
  );
};