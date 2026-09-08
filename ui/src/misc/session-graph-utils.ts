import type { Session, SessionElement } from "../api/apiTypes.ts";

export function getGraphChainId(
  element: SessionElement | undefined,
  session: Session | undefined,
): string | undefined {
  if (!element) {
    return session?.chainId;
  }
  const camelName = element.camelName ?? "";
  const isChainCall = camelName === "chain-call" || camelName === "chain-call-2";
  if (isChainCall) {
    return session?.chainId;
  }
  return element.actualElementChainId ?? session?.chainId;
}

export function buildGraphUrl(
  element: SessionElement | undefined,
  session: Session | undefined,
): string {
  const chainId = getGraphChainId(element, session);
  return `/chains/${chainId}/graph/${element?.chainElementId}`;
}
