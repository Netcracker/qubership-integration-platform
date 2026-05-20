import { useContext } from "react";
import { UserPermissionsContext } from "./UserPermissionsContext.tsx";

export const usePermissions = () => {
  const context = useContext(UserPermissionsContext);
  if (!context) {
    throw new Error(
      "usePermissions must be used within UserPermissionsProvider",
    );
  }
  return context;
};
