import { useNavigate } from "react-router";
import { NAVIGATE_EVENT, STARTUP_EVENT, VSCodeExtensionApi, VSCodeResponse } from "../api/rest/vscodeExtensionApi.ts";
import React from "react";
import { Result } from "antd";
import { configureAppExtension } from "../appConfig.ts";
import { api } from "../api/api.ts";

const DefaultExtensionPage: React.FC = () => {
  const navigate = useNavigate();
  // Listener for messages from extension to navigate to the start (chain_ page
  window.addEventListener("message", (event: MessageEvent<VSCodeResponse<never>>) => {
    const message: VSCodeResponse<never> = event.data;
    const { type, payload } = message;
    if (type == STARTUP_EVENT && payload) {
      console.log('Received startup message from vscode extension', message)
      configureAppExtension(payload);

      if (api instanceof VSCodeExtensionApi) {
        void api.sendMessageToExtension(NAVIGATE_EVENT);
      }
    }
    if (type == NAVIGATE_EVENT && payload) {
      console.log('Received navigate message from vscode extension', message)
      void navigate(payload);
    }
  });
  return (<Result
    title="Loading"
    subTitle="The extension is loading"
  />);
};

export default DefaultExtensionPage;
