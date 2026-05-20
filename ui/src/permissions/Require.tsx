import { RequiredPermissions } from "./types.ts";
import React, { PropsWithChildren, ReactNode } from "react";
import { usePermissions } from "./usePermissions.tsx";
import { hasPermissions } from "./funcs.ts";

type RequireProps = PropsWithChildren & {
  permissions: RequiredPermissions;
  fallback?: ReactNode;
};

export const Require: React.FC<RequireProps> = ({
  permissions,
  fallback,
  children,
}): ReactNode => {
  const providedPermissions = usePermissions();

  return hasPermissions(providedPermissions, permissions) ? (
    <>{children}</>
  ) : (
    (fallback ?? null)
  );
};
