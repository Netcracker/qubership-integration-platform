import { Button, Menu } from "antd";
import { Link, useLocation, useNavigate } from "react-router-dom";
import styles from "./Navigation.module.css";
import { NotificationBar } from "./notifications/NotificationBar.tsx";
import { OverridableIcon } from "../icons/IconProvider.tsx";
import { useDocumentation } from "../hooks/useDocumentation.ts";
import { isVsCode } from "../api/rest/vscodeExtensionApi.ts";
import { usePermissions } from "../permissions/usePermissions.tsx";
import { useMemo } from "react";
import {
  ProtectedMenuItem,
  protectMenuItems,
} from "../permissions/ProtectedDropdown.tsx";
import { AiAssistant } from "./ai/AiAssistant.tsx";
import { useIsAiServiceAvailable } from "../ai/appConfig";
import { UserMenu } from "./UserMenu.tsx";

const Navigation = () => {
  const navigate = useNavigate();
  const { openContextDoc } = useDocumentation();
  const { pathname } = useLocation();
  const selectedKey = pathname.split("/")[1] || "chains";
  const permissions = usePermissions();

  const isAiServiceAvailable = useIsAiServiceAvailable();

  const items = useMemo(() => {
    const protectedItems: ProtectedMenuItem[] = [
      {
        label: <Link to="/chains">Chains</Link>,
        key: "chains",
        icon: <OverridableIcon name="chain" />,
        require: { chain: ["list"] },
      },
      {
        label: <Link to="/services">Services</Link>,
        key: "services",
        icon: <OverridableIcon name="appstore" />,
        require: { service: ["list"] },
      },
      {
        label: <Link to="/admintools">Admin Tools</Link>,
        key: "admintools",
        icon: <OverridableIcon name="tool" />,
        require: { adminTools: ["read"] },
      },
      {
        label: <Link to="/devtools">Dev Tools</Link>,
        key: "devtools",
        icon: <OverridableIcon name="bug" />,
        require: { devTools: ["read"] },
      },
    ];
    return protectMenuItems(protectedItems, permissions, "hide");
  }, [permissions]);

  return (
    <nav className={styles.navigation}>
      <div className={styles["nav-main"]}>
        <Button
          icon={<OverridableIcon name="logo" />}
          onClick={() => void navigate("/chains")}
          type="text"
          size="middle"
          title="Home"
          className={styles["home-button"]}
        />
        <Menu
          style={{ border: "none" }}
          items={items}
          key="menu"
          mode="horizontal"
          className={styles.menu}
          selectedKeys={[selectedKey]}
        />
      </div>
      <div className={styles["utilities"]}>
        {!isVsCode && (
          <>
            <div className="bg-switcher-selector"></div>
            <Button
              icon={<OverridableIcon name="questionCircle" />}
              onClick={openContextDoc}
              type="text"
              title="Help"
            />
          </>
        )}
        {isAiServiceAvailable && <AiAssistant />}
        <NotificationBar />
        <UserMenu />
      </div>
    </nav>
  );
};

export default Navigation;
