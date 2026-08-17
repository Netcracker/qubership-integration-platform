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
import { EndpointMock, EndpointMockRequest } from "../../api/apiTypes.ts";
import { UnsavedChangesModal } from "../../components/modal/UnsavedChangesModal.tsx";
import { TableToolbar } from "../../components/table/TableToolbar.tsx";
import { matchersAreValid } from "../../components/testing/matchers.ts";
import { getTestingPermissions } from "../../components/testing/testingPermissions.ts";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import {
  isHttpFieldName,
  isHttpFieldValue,
} from "../../misc/http-field-utils.ts";
import { useModalsContext } from "../../Modals.tsx";
import { ProtectedButton } from "../../permissions/ProtectedButton.tsx";

const TABS = [
  { key: "general", label: "General" },
  { key: "response", label: "Response" },
  { key: "request-matchers", label: "Request Matchers" },
];

/** State the routed sub-tabs share with the editor that owns the draft. */
export type EndpointMockEditorContext = {
  endpointMock: EndpointMock;
  /** Set when the editor was reached inside a chain; absent in the admin scope. */
  chainId?: string;
  readonly: boolean;
  onChange: (changes: Partial<EndpointMock>) => void;
};

export function useEndpointMockEditor(): EndpointMockEditorContext {
  return useOutletContext<EndpointMockEditorContext>();
}

function getActiveTab(pathname: string): string {
  const segment = pathname.split("/").filter(Boolean).pop();
  return TABS.some((tab) => tab.key === segment) ? segment! : TABS[0].key;
}

function toRequest(endpointMock: EndpointMock): EndpointMockRequest {
  return {
    name: endpointMock.name.trim(),
    description: endpointMock.description,
    enabled: endpointMock.enabled,
    endpointReference: endpointMock.endpointReference,
    responseSettings: endpointMock.responseSettings,
    requestMatchers: endpointMock.requestMatchers,
  };
}

export const EndpointMockPage: React.FC = () => {
  const { chainId, mockId } = useParams<{
    chainId?: string;
    mockId: string;
  }>();
  const navigate = useNavigate();
  const location = useLocation();
  const notificationService = useNotificationService();
  const { showModal } = useModalsContext();

  // An editor without a chain context cannot resolve an endpoint to edit, so it reads only.
  const readonly = !chainId;
  const permissions = useMemo(() => getTestingPermissions(chainId), [chainId]);

  const [endpointMock, setEndpointMock] = useState<EndpointMock | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [hasChanges, setHasChanges] = useState(false);
  // The blocker reads the draft state at navigation time, which lets a save clear
  // the flag and leave in the same tick without prompting for its own navigation.
  const hasChangesRef = useRef(false);
  const promptedForBlockRef = useRef(false);

  const listPath = chainId
    ? `/chains/${chainId}/testing/endpoint-mocks`
    : "/admintools/testing/endpoint-mocks";

  useEffect(() => {
    if (!mockId) {
      return;
    }
    let cancelled = false;
    setLoading(true);
    void (async () => {
      try {
        const loaded = await api.getEndpointMock(mockId);
        if (!cancelled) {
          setEndpointMock(loaded);
        }
      } catch (error) {
        if (!cancelled) {
          notificationService.requestFailed(
            "Failed to load the endpoint mock",
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
  }, [mockId, notificationService]);

  const handleChange = useCallback((changes: Partial<EndpointMock>) => {
    setEndpointMock((current) =>
      current ? { ...current, ...changes } : current,
    );
    hasChangesRef.current = true;
    setHasChanges(true);
  }, []);

  // No method here: a mock answers whatever the endpoint is called with. The
  // headers are checked because the service refuses a response it cannot write.
  const isValid = useMemo(
    () =>
      !!endpointMock &&
      endpointMock.name.trim().length > 0 &&
      !!endpointMock.endpointReference?.chainId &&
      !!endpointMock.endpointReference?.elementId &&
      (endpointMock.responseSettings?.message?.headers ?? []).every(
        (header) =>
          isHttpFieldName(header.name) && isHttpFieldValue(header.value),
      ) &&
      matchersAreValid(endpointMock.requestMatchers),
    [endpointMock],
  );

  const save = useCallback(async () => {
    if (!endpointMock || !mockId) {
      return;
    }
    setSaving(true);
    try {
      const saved = await api.updateEndpointMock(
        mockId,
        toRequest(endpointMock),
      );
      setEndpointMock(saved);
      hasChangesRef.current = false;
      setHasChanges(false);
    } catch (error) {
      notificationService.requestFailed(
        "Failed to save the endpoint mock",
        error,
      );
      // Rethrown so the unsaved-changes prompt keeps the navigation blocked.
      throw error;
    } finally {
      setSaving(false);
    }
  }, [endpointMock, mockId, notificationService]);

  const handleSave = useCallback(() => {
    void save()
      .then(() => navigate(listPath))
      .catch(() => undefined);
  }, [save, navigate, listPath]);

  const handleCancel = useCallback(() => {
    void navigate(listPath);
  }, [navigate, listPath]);

  // Sub-tabs are routes of this editor, so only a navigation that leaves it prompts.
  const editorPath = `${listPath}/${mockId ?? ""}`;
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

  const editorContext = useMemo<EndpointMockEditorContext | null>(
    () =>
      endpointMock
        ? { endpointMock, chainId, readonly, onChange: handleChange }
        : null,
    [endpointMock, chainId, readonly, handleChange],
  );

  if (loading) {
    return <Skeleton active />;
  }
  if (!endpointMock || !editorContext) {
    return <Empty description="Endpoint mock not found" />;
  }

  return (
    <Flex vertical gap={8} style={{ flex: 1, minWidth: 0 }}>
      <Breadcrumb
        items={[
          {
            title: (
              <a onClick={() => void navigate(listPath)}>Endpoint Mocks</a>
            ),
          },
          { title: endpointMock.name || mockId },
        ]}
      />
      <Tabs
        activeKey={getActiveTab(location.pathname)}
        items={TABS}
        onChange={(key) => void navigate(`${listPath}/${mockId ?? ""}/${key}`)}
      />
      <Outlet context={editorContext} />
      {readonly ? null : (
        <TableToolbar
          data-testid="endpoint-mock-editor-toolbar"
          actions={
            <>
              <ProtectedButton
                require={permissions.write}
                tooltipProps={{ title: "Discard the changes" }}
                buttonProps={{
                  "data-testid": "endpoint-mock-cancel",
                  children: "Cancel",
                  onClick: handleCancel,
                }}
              />
              <ProtectedButton
                require={permissions.write}
                tooltipProps={{ title: "Save the endpoint mock" }}
                buttonProps={{
                  "data-testid": "endpoint-mock-save",
                  type: "primary",
                  children: "Save",
                  loading: saving,
                  disabled: !hasChanges || !isValid,
                  onClick: handleSave,
                }}
              />
            </>
          }
        />
      )}
    </Flex>
  );
};

export default EndpointMockPage;
