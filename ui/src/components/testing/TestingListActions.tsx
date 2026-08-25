import React from "react";
import { ProtectedButton } from "../../permissions/ProtectedButton.tsx";
import { IconName } from "../../icons/IconProvider.tsx";
import { TestingPermissions } from "./testingPermissions.ts";

/** The buttons every testing list page picks from. A kind fixes the icon, the right and the wording. */
export type TestingListActionKind =
  | "refresh"
  | "run"
  | "restart"
  | "cancel"
  | "export"
  | "import"
  | "delete"
  | "create";

export type TestingListAction = {
  kind: TestingListActionKind;
  /** Optional for the same reason the button's own handler is: a bulk action a page did not configure. */
  onClick?: () => void;
  disabled?: boolean;
  loading?: boolean;
};

type ActionShape = {
  right: keyof TestingPermissions;
  iconName: IconName;
  title: (entityLabel: string, createLabel: string) => string;
};

const SHAPES: Record<TestingListActionKind, ActionShape> = {
  refresh: { right: "view", iconName: "refresh", title: () => "Refresh" },
  run: {
    right: "execute",
    iconName: "play",
    title: (entity) => `Run selected ${entity}`,
  },
  restart: {
    right: "execute",
    iconName: "play",
    title: (entity) => `Restart selected ${entity}`,
  },
  cancel: {
    right: "execute",
    iconName: "stop",
    title: (entity) => `Cancel selected ${entity}`,
  },
  export: {
    right: "export",
    iconName: "cloudDownload",
    title: (entity) => `Export selected ${entity}`,
  },
  import: {
    right: "import",
    iconName: "cloudUpload",
    title: (entity) => `Import ${entity}`,
  },
  delete: {
    right: "write",
    iconName: "delete",
    title: (entity) => `Delete selected ${entity}`,
  },
  create: {
    right: "write",
    iconName: "plus",
    title: (_entity, create) => `Create ${create}`,
  },
};

type TestingListActionsProps = {
  /** Prefixes each button's test id: "test-cases" gives "test-cases-export". */
  testIdPrefix: string;
  /** Plural and lowercase, so that it reads back as "Export selected test cases". */
  entityLabel: string;
  /** Carries its own article: "a test case" reads back as "Create a test case". */
  createLabel?: string;
  permissions: TestingPermissions;
  actions: TestingListAction[];
};

export const TestingListActions: React.FC<TestingListActionsProps> = ({
  testIdPrefix,
  entityLabel,
  createLabel = "",
  permissions,
  actions,
}) => (
  <>
    {actions.map(({ kind, onClick, disabled, loading }) => {
      const shape = SHAPES[kind];
      return (
        <ProtectedButton
          key={kind}
          require={permissions[shape.right]}
          tooltipProps={{
            title: shape.title(entityLabel, createLabel),
            // Refresh sits leftmost, where a tooltip above would cover the toolbar.
            ...(kind === "refresh" ? { placement: "bottom" as const } : {}),
          }}
          buttonProps={{
            "data-testid": `${testIdPrefix}-${kind}`,
            iconName: shape.iconName,
            disabled,
            loading,
            onClick,
            ...(kind === "create"
              ? { type: "primary" as const, children: "Create" }
              : {}),
          }}
        />
      );
    })}
  </>
);
