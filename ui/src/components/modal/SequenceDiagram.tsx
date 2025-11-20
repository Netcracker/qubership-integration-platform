import {
  Button,
  Dropdown,
  MenuProps,
  Modal,
  Space,
  Spin,
  Tabs,
  TabsProps,
} from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { MermaidDiagram } from "@lightenna/react-mermaid-diagram";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import {
  DiagramLangType,
  DiagramMode,
  ElementsSequenceDiagrams,
} from "../../api/apiTypes.ts";
import { downloadFile } from "../../misc/download-utils.ts";
import { MenuInfo } from "rc-menu/lib/interface";
import mermaid from "mermaid";
import styles from "./SequenceDiagram.module.css";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

type SequenceDiagramProps = {
  title?: string;
  fileNamePrefix?: string;
  entityId?: string;
  diagramProvider: () => Promise<ElementsSequenceDiagrams>;
};

function getFileNameExtension(format: string): string {
  switch (format) {
    case "svg":
      return "svg";
    case "MERMAID":
      return "mmd";
    case "PLANT_UML":
      return "puml";
    default:
      return "";
  }
}

export const SequenceDiagram: React.FC<SequenceDiagramProps> = ({
  title,
  fileNamePrefix,
  entityId,
  diagramProvider,
}) => {
  const { closeContainingModal } = useModalContext();
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [activeTab, setActiveTab] = useState<DiagramMode>(DiagramMode.FULL);
  const [activeDiagram, setActiveDiagram] = useState<string>("");
  const notificationService = useNotificationService();
  const [diagrams, setDiagrams] = useState<
    ElementsSequenceDiagrams | undefined
  >(undefined);

  const loadDiagram = useCallback(async (): Promise<void> => {
    setIsLoading(true);
    try {
      const diagrams = await diagramProvider();
      setDiagrams(diagrams);
    } catch (error) {
      notificationService.requestFailed(
        "Failed to get sequence diagram",
        error,
      );
    } finally {
      setIsLoading(false);
    }
  }, [diagramProvider, notificationService]);

  useEffect(() => {
    void loadDiagram();
  }, [loadDiagram]);

  useEffect(
    () =>
      setActiveDiagram(
        diagrams?.[activeTab].diagramSources?.[DiagramLangType.MERMAID] ?? "",
      ),
    [activeTab, diagrams],
  );

  const tabItems: TabsProps["items"] = useMemo(
    () => [
      {
        key: DiagramMode.FULL,
        label: "Full",
        children: <MermaidDiagram>{activeDiagram}</MermaidDiagram>,
      },
      {
        key: DiagramMode.SIMPLE,
        label: "Simple",
        children: <MermaidDiagram>{activeDiagram}</MermaidDiagram>,
      },
    ],
    [activeDiagram],
  );

  const items: MenuProps["items"] = [
    {
      label: "SVG",
      key: "svg",
    },
    {
      label: "Mermaid",
      key: DiagramLangType.MERMAID,
    },
    {
      label: "PlantUML",
      key: DiagramLangType.PLANT_UML,
    },
  ];

  const buildDiagramFile = useCallback(
    async (e: MenuInfo) => {
      const diagram =
        diagrams?.[activeTab].diagramSources[
          e.key === DiagramLangType.PLANT_UML.toString()
            ? DiagramLangType.PLANT_UML
            : DiagramLangType.MERMAID
        ] ?? "";
      const extension = getFileNameExtension(e.key);
      const fileName = `${fileNamePrefix}-sequence-${activeTab.toLowerCase()}-diagram__${entityId}.${extension}`;
      if (e.key === "svg") {
        const { svg } = await mermaid.render("sequence-diagram", diagram);
        const blob = new Blob([svg]);
        return new File([blob], fileName, { type: "image/svg+xml" });
      } else {
        const blob = new Blob([diagram]);
        return new File([blob], fileName, { type: "text/plain" });
      }
    },
    [activeTab, diagrams, entityId, fileNamePrefix],
  );

  const handleMenuClick: MenuProps["onClick"] = useCallback(
    (e: MenuInfo) => {
      if (!diagrams) {
        return;
      }
      void buildDiagramFile(e).then(downloadFile);
    },
    [buildDiagramFile, diagrams],
  );

  const menuProps: MenuProps = {
    items,
    onClick: handleMenuClick,
  };

  return (
    <Modal
      title={title ?? "Sequence Diagram"}
      centered
      open={true}
      onCancel={closeContainingModal}
      footer={
        <Dropdown menu={menuProps}>
          <Button type="primary">
            <Space>
              Export
              <OverridableIcon name="down" />
            </Space>
          </Button>
        </Dropdown>
      }
      width={"90%"}
    >
      <Tabs
        style={{ height: "80vh", resize: "none" }}
        className={"flex-tabs diagram-tabs"}
        items={tabItems}
        activeKey={activeTab}
        destroyOnHidden={false}
        onChange={(key) => setActiveTab(key as DiagramMode)}
      />
      {isLoading ? (
        <Spin className={styles.loader} size={"large"}></Spin>
      ) : (
        <></>
      )}
    </Modal>
  );
};
