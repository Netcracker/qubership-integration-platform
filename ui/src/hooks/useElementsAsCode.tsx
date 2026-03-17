import { ChainElementCodeResponse } from "../api/apiTypes.ts";
import { api } from "../api/api.ts";
import { useNotificationService } from "./useNotificationService.tsx";
import { useCallback, useEffect, useState } from "react";

export const useElementsAsCode = (chainId: string) => {
    const [elementAsCode, setElementAsCode] = useState<ChainElementCodeResponse>();
    const notificationService = useNotificationService();

    const getElementsAsCode= useCallback(async () => {
        try {
            const elementAsCode = await api.getElementsAsCode(chainId);
            setElementAsCode(elementAsCode);
        } catch (error) {
            notificationService.requestFailed(
                "Failed to get elements as code",
                error,
            );
        }
    }, [chainId, notificationService]);

    useEffect(() => {
        if (chainId) {
            void getElementsAsCode();
        }
    }, [chainId, getElementsAsCode]);

    return {
        elementAsCode,
        refresh: getElementsAsCode,
    };
};
