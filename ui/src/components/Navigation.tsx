import { Menu } from "antd";
import styles from "./Navigation.module.css";
import type { MenuProps } from "antd";
import { DesktopOutlined, UnorderedListOutlined, AppstoreOutlined } from "@ant-design/icons";
import { NotificationBar } from "./notifications/NotificationBar.tsx";

type MenuItem = Required<MenuProps>["items"][number];

const items: MenuItem[] = [
  {
    label: <a href="/chains">Chains</a>,
    key: "chains",
    icon: <UnorderedListOutlined />,
  },
  {
    label: <a href="/services">Services</a>,
    key: "services",
    icon: <AppstoreOutlined />,
  },
  {
    label: <a href="/admintools">Admin Tools</a>,
    key: "admintools",
    icon: <DesktopOutlined />,
  },
];

const Navigation = () => (
  <nav className={styles.navigation}>
    <Menu
      style={{ border: "none" }}
      items={items}
      key="menu"
      mode="horizontal"
      className={styles.menu}
    ></Menu>
    <NotificationBar />
  </nav>
);

export default Navigation;
