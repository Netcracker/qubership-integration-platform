import { UserPermissions } from "./types.ts";
import React, {
  PropsWithChildren,
  ReactNode,
  useEffect,
  useState,
} from "react";
import { UserPermissionsContext } from "./UserPermissionsContext.tsx";
import { getConfig, onConfigChange } from "../appConfig.ts";
import { getPermissions } from "./funcs.ts";

export const UserPermissionsProvider: React.FC<PropsWithChildren> = ({
  children,
}): ReactNode => {
  const [permissions, setPermissions] = useState<UserPermissions>({});

  useEffect(() => {
    const updatePermissions = (cfg: ReturnType<typeof getConfig>) => {
      setPermissions(getPermissions(cfg));
    };
    updatePermissions(getConfig());
    return onConfigChange((config) => updatePermissions(config));
  }, []);

  return (
    <UserPermissionsContext.Provider value={permissions}>
      {children}
    </UserPermissionsContext.Provider>
  );
};
