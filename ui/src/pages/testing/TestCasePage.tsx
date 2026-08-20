import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Breadcrumb, Empty, Flex, Skeleton, Tabs } from "antd";
import {
  BlockerFunction,
  Outlet,
  useBlocker,
  useLocation,
  useNavigate,
  useOutletContext,
  useParams,
} from "react-router";
import { api } from "../../api/api.ts";
import { TestCase, TestCaseRequest } from "../../api/apiTypes.ts";
import { UnsavedChangesModal } from "../../components/modal/UnsavedChangesModal.tsx";
import { getTestingPermissions } from "../../components/testing/testingPermissions.ts";
import {
  introducesViolation,
  testCaseViolations,
} from "../../components/testing/violations.ts";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import { useModalsContext } from "../../Modals.tsx";
import { useRegisterChainHeaderActions } from "../ChainHeaderActionsContext.tsx";
import { ProtectedButton } from "../../permissions/ProtectedButton.tsx";

const TABS = [
  { key: "general", label: "General" },
  { key: "request", label: "Request Parameters" },
  { key: "response-validation", label: "Response Validation" },
];

/** State the routed sub-tabs share with the editor that owns the draft. */
export type TestCaseEditorContext = {
  testCase: TestCase;
  /** Set when the editor was reached inside a chain; absent in the admin scope. */
  chainId?: string;
  readonly: boolean;
  onChange: (changes: Partial<TestCase>) => void;
};

export function useTestCaseEditor(): TestCaseEditorContext {
  return useOutletContext<TestCaseEditorContext>();
}

function getActiveTab(pathname: string): string {
  const segment = pathname.split("/").filter(Boolean).pop();
  return TABS.some((tab) => tab.key === segment) ? segment! : TABS[0].key;
}

function toRequest(testCase: TestCase): TestCaseRequest {
  return {
    name: testCase.name.trim(),
    description: testCase.description,
    enabled: testCase.enabled,
    triggerReference: testCase.triggerReference,
    requestSettings: testCase.requestSettings,
    responseValidationRules: testCase.responseValidationRules,
  };
}

export const TestCasePage: React.FC = () => {
  const { chainId, testCaseId } = useParams<{
    chainId?: string;
    testCaseId: string;
  }>();
  const navigate = useNavigate();
  const location = useLocation();
  const notificationService = useNotificationService();
  const { showModal } = useModalsContext();

  // An editor without a chain context cannot resolve a trigger to edit, so it reads only.
  const readonly = !chainId;
  const permissions = useMemo(() => getTestingPermissions(chainId), [chainId]);

  const [testCase, setTestCase] = useState<TestCase | null>(null);
  // Values the case carried when it was read; the service lets an update keep them.
  const [storedViolations, setStoredViolations] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [hasChanges, setHasChanges] = useState(false);
  // The blocker reads the draft state at navigation time, which lets a save clear
  // the flag and leave in the same tick without prompting for its own navigation.
  const hasChangesRef = useRef(false);
  const promptedForBlockRef = useRef(false);

  const listPath = chainId
    ? `/chains/${chainId}/testing/test-cases`
    : "/admintools/testing/test-cases";

  useEffect(() => {
    if (!testCaseId) {
      return;
    }
    let cancelled = false;
    // The editor holds the case the route names and no other: the draft goes
    // before the next one is read, so a read that fails cannot leave the case
    // before it on screen, or save it under the id now in the address.
    setTestCase(null);
    setStoredViolations([]);
    hasChangesRef.current = false;
    setHasChanges(false);
    setLoading(true);
    void (async () => {
      try {
        const loaded = await api.getTestCase(testCaseId);
        if (!cancelled) {
          setTestCase(loaded);
          setStoredViolations(testCaseViolations(loaded));
        }
      } catch (error) {
        if (!cancelled) {
          notificationService.requestFailed(
            "Failed to load the test case",
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
  }, [testCaseId, notificationService]);

  const handleChange = useCallback((changes: Partial<TestCase>) => {
    setTestCase((current) => (current ? { ...current, ...changes } : current));
    hasChangesRef.current = true;
    setHasChanges(true);
  }, []);

  // The validation rules are checked against the case as it was read, so a rule
  // the service already tolerates keeps the save open and a rule broken here
  // shuts it.
  const isValid = useMemo(
    () =>
      !!testCase &&
      testCase.name.trim().length > 0 &&
      !!testCase.triggerReference?.chainId &&
      !!testCase.triggerReference?.elementId &&
      !!testCase.requestSettings?.method &&
      !introducesViolation(testCaseViolations(testCase), storedViolations),
    [testCase, storedViolations],
  );

  const save = useCallback(async () => {
    if (!testCase || !testCaseId) {
      return;
    }
    setSaving(true);
    try {
      const saved = await api.updateTestCase(testCaseId, toRequest(testCase));
      setTestCase(saved);
      setStoredViolations(testCaseViolations(saved));
      hasChangesRef.current = false;
      setHasChanges(false);
    } catch (error) {
      notificationService.requestFailed("Failed to save the test case", error);
      // Rethrown so the unsaved-changes prompt keeps the navigation blocked.
      throw error;
    } finally {
      setSaving(false);
    }
  }, [testCase, testCaseId, notificationService]);

  // Saving keeps the editor open, the way Apply does on the chain's other tabs.
  // Leaving is the breadcrumb's job, and the blocker below still guards it.
  const handleSave = useCallback(() => {
    void save().catch(() => undefined);
  }, [save]);

  // Sub-tabs are routes of this editor, so only a navigation that leaves it prompts.
  const editorPath = `${listPath}/${testCaseId ?? ""}`;
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

  const editorContext = useMemo<TestCaseEditorContext | null>(
    () =>
      testCase ? { testCase, chainId, readonly, onChange: handleChange } : null,
    [testCase, chainId, readonly, handleChange],
  );

  // Registered before the guards below, since a hook cannot sit behind a return,
  // and withheld until there is a case to save so the button cannot linger over
  // a screen that failed to load one. The dependencies name the state the button
  // reads: the hook holds the node by reference and re-reads it on these alone.
  const hasCase = !!testCase;
  useRegisterChainHeaderActions(
    readonly || !hasCase ? undefined : (
      <ProtectedButton
        require={permissions.write}
        tooltipProps={{ title: "Save the test case" }}
        buttonProps={{
          "data-testid": "test-case-save",
          type: "primary",
          children: "Save",
          loading: saving,
          disabled: !hasChanges || !isValid,
          onClick: handleSave,
        }}
      />
    ),
    [readonly, hasCase, permissions, saving, hasChanges, isValid, handleSave],
  );

  if (loading) {
    return <Skeleton active />;
  }
  if (!testCase || !editorContext) {
    return <Empty description="Test case not found" />;
  }

  return (
    <Flex vertical gap={8} style={{ flex: 1, minWidth: 0 }}>
      <Breadcrumb
        items={[
          {
            title: <a onClick={() => void navigate(listPath)}>Test Cases</a>,
          },
          { title: testCase.name || testCaseId },
        ]}
      />
      <Tabs
        activeKey={getActiveTab(location.pathname)}
        items={TABS}
        onChange={(key) =>
          void navigate(`${listPath}/${testCaseId ?? ""}/${key}`)
        }
      />
      <Outlet context={editorContext} />
    </Flex>
  );
};

export default TestCasePage;
