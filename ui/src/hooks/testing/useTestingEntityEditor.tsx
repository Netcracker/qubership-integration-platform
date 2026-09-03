import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { BreadcrumbProps } from "antd";
import {
  BlockerFunction,
  useBlocker,
  useLocation,
  useNavigate,
} from "react-router";
import { UnsavedChangesModal } from "../../components/modal/UnsavedChangesModal.tsx";
import { getTestingPermissions } from "../../components/testing/testingPermissions.ts";
import { introducesViolation } from "../../components/testing/violations.ts";
import { useModalsContext } from "../../Modals.tsx";
import { useRegisterChainHeaderActions } from "../../pages/ChainHeaderActionsContext.tsx";
import { ProtectedButton } from "../../permissions/ProtectedButton.tsx";
import { useNotificationService } from "../useNotificationService.tsx";
import { RowLink } from "../../components/table/RowLink.tsx";

export type TestingEntityTab = { key: string; label: string };

/** Both spellings the editor shows, given rather than derived from one noun. */
export type TestingEntityNouns = {
  /** Lowercase, for the failure notifications and the Save tooltip: "test case". */
  singular: string;
  /** The breadcrumb link back to the list: "Test Cases". */
  listTitle: string;
};

/**
 * What one entity kind contributes to the editor. Declare it outside the
 * component. The load effect depends on `get` and `violations`, so a config
 * rebuilt per render would re-read the entity in a loop.
 */
export type TestingEntityEditorConfig<T, R> = {
  /** Last segment of the list route, under `/chains/:chainId/testing` or `/admintools/testing`. */
  listSegment: string;
  tabs: TestingEntityTab[];
  nouns: TestingEntityNouns;
  saveTestId: string;
  get: (id: string) => Promise<T>;
  update: (id: string, request: R) => Promise<T>;
  toRequest: (entity: T) => R;
  violations: (entity: T | null) => string[];
  /** What the draft itself must carry; the hook checks the stored violations. */
  isValid: (entity: T) => boolean;
};

export type UseTestingEntityEditorOptions<T, R> = TestingEntityEditorConfig<
  T,
  R
> & {
  /** Set when the editor was reached inside a chain; absent in the admin scope. */
  chainId?: string;
  entityId?: string;
};

export type TestingEntityEditor<T> = {
  entity: T | null;
  loading: boolean;
  readonly: boolean;
  onChange: (changes: Partial<T>) => void;
  activeTab: string;
  onTabChange: (key: string) => void;
  breadcrumbItems: BreadcrumbProps["items"];
};

function getActiveTab(pathname: string, tabs: TestingEntityTab[]): string {
  // `.findLast(Boolean)` would read better, but it is ES2023 and this project's lib is ES2021.
  const segment = pathname.split("/").filter(Boolean).pop();
  return tabs.some((tab) => tab.key === segment) ? segment! : tabs[0].key;
}

/**
 * The choreography every testing entity editor repeats: it reads the entity the
 * route names, holds the draft, guards a navigation that would drop it, and
 * registers the header Save button. A page keeps only what belongs to its own
 * entity — how a draft becomes a request, and what makes one valid.
 */
