import {
  AccessControl as AccessControlData,
  AccessControlProperty,
  AccessControlUpdateRequest,
} from "../../../api/apiTypes.ts";

/** The roles each element ends up with: the selected ones removed, or merged in. */
export const buildUpdateRequests = (
  records: AccessControlData[],
  selectedRoles: string[],
  isDeleteMode: boolean,
): AccessControlUpdateRequest[] =>
  records.map((rec) => {
    if (!rec.elementId) {
      throw new Error("Element ID is required");
    }

    const props = rec.properties as unknown as
      | AccessControlProperty
      | undefined;
    const existingRoles = Array.isArray(props?.roles) ? props.roles : [];

    return {
      elementId: rec.elementId,
      roles: isDeleteMode
        ? existingRoles.filter((role: string) => !selectedRoles.includes(role))
        : Array.from(new Set([...existingRoles, ...selectedRoles])),
    };
  });

/** One entry per chain, however many of its elements were edited. */
export const chainIdsOf = (records: AccessControlData[]): string[] =>
  Array.from(new Set(records.map((rec) => rec.chainId)));
