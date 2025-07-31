import { PatchElementRequest, Element } from "../api/apiTypes.ts";
import { api } from "../api/api.ts";
import { useNotificationService } from "./useNotificationService.tsx";

export const useElement = () => {
  const notificationService = useNotificationService();

  const updateElement = async (
    chainId: string,
    elementId: string,
    request: PatchElementRequest,
  ): Promise<Element | undefined> => {
    try {
      const elementChange = await api.updateElement(
        request,
        chainId,
        elementId,
      );
      return elementChange.updatedElements?.[0];
    } catch (error) {
      notificationService.errorWithDetails(
        "Save element failed",
        "Failed to save element",
        error,
      );
    }
    return undefined;
  };

  return { updateElement };
};
