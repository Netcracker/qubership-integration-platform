interface ImportMetaEnv {
  readonly VITE_GATEWAY: string;
  readonly VITE_API_APP: string;
  readonly VITE_SHOW_DEV_TOOLS: string;
  readonly VITE_AI_SERVICE_URL?: string;
  readonly VITE_AI_ASSISTANT_NAME?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
