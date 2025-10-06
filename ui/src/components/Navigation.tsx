import { Menu } from "antd";
import styles from "./Navigation.module.css";
import type { MenuProps } from "antd";
import { NotificationBar } from "./notifications/NotificationBar.tsx";
import { Icon } from "../IconProvider.tsx";

type MenuItem = Required<MenuProps>["items"][number];

const items: MenuItem[] = [
  {
    label: <a href="/chains">Chains</a>,
    key: "chains",
    icon: <Icon name="unorderedList" />,
  },
  {
    label: <a href="/services">Services</a>,
    key: "services",
    icon: <Icon name="appstore" />,
  },
  {
    label: <a href="/admintools">Admin Tools</a>,
    key: "admintools",
    icon: <Icon name="desktop" />,
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
