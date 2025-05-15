interface ImportMetaEnv {
  readonly VITE_GATEWAY: string;
  readonly VITE_API_APP: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}