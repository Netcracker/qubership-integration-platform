const CSS_VARIABLES_STYLE_ID = "qip-ui-injected-css-variables";
const loadedCssFiles = new Set<string>();

export function injectCssVariables(variables: Record<string, string>): void {
  let styleElement = document.getElementById(CSS_VARIABLES_STYLE_ID) as HTMLStyleElement;

  if (!styleElement) {
    styleElement = document.createElement("style");
    styleElement.id = CSS_VARIABLES_STYLE_ID;
    document.head.appendChild(styleElement);
  }

  const cssRules = Object.entries(variables)
    .map(([key, value]) => `  ${key}: ${value};`)
    .join("\n");

  styleElement.textContent = `:root {\n${cssRules}\n}`;
}

export function loadCssFile(url: string): Promise<void> {
  return new Promise((resolve, reject) => {
    if (loadedCssFiles.has(url)) {
      resolve();
      return;
    }

    const link = document.createElement("link");
    link.rel = "stylesheet";
    link.type = "text/css";
    link.href = url;

    link.onload = () => {
      loadedCssFiles.add(url);
      resolve();
    };

    link.onerror = () => {
      reject(new Error(`Failed to load CSS file: ${url}`));
    };

    document.head.appendChild(link);
  });
}

export function loadCssFiles(urls: string[]): Promise<void> {
  return Promise.all(urls.map(url => loadCssFile(url).catch(error => {
    console.warn(`Failed to load CSS file ${url}:`, error);
  }))).then(() => undefined);
}
