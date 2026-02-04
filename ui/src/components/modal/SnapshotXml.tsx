import { Modal } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import React, { useCallback, useEffect, useRef, useState } from "react";
import { Snapshot } from "../../api/apiTypes.ts";
import { api } from "../../api/api.ts";
import { Editor, Monaco } from "@monaco-editor/react";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import {
  useMonacoTheme,
  applyVSCodeThemeToMonaco,
} from "../../hooks/useMonacoTheme";

type SnapshotXmlViewProps = {
  snapshotId: string;
};

export const SnapshotXmlView: React.FC<SnapshotXmlViewProps> = ({
  snapshotId,
}) => {
  const { closeContainingModal } = useModalContext();
  const [isLoading, setIsLoading] = useState(false);
  const [snapshot, setSnapshot] = useState<Snapshot>();
  const notificationService = useNotificationService();
  const monacoTheme = useMonacoTheme();
  const monacoRef = useRef<Monaco | null>(null);

  const getSnapshot = useCallback(
    async (snapshotId: string) => {
      setIsLoading(true);
      try {
        setSnapshot(await api.getSnapshot(snapshotId));
      } catch (err) {
        notificationService.requestFailed("Failed to get snapshot", err);
      } finally {
        setIsLoading(false);
      }
    },
    [notificationService],
  );

  useEffect(() => {
    void getSnapshot(snapshotId);
  }, [getSnapshot, snapshotId]);

  useEffect(() => {
    if (monacoRef.current) {
      applyVSCodeThemeToMonaco(monacoRef.current);
    }
  }, [monacoTheme]);

  return (
    <Modal
      title="XML Definition"
      centered
      open={true}
      onCancel={closeContainingModal}
      footer={null}
      width={"90%"}
      loading={isLoading}
    >
      <Editor
        className="qip-editor"
        height={"80vh"}
        defaultLanguage="xml"
        defaultValue={snapshot?.xmlDefinition}
        theme={monacoTheme}
        options={{ readOnly: true, fixedOverflowWidgets: true }}
        onMount={(_editor, monaco) => {
          monacoRef.current = monaco;
          applyVSCodeThemeToMonaco(monaco);
        }}
      />
    </Modal>
  );
};
