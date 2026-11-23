/** The chain a spec is exercising, as much of it as the spec knows. */
export interface ChainUnderTest {
  id: string;
  name: string;
  contextPath?: string;
}

/** A session a lookup found, and the token it was found by. */
export interface FoundSession {
  id: string;
  chainId?: string;
  token?: string;
}

export interface Diagnostic {
  startedAt: Date;
  chains: ChainUnderTest[];
  sessions: FoundSession[];
  /** Tokens a lookup asked for. A token here and no session is the interesting case. */
  tokens: string[];
}

function fresh(): Diagnostic {
  return { startedAt: new Date(), chains: [], sessions: [], tokens: [] };
}

export let current: Diagnostic = fresh();

/** Starts a fresh record. Called by the `diagnostics` fixture before each test. */
export function beginDiagnostics(): void {
  current = fresh();
}

/** Notes the chain a spec addressed. Called from `seedChain()`, and by hand for a built chain. */
export function noteChain(chain: ChainUnderTest): void {
  if (!current.chains.some((each) => each.id === chain.id)) current.chains.push(chain);
}

/** Notes a token a lookup asked for, whether or not a session ever answered it. */
export function noteSessionLookup(token: string): void {
  if (!current.tokens.includes(token)) current.tokens.push(token);
}

/** Notes a session a lookup found. */
export function noteSession(session: FoundSession): void {
  if (!current.sessions.some((each) => each.id === session.id)) current.sessions.push(session);
}
