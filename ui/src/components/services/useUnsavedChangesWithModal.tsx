import { useRef, useState, useEffect } from "react";
import { useModalsContext } from "../../Modals";
import { useBlocker } from "react-router-dom";
import { UnsavedChangesModal } from "../modal/UnsavedChangesModal";

interface UseBlockerPromptParams {
  system: unknown;
  blockerCondition: boolean;
  onYes: () => Promise<void>;
}

export const useUnsavedChangesWithModal = ({
  system,
  blockerCondition,
  onYes,
}: UseBlockerPromptParams) => {
  const { showModal } = useModalsContext();
  const [hasChanges, setHasChanges] = useState<boolean>(false);
  const blocker = useBlocker(blockerCondition && hasChanges);
  /** One modal per router block; avoids re-opening when `system` updates after save (before `proceed`). */
  const unsavedPromptShownForBlockRef = useRef(false);

  useEffect(() => {
    if (blocker.state !== "blocked") {
      unsavedPromptShownForBlockRef.current = false;
      return;
    }

    if (!system || unsavedPromptShownForBlockRef.current) return;
    unsavedPromptShownForBlockRef.current = true;

    showModal({
      component: (
        <UnsavedChangesModal
          onYes={() => {
            void onYes()
              .then(() => blocker.proceed())
              .catch(() => {
                /* validation or save failed — keep blocker */
              });
          }}
          onNo={() => blocker.proceed()}
          onCancelQuestion={() => blocker.reset()}
        />
      ),
    });
  }, [blocker, system, showModal]);

  return { hasChanges, setHasChanges };
};
