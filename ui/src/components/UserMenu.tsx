import { Button, Divider, Dropdown, Flex, Typography } from "antd";
import React, { useEffect, useState } from "react";
import styles from "./UserMenu.module.css";
import { getConfig, onConfigChange, UserInfo } from "../appConfig.ts";
import { OverridableIcon } from "../icons/IconProvider.tsx";
import { confirmAndRun } from "../misc/confirm-utils.ts";

const { Text } = Typography;

type UserMenuState = {
  userInfo: UserInfo;
  onLogout?: () => void;
};

function readState(): UserMenuState {
  const cfg = getConfig();
  return { userInfo: cfg.userInfo ?? {}, onLogout: cfg.onLogout };
}

function isSameState(a: UserMenuState, b: UserMenuState): boolean {
  return (
    a.onLogout === b.onLogout &&
    a.userInfo.userName === b.userInfo.userName &&
    a.userInfo.email === b.userInfo.email &&
    a.userInfo.tenantName === b.userInfo.tenantName &&
    a.userInfo.tenantId === b.userInfo.tenantId
  );
}

export const UserMenu: React.FC = () => {
  const [state, setState] = useState<UserMenuState>(readState);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    return onConfigChange(() => {
      setState((prev) => {
        const next = readState();
        return isSameState(prev, next) ? prev : next;
      });
    });
  }, []);

  const { userInfo, onLogout } = state;
  const displayName = userInfo.userName?.trim() || "Dev user";
  const tenantName = userInfo.tenantName?.trim();
  const tenantId = userInfo.tenantId?.trim();

  const handleResetPreferences = () => {
    setOpen(false);
    confirmAndRun({
      title: "Reset UI preferences?",
      content:
        "This will clear all locally stored UI preferences for this host and reload the page.",
      onOk: () => {
        localStorage.clear();
        window.location.reload();
      },
    });
  };

  const handleLogout = () => {
    setOpen(false);
    onLogout?.();
  };

  const card = (
    <Flex vertical className={styles.card} role="menu">
      <Flex vertical gap={2} className={styles.header}>
        <Text strong ellipsis={{ tooltip: displayName }}>
          {displayName}
        </Text>
        {userInfo.email && (
          <Text type="secondary" ellipsis={{ tooltip: userInfo.email }}>
            {userInfo.email}
          </Text>
        )}
      </Flex>

      {(tenantName || tenantId) && (
        <>
          <Divider className={styles.divider} />
          <Flex vertical gap={10} className={styles.info}>
            {tenantName && (
              <Flex vertical gap={2}>
                <Text type="secondary" className={styles.label}>
                  Tenant name
                </Text>
                <Text ellipsis={{ tooltip: tenantName }}>{tenantName}</Text>
              </Flex>
            )}
            {tenantId && (
              <Flex vertical gap={2}>
                <Text type="secondary" className={styles.label}>
                  Tenant ID
                </Text>
                <Text copyable className={styles.mono}>
                  {tenantId}
                </Text>
              </Flex>
            )}
          </Flex>
        </>
      )}

      <Divider className={styles.divider} />
      <Flex vertical className={styles.actions}>
        <Button
          type="text"
          block
          className={styles.actionButton}
          icon={<OverridableIcon name="resetPreferences" />}
          onClick={handleResetPreferences}
        >
          Reset UI preferences
        </Button>
        {onLogout && (
          <Button
            type="text"
            danger
            block
            className={styles.actionButton}
            icon={<OverridableIcon name="logout" />}
            onClick={handleLogout}
          >
            Log out
          </Button>
        )}
      </Flex>
    </Flex>
  );

  return (
    <Dropdown
      trigger={["click"]}
      open={open}
      onOpenChange={setOpen}
      placement="bottomRight"
      popupRender={() => card}
    >
      <Button
        type="text"
        aria-label="User menu"
        title={displayName}
        icon={<OverridableIcon name="user" />}
      />
    </Dropdown>
  );
};

export default UserMenu;
