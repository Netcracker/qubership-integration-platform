import { Element, PatchElementRequest } from "../api/apiTypes.ts";
import { api } from "../api/api.ts";
import { notification } from "antd";

export const useElement = () => {

  const updateElement = async (chainId: string, elementId: string, request: PatchElementRequest): Promise<Element | undefined> => {
    try {
      const elementChange = await api.updateElement(
        request,
        chainId,
        elementId,
      );
      return elementChange.updatedElements?.[0]!;
    } catch (error) {
      notification.error({
        message: "Save element failed",
        description: "Failed to save element",
      });
    }
    return undefined;
  };

  return { updateElement };
};