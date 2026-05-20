import {
  Operations,
  RequiredPermissions,
  ResourceType,
  ResourceTypes,
  UserPermissions,
} from "./types.ts";
import { AppConfig } from "../appConfig.ts";

export function getAllPermissions(): UserPermissions {
  const permissions: UserPermissions = {};
  ResourceTypes.forEach((type) => {
    permissions[type] = [...Operations];
  });
  return permissions;
}

export function getPermissions(appConfig: AppConfig): UserPermissions {
  return appConfig.permissions ?? getAllPermissions();
}

export function hasPermissions(
  provided: UserPermissions,
  required: RequiredPermissions,
): boolean {
  const options = "anyOf" in required ? required.anyOf : [required];
  return options.some((option) =>
    Object.entries(option).every(([resource, operations]) => {
      const permittedOperations = provided[resource as ResourceType] ?? [];
      return operations.every((operation) =>
        permittedOperations.includes(operation),
      );
    }),
  );
}
