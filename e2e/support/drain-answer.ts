/** A sample of a chain route during a redeploy, and whether it is the #845 drain answer. */

export interface RouteAnswer {
  status: number;
  marker: string | null;
  body: string;
  /** The request body the sample sent, as the engine received it. */
  sent: string;
}

/**
 * Whether a sample is the engine's answer for a route that is not serving right now.
 *
 * During a redeploy's drain window the engine answers in one of two shapes (#845). A suspended
 * consumer's 503 reaches the caller as `500 CIP-0001`, because `CustomErrorController` maps every
 * chain-URI error status except 404 and 405 to that code; a chain that threw gets the same
 * document, so the code is read out of the body rather than any 500 accepted. An exchange the
 * stopping context rejects before the chain runs gets the request echoed back with a 500 and no
 * marker, which `HandlingHttpBinding.doWriteExceptionResponse` writes.
 */
export function isDrainAnswer(answer: RouteAnswer): boolean {
  if (answer.status !== 500) return false;
  if (answer.marker === null && answer.body === answer.sent) return true;
  try {
    return (JSON.parse(answer.body) as { code?: string }).code === "CIP-0001";
  } catch {
    return false;
  }
}
