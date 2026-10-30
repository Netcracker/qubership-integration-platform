import { useNavigate } from "react-router";
import {
  NAVIGATE_EVENT,
  STARTUP_EVENT,
  VSCodeExtensionApi,
  VSCodeResponse,
} from "../api/rest/vscodeExtensionApi.ts";
import React, { useContext, useEffect, useState } from "react";
import { Result } from "antd";
import { AppExtensionProps, configureAppExtension } from "../appConfig.ts";
import { api } from "../api/api.ts";
import { IconContext } from "../icons/IconProvider.tsx";
import { getErrorMessage } from "../misc/error-utils.ts";

const DefaultExtensionPage: React.FC = () => {
  const navigate = useNavigate();
  const { setIcons } = useContext(IconContext);
  const [navigateError, setNavigateError] = useState<string>();

  useEffect(() => {
    const onMessage = (
      event: MessageEvent<VSCodeResponse<AppExtensionProps>>,
    ) => {
      // VS Code's webview host forwards extension messages from the webview's own origin.
      if (event.origin !== window.origin) {
        return;
      }
      const { type, payload } = event.data;

      if (type === STARTUP_EVENT && payload && typeof payload === "object") {
        const startupPayload = payload;

        void configureAppExtension(startupPayload).catch((error) => {
          console.warn("Failed to configure app extension:", error);
        });

        if (startupPayload.icons) {
          setIcons(startupPayload.icons);
        }

        if (
          startupPayload.operation === "exportImages" &&
          startupPayload.exportImages
        ) {
          void navigate("/batch-export", {
            state: startupPayload.exportImages,
          });
          return;
        }

        if (api instanceof VSCodeExtensionApi) {
          api.sendMessageToExtension(NAVIGATE_EVENT).catch((error) => {
            setNavigateError(getErrorMessage(error));
          });
        }
      }
    };

    window.addEventListener("message", onMessage);
    return () => {
      window.removeEventListener("message", onMessage);
    };
  }, [navigate, setIcons]);

  if (navigateError) {
    return (
      <Result
        status="error"
        title="Cannot open the file"
        subTitle={navigateError}
      />
    );
  }

  return <Result title="Loading" subTitle="The extension is loading" />;
};

export default DefaultExtensionPage;
