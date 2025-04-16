interface ImportMetaEnv {
  readonly VITE_GATEWAY: string;
  readonly VITE_APP: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}