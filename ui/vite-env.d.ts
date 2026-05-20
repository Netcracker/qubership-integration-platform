interface ImportMetaEnv {
  readonly VITE_GATEWAY: string;
  readonly VITE_API_APP: string;
  readonly VITE_SHOW_DEV_TOOLS: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
