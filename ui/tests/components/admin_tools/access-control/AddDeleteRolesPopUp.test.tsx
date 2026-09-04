/**
 * @jest-environment jsdom
 */

import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  AddDeleteRolesPopUp,
  buildUpdateRequests,
  chainIdsOf,
} from "../../../../src/components/admin_tools/access-control/AddDeleteRolesPopUp";
import { useNotificationService } from "../../../../src/hooks/useNotificationService";
import { useModalContext } from "../../../../src/ModalContextProvider";
import {
  AccessControl as AccessControlData,
  AccessControlType,
} from "../../../../src/api/apiTypes";

jest.mock("../../../../src/hooks/useNotificationService");
jest.mock("../../../../src/ModalContextProvider");

const mockUseNotificationService =
  useNotificationService as jest.MockedFunction<typeof useNotificationService>;
const mockUseModalContext = useModalContext as jest.MockedFunction<
  typeof useModalContext
>;

/**
 * `properties` carries the element's own property map — ChainRolesMapper maps it straight from
 * ChainElement.properties — which is why every consumer casts through AccessControlProperty rather
 * than the Record the type declares.
 */
const properties = (
  roles: string[] | undefined,
): AccessControlData["properties"] =>
  ({
    contextPath: "/test",
    externalRoute: true,
    privateRoute: false,
    accessControlType: AccessControlType.RBAC,
    ...(roles === undefined ? {} : { roles }),
  }) as unknown as AccessControlData["properties"];

const record = (
  overrides: Partial<AccessControlData> = {},
): AccessControlData => ({
  elementId: "elem-1",
  elementName: "Element Name",
  chainId: "chain-1",
  chainName: "Test Chain",
  deploymentStatus: ["DEPLOYED"],
  unsavedChanges: false,
  modifiedWhen: 1700000000000,
  properties: properties(["admin"]),
  ...overrides,
});

describe("buildUpdateRequests", () => {
  it("merges the selected roles into the roles an element already has", () => {
    const requests = buildUpdateRequests(
      [record()],
      ["reader", "admin"],
      false,
    );

    expect(requests).toStrictEqual([
      { elementId: "elem-1", roles: ["admin", "reader"] },
    ]);
  });

  it("removes the selected roles in delete mode", () => {
    const requests = buildUpdateRequests(
      [record({ properties: properties(["admin", "reader"]) })],
      ["admin"],
      true,
    );

    expect(requests).toStrictEqual([
      { elementId: "elem-1", roles: ["reader"] },
    ]);
  });

  it("treats an element with no roles as having none", () => {
    const requests = buildUpdateRequests(
      [record({ properties: properties(undefined) })],
      ["reader"],
      false,
    );

    expect(requests).toStrictEqual([
      { elementId: "elem-1", roles: ["reader"] },
    ]);
  });

  it("refuses an element without an id", () => {
    expect(() =>
      buildUpdateRequests([record({ elementId: "" })], ["reader"], false),
    ).toThrow("Element ID is required");
  });
});

describe("chainIdsOf", () => {
  it("names each chain once, however many of its elements were edited", () => {
    const ids = chainIdsOf([
      record({ elementId: "elem-1", chainId: "chain-1" }),
      record({ elementId: "elem-2", chainId: "chain-1" }),
      record({ elementId: "elem-3", chainId: "chain-2" }),
    ]);

    expect(ids).toStrictEqual(["chain-1", "chain-2"]);
  });

  it("drops a record that carries no chain id", () => {
    const ids = chainIdsOf([
      record({ chainId: "chain-1" }),
      record({ elementId: "elem-2", chainId: undefined as unknown as string }),
    ]);

    expect(ids).toStrictEqual(["chain-1"]);
  });
});

describe("AddDeleteRolesPopUp", () => {
  const updateAccessControl = jest.fn().mockResolvedValue(undefined);
  const bulkDeployAccessControl = jest.fn().mockResolvedValue(undefined);
  const info = jest.fn();
  const requestFailed = jest.fn();
  const closeContainingModal = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
    updateAccessControl.mockResolvedValue(undefined);
    bulkDeployAccessControl.mockResolvedValue(undefined);

    mockUseNotificationService.mockReturnValue({
      info,
      requestFailed,
    } as unknown as ReturnType<typeof useNotificationService>);
    mockUseModalContext.mockReturnValue({ closeContainingModal });
  });

  const callbacks = { updateAccessControl, bulkDeployAccessControl };

  const submit = () =>
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

  const tickRedeploy = () => fireEvent.click(screen.getByRole("checkbox"));

  it("saves the roles and does not deploy when redeploy is left unticked", async () => {
    render(<AddDeleteRolesPopUp records={[record()]} {...callbacks} />);

    submit();

    await waitFor(() => {
      expect(updateAccessControl).toHaveBeenCalledTimes(1);
    });
    expect(bulkDeployAccessControl).not.toHaveBeenCalled();
    expect(info).toHaveBeenCalledWith("Success", "Roles updated successfully");
  });

  it("deploys the edited chains once each when redeploy is ticked", async () => {
    render(
      <AddDeleteRolesPopUp
        records={[
          record({ elementId: "elem-1", chainId: "chain-1" }),
          record({ elementId: "elem-2", chainId: "chain-1" }),
          record({ elementId: "elem-3", chainId: "chain-2" }),
        ]}
        {...callbacks}
      />,
    );

    tickRedeploy();
    submit();

    await waitFor(() => {
      expect(bulkDeployAccessControl).toHaveBeenCalledTimes(1);
    });
    expect(bulkDeployAccessControl.mock.calls[0][0]).toStrictEqual([
      "chain-1",
      "chain-2",
    ]);
    expect(info).toHaveBeenCalledWith("Success", "Roles updated successfully");
  });

  it("says the roles were saved when the deploy fails, and still closes", async () => {
    const failure = new Error("engine unreachable");
    bulkDeployAccessControl.mockRejectedValueOnce(failure);
    const onSuccess = jest.fn();

    render(
      <AddDeleteRolesPopUp
        records={[record()]}
        onSuccess={onSuccess}
        {...callbacks}
      />,
    );

    tickRedeploy();
    submit();

    await waitFor(() => {
      expect(requestFailed).toHaveBeenCalledWith(
        "Roles updated, but some chains were not deployed",
        failure,
      );
    });
    expect(info).not.toHaveBeenCalledWith(
      "Success",
      "Roles updated successfully",
    );
    // The roles are saved, so the caller is told and the dialog goes away.
    expect(onSuccess).toHaveBeenCalledTimes(1);
    expect(closeContainingModal).toHaveBeenCalledTimes(1);
  });

  it("reports a failed role update without attempting a deploy", async () => {
    const failure = new Error("catalog rejected the batch");
    updateAccessControl.mockRejectedValueOnce(failure);

    render(<AddDeleteRolesPopUp records={[record()]} {...callbacks} />);

    tickRedeploy();
    submit();

    await waitFor(() => {
      expect(requestFailed).toHaveBeenCalledWith(
        "Failed to update roles",
        failure,
      );
    });
    expect(bulkDeployAccessControl).not.toHaveBeenCalled();
    expect(closeContainingModal).not.toHaveBeenCalled();
  });

  it("refuses to delete when no role is selected", async () => {
    render(
      <AddDeleteRolesPopUp records={[record()]} mode="delete" {...callbacks} />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => {
      expect(info).toHaveBeenCalledWith(
        "Error",
        "Please select at least one role to delete",
      );
    });
    expect(updateAccessControl).not.toHaveBeenCalled();
  });
});
