import { useNavigate } from "react-router";
import { STARTUP_EVENT, VSCodeResponse } from "../api/rest/vscodeExtensionApi.ts";
import React from "react";
import { Result } from "antd";

const DefaultExtensionPage: React.FC = () => {
  const navigate = useNavigate();
  // Listener for messages from extension to navigate to the start (chain_ page
  window.addEventListener("message", (event: MessageEvent<VSCodeResponse<never>>) => {
    const message: VSCodeResponse<never> = event.data;
    const { type, payload } = message;
    if (type == STARTUP_EVENT && payload) {
      console.log('Received startup message from vscode extension', message)
      void navigate(payload);
    }
  });
  return (<Result
    title="Loading"
    subTitle="The extension is loading"
  />);
};

export default DefaultExtensionPage;
