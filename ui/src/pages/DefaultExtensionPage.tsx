import { useNavigate } from "react-router";
import {
  NAVIGATE_EVENT,
  STARTUP_EVENT,
  VSCodeExtensionApi,
  VSCodeResponse,
} from "../api/rest/vscodeExtensionApi.ts";
import React, { useContext, useEffect } from "react";
import { Result } from "antd";
import { AppExtensionProps, configureAppExtension } from "../appConfig.ts";
import { api } from "../api/api.ts";
import { IconContext } from "../icons/IconProvider.tsx";

const DefaultExtensionPage: React.FC = () => {
  const navigate = useNavigate();
  const { setIcons } = useContext(IconContext);

  useEffect(() => {
    const onMessage = (
      event: MessageEvent<VSCodeResponse<AppExtensionProps | string>>,
    ) => {
      const { type, payload } = event.data;

      if (type === STARTUP_EVENT && payload && typeof payload === "object") {
        const startupPayload = payload;

        void configureAppExtension(startupPayload).catch((error) => {
          console.warn("Failed to configure app extension:", error);
        });

        if (startupPayload.icons) {
          setIcons(startupPayload.icons);
        }

        if (api instanceof VSCodeExtensionApi) {
          void api.sendMessageToExtension(NAVIGATE_EVENT);
        }

        return;
      }

      if (type === NAVIGATE_EVENT && typeof payload === "string") {
        void navigate(payload);
      }
    };

    window.addEventListener("message", onMessage);
    return () => {
      window.removeEventListener("message", onMessage);
    };
  }, [navigate, setIcons]);

  return <Result title="Loading" subTitle="The extension is loading" />;
};

export default DefaultExtensionPage;
