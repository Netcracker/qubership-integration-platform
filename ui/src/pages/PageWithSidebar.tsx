import React, { useState } from "react";
import { Divider, Layout } from "antd";
import { AnimatePresence, motion } from "framer-motion";
import styles from "./PageWithSidebar.module.css";
import { OverridableIcon } from "../icons/IconProvider.tsx";

const { Sider, Content } = Layout;

interface PageWithSidebarProps {
  sidebar: React.ReactNode;
  children: React.ReactNode;
  initialCollapsed?: boolean;
  sidebarWidth?: number;
  sidebarCollapsedWidth?: number;
  contentClassName?: string;
  showCollapseToggle?: boolean;
}

export const PageWithSidebar: React.FC<PageWithSidebarProps> = ({
  sidebar,
  children,
  initialCollapsed = false,
  sidebarWidth = 180,
  sidebarCollapsedWidth = 80,
  contentClassName,
  showCollapseToggle = true,
}) => {
  const [collapsed, setCollapsed] = useState(initialCollapsed);

  return (
    <Layout className={styles.container}>
      <Sider
        width={sidebarWidth}
        collapsedWidth={sidebarCollapsedWidth}
        collapsible
        collapsed={collapsed}
        trigger={null}
        className={styles.sidebar}
      >
        <div className={styles.sidebarInner}>
          <div className={styles.sidebarMenu}>
            {React.isValidElement(sidebar)
              ? React.cloneElement(sidebar, { collapsed })
              : sidebar}
          </div>
          {showCollapseToggle && (
            <>
              <div style={{ padding: "0px 24px" }}>
                <Divider style={{ margin: 0 }} />
              </div>
              <div
                className={styles.sidebarToggleBar}
                onClick={() => setCollapsed(!collapsed)}
              >
                {collapsed ? (
                  <OverridableIcon name="doubleRight" />
                ) : (
                  <OverridableIcon name="doubleLeft" />
                )}
              </div>
            </>
          )}
        </div>
      </Sider>
      {
        <Divider
          type="vertical"
          size="small"
          style={{ height: "100%", margin: 0 }}
        />
      }
      <Content
        className={[styles.contentArea, contentClassName]
          .filter(Boolean)
          .join(" ")}
      >
        <AnimatePresence mode="wait" initial={false}>
          <motion.div
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.25 }}
            className={styles.moduleContainer}
          >
            {children}
          </motion.div>
        </AnimatePresence>
      </Content>
    </Layout>
  );
};

export default PageWithSidebar;
