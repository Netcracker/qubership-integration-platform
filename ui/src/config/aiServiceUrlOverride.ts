let override: string | null = null;

export function setAiServiceUrlOverride(url: string | undefined): void {
  override = url ?? null;
}

export function getAiServiceUrlOverride(): string | null {
  return override;
}
