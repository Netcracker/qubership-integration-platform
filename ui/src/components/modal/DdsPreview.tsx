import React, { useCallback, useEffect, useState } from "react";
import { ChainDetailedDesign } from "../../api/apiTypes.ts";
import { Button, Modal, Spin } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import { api } from "../../api/api.ts";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeRaw from "rehype-raw";
import { downloadFile } from "../../misc/download-utils.ts";
import JSZip from "jszip";
import { exportAsMarkdown } from "../../mapper/markdown/markdown.ts";
import { MappingDescription } from "../../mapper/model/model.ts";
import { MermaidDiagram } from "@lightenna/react-mermaid-diagram";
import styles from "./DdsPreview.module.css";
import mermaid from "mermaid";

export type DdsViewProps = {
  chainId: string;
  templateId: string;
  fileName: string;
};

export const DdsPreview: React.FC<DdsViewProps> = ({
  chainId,
  templateId,
  fileName,
}) => {
  const { closeContainingModal } = useModalContext();
  const [isLoading, setIsLoading] = useState(false);
  const notificationService = useNotificationService();
  const [chainDetailedDesign, setChainDetailedDesign] = useState<
    ChainDetailedDesign | undefined
  >(undefined);

  const patchDesignWithMapping = useCallback(
    async (design: ChainDetailedDesign): Promise<ChainDetailedDesign> => {
      if (!chainId) {
        return design;
      }
      const elements = await api.getElements(chainId);
      const document = elements
        .filter((element) => element.type === "mapper-2")
        .map((element) => {
          const markdown = exportAsMarkdown(
            (element.properties as Record<string, unknown>)[
              "mappingDescription"
            ] as MappingDescription,
            { titleSize: "h3" },
          );
          return { id: element.id, markdown };
        })
        .reduce(
          (result, { id, markdown }) =>
            result.replace(`[//]:#(mapper-table-export-view-${id})`, markdown),
          design.document,
        );
      return { ...design, document };
    },
    [chainId],
  );

  const loadChainDetailedDesign = useCallback(async () => {
    if (!chainId || !templateId) {
      return;
    }
    setIsLoading(true);
    try {
      const design = await api
        .getChainDetailedDesign(chainId, templateId)
        .then(patchDesignWithMapping);
      setChainDetailedDesign(design);
    } catch (error) {
      notificationService.requestFailed(
        "Failed to get chain detailed design",
        error,
      );
    } finally {
      setIsLoading(false);
    }
  }, [chainId, notificationService, patchDesignWithMapping, templateId]);

  const buildDdsFile = useCallback(async (): Promise<File | undefined> => {
    if (!chainDetailedDesign || !fileName) {
      return;
    }
    const zip = new JSZip();
    zip.file("doc.md", chainDetailedDesign.document);

    if (chainDetailedDesign.simpleSeqDiagramMermaid) {
      const { svg } = await mermaid.render(
        "sequence-diagram",
        chainDetailedDesign.simpleSeqDiagramMermaid,
      );
      const imgEntry = zip.folder("img")!;
      imgEntry.file("simple-sequence-diagram-mermaid.svg", svg);
    }

    const srcEntry = zip.folder("src")!;
    const diagramsEntry = srcEntry.folder("diagrams")!;
    if (chainDetailedDesign.simpleSeqDiagramMermaid) {
      diagramsEntry.file(
        "simple-sequence-diagram.mmd",
        chainDetailedDesign.simpleSeqDiagramMermaid,
      );
    }
    if (chainDetailedDesign.simpleSeqDiagramPlantuml) {
      diagramsEntry.file(
        "simple-sequence-diagram.puml",
        chainDetailedDesign.simpleSeqDiagramPlantuml,
      );
    }
    const specEntry = srcEntry.folder("specifications")!;
    if (chainDetailedDesign.triggerSpecifications) {
      for (const spec of chainDetailedDesign.triggerSpecifications) {
        const filename =
          spec.serviceName.replace(" ", "_") +
          "_" +
          spec.specificationName.replace(" ", "_") +
          "_" +
          spec.specificationId +
          "." +
          spec.fileExtension;
        specEntry.file(filename, spec.specificationContent);
      }
    }

    const blob = await zip.generateAsync({ type: "blob" });
    return new File([blob], fileName, {
      type: "application/zip",
    });
  }, [chainDetailedDesign, fileName]);

  useEffect(() => {
    void loadChainDetailedDesign();
  }, [loadChainDetailedDesign]);

  return (
    <Modal
      title="Document Preview"
      open={true}
      onCancel={closeContainingModal}
      width={"80vw"}
      footer={[
        <Button key="cancel" onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          htmlType={"submit"}
          disabled={isLoading || !chainDetailedDesign}
          onClick={() => {
            void buildDdsFile().then((file) => {
              if (!file) {
                return;
              }
              downloadFile(file);
              closeContainingModal();
            });
          }}
        >
          Download
        </Button>,
      ]}
    >
      <div className={styles["dds-content"]}>
        {isLoading ? (
          <Spin className={styles.loader} size={"large"}></Spin>
        ) : (
          <Markdown
            rehypePlugins={[rehypeRaw]}
            remarkPlugins={[remarkGfm]}
            components={{
              img(props) {
                // eslint-disable-next-line react/prop-types
                return props.src ===
                  "./img/simple-sequence-diagram-mermaid.svg" ? (
                  <MermaidDiagram>
                    {chainDetailedDesign?.simpleSeqDiagramMermaid ?? ""}
                  </MermaidDiagram>
                ) : (
                  <img {...props} alt="image" />
                );
              },
            }}
          >
            {chainDetailedDesign?.document}
          </Markdown>
        )}
      </div>
    </Modal>
  );
};
