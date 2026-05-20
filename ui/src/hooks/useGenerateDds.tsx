import { DdsPreview } from "../components/modal/DdsPreview";
import { GenerateDdsModal } from "../components/modal/GenerateDdsModal";
import { useModalsContext } from "../Modals";

export const useGenerateDds = () => {
  const { showModal } = useModalsContext();

  const showGenerateDdsModal = (chainId: string) => {
    showModal({
      component: (
        <GenerateDdsModal
          onSubmit={(templateId, fileName) => {
            showDdsModal(chainId, templateId, fileName);
          }}
        />
      ),
    });

    const showDdsModal = (
      chainId: string,
      templateId: string,
      fileName: string,
    ) => {
      showModal({
        component: (
          <DdsPreview
            chainId={chainId}
            templateId={templateId}
            fileName={fileName}
          />
        ),
      });
    };
  };

  return { showGenerateDdsModal };
};
