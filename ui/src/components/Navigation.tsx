import { Menu } from "antd";
import styles from "./Navigation.module.css";
import type { MenuProps } from "antd";
import { DesktopOutlined, UnorderedListOutlined } from "@ant-design/icons";

type MenuItem = Required<MenuProps>["items"][number];

const items: MenuItem[] = [
  {
    label: <a href="/chains">Chains</a>,
    key: "chains",
    icon: <UnorderedListOutlined />,
  },
  {
    label: <a href="/admin">Admin</a>,
    key: "admin",
    icon: <DesktopOutlined />,
  },
];

const Navigation = () => (
  <nav className={styles.navigation}>
    <Menu
      items={items}
      key="menu"
      mode="horizontal"
      className={styles.menu}
    ></Menu>
  </nav>
);

export default Navigation;