export function useTestingEntityEditor<T extends { name: string }, R>({
  chainId,
  entityId,
  listSegment,
  tabs,
  nouns,
  saveTestId,
  get,
  update,
  toRequest,
  violations,
  isValid,
}: UseTestingEntityEditorOptions<T, R>): TestingEntityEditor<T> {
  const navigate = useNavigate();
  const location = useLocation();
  const notificationService = useNotificationService();
  const { showModal } = useModalsContext();
  const { singular, listTitle } = nouns;

  // An editor without a chain context cannot resolve the element to edit, so it reads only.
  const readonly = !chainId;
  const permissions = useMemo(() => getTestingPermissions(chainId), [chainId]);

  const [entity, setEntity] = useState<T | null>(null);
  // Values the entity carried when it was read; the service lets an update keep them.
  const [storedViolations, setStoredViolations] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [hasChanges, setHasChanges] = useState(false);
  // The blocker reads the draft state at navigation time, which lets a save clear
  // the flag and leave in the same tick without prompting for its own navigation.
  const hasChangesRef = useRef(false);
  const promptedForBlockRef = useRef(false);

  const listPath = chainId
    ? `/chains/${chainId}/testing/${listSegment}`
    : `/admintools/testing/${listSegment}`;

  useEffect(() => {
    if (!entityId) {
      return;
    }
    let cancelled = false;
    // The editor holds the entity the route names and no other: the draft goes
    // before the next one is read, so a read that fails cannot leave the entity
    // before it on screen, or save it under the id now in the address.
    setEntity(null);
    setStoredViolations([]);
    hasChangesRef.current = false;
    setHasChanges(false);
    setLoading(true);
    void (async () => {
      try {
        const loaded = await get(entityId);
        if (!cancelled) {
          setEntity(loaded);
          setStoredViolations(violations(loaded));
        }
      } catch (error) {
        if (!cancelled) {
          notificationService.requestFailed(
            `Failed to load the ${singular}`,
            error,
          );
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [entityId, get, violations, singular, notificationService]);

  const handleChange = useCallback((changes: Partial<T>) => {
    setEntity((current) => (current ? { ...current, ...changes } : current));
    hasChangesRef.current = true;
    setHasChanges(true);
  }, []);

  // The rules are checked against the entity as it was read, so a value the
  // service already tolerates keeps the save open and a value broken here shuts
  // it.
  const valid = useMemo(
    () =>
      !!entity &&
      isValid(entity) &&
      !introducesViolation(violations(entity), storedViolations),
    [entity, isValid, violations, storedViolations],
  );

  const save = useCallback(async () => {
    if (!entity || !entityId) {
      return;
    }
    setSaving(true);
    try {
      const saved = await update(entityId, toRequest(entity));
      setEntity(saved);
      setStoredViolations(violations(saved));
      hasChangesRef.current = false;
      setHasChanges(false);
    } catch (error) {
      notificationService.requestFailed(
        `Failed to save the ${singular}`,
        error,
      );
      // Rethrown so the unsaved-changes prompt keeps the navigation blocked.
      throw error;
    } finally {
      setSaving(false);
    }
  }, [
    entity,
    entityId,
    update,
    toRequest,
    violations,
    singular,
    notificationService,
  ]);

  // Saving keeps the editor open, the way Apply does on the chain's other tabs.
  // Leaving is the breadcrumb's job, and the blocker below still guards it.
  const handleSave = useCallback(() => {
    void save().catch(() => undefined);
  }, [save]);

  // Sub-tabs are routes of this editor, so only a navigation that leaves it prompts.
  const editorPath = `${listPath}/${entityId ?? ""}`;
  const blocker = useBlocker(
    useCallback<BlockerFunction>(
      ({ nextLocation }) =>
        !readonly &&
        hasChangesRef.current &&
        !nextLocation.pathname.startsWith(editorPath),
      [readonly, editorPath],
    ),
  );

  useEffect(() => {
    if (blocker.state !== "blocked") {
      promptedForBlockRef.current = false;
      return;
    }
    if (promptedForBlockRef.current) {
      return;
    }
    promptedForBlockRef.current = true;
    showModal({
      component: (
        <UnsavedChangesModal
          onYes={() => {
            void save()
              .then(() => blocker.proceed?.())
              .catch(() => blocker.reset?.());
          }}
          onNo={() => {
            hasChangesRef.current = false;
            setHasChanges(false);
            blocker.proceed?.();
          }}
          onCancelQuestion={() => blocker.reset?.()}
        />
      ),
    });
  }, [blocker, save, showModal]);

  // Registered before the page's own guards, since a hook cannot sit behind a
  // return, and withheld until there is an entity to save so the button cannot
  // linger over a screen that failed to load one. The dependencies name the
  // state the button reads: the hook holds the node by reference and re-reads it
  // on these alone.
  const hasEntity = !!entity;
  useRegisterChainHeaderActions(
    readonly || !hasEntity ? undefined : (
      <ProtectedButton
        require={permissions.write}
        tooltipProps={{ title: `Save the ${singular}` }}
        buttonProps={{
          "data-testid": saveTestId,
          type: "primary",
          children: "Save",
          loading: saving,
          disabled: !hasChanges || !valid,
          onClick: handleSave,
        }}
      />
    ),
    [
      readonly,
      hasEntity,
      permissions,
      saving,
      hasChanges,
      valid,
      handleSave,
      singular,
      saveTestId,
    ],
  );

  const handleTabChange = useCallback(
    (key: string) => void navigate(`${editorPath}/${key}`),
    [navigate, editorPath],
  );

  return {
    entity,
    loading,
    readonly,
    onChange: handleChange,
    activeTab: getActiveTab(location.pathname, tabs),
    onTabChange: handleTabChange,
    breadcrumbItems: [
      { title: <RowLink to={listPath}>{listTitle}</RowLink> },
      { title: entity?.name || entityId },
    ],
  };
}
