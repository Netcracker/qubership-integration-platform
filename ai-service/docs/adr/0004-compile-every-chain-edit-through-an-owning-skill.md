# Compile every chain edit through an owning skill, and keep the fast path as a smaller subgraph

ADR 0003 decided to route an edit by what it needs to resolve, and left one exception open: a
change that only rewrites properties of elements the chain already has stayed on the one-shot path,
where a general-purpose model authored the whole `GraphPatch` in a single tool call. That exception
does not survive contact with the catalog.

Pointing a `service-call` at another operation looks exactly like a property rewrite. It is not
one. The catalog stores an operation as nine properties that must together describe one real
operation, and it refuses the element when they disagree. The patch agent wrote
`integrationOperationId` and left `integrationOperationMethod` and `integrationOperationPath`
describing the operation before it. No prompt wording fixes that, because the agent has no way to
know what a complete binding is; the skill that owns service calls does.

The same shape showed up elsewhere. Appending an element produced a connection pointing the wrong
way. Adding a branch produced node patches with no `operation`. Each was answered with another
correction rule, and the rules accumulated: infer a missing operation, merge a re-sent patch,
expand a replaced connection, order parents before children, list the catalog's element types in
the request. Every one of them exists because a model was asked to encode mechanics the platform
already knows.

Decided: `ChainEditCompiler` is the only entry point for an existing-chain edit, and the
model-authored patch path is removed.

An edit is a compilation whose starting graph is the imported chain. The reader's words resolve
into a typed intent — an action and the elements it acts on — and nothing else. Element property
keys, catalog identities, and topology belong to the compiler skills that own them, which read the
element schemas and the knowledge package. What comes back is diffed against the imported graph, so
the reader still approves one change rather than a replay of the generators' working.

ADR 0003's central rule stands: an edit is routed by what it needs to resolve. What changes is the
fast path. A simple edit is now a smaller compiler subgraph — one owning generator, the assembler,
and the mandatory validators, with CREATE's discovery, naming and structure generation cut away —
rather than a separate general-purpose model. It stays fast for the same reason it was fast before:
it runs one model call over a graph that already exists. It stops being a second way to write
catalog properties.

Three edits do not go through a skill at all, because no skill is needed. Deleting an element,
cutting a connection and reordering branches are mechanics the catalog defines exactly: deletion
cascades to descendants and dependencies, and branch priority is renumbered from the ordinary
`priority` property. These run as deterministic Java transforms. The model is still needed for
which element the reader meant, and that is settled before the transform runs.

What the reader sees is unchanged: one decision card, the base-digest recheck before the write, the
same bounded writer and materializers, the same compensating rollback from ADR 0002, and the same
explicit report when a deleted element cannot come back. The interactive scenario and the
regression harness now share both seams — the compiler and the proposal assembler — so a change the
harness passes cannot fail differently in front of a reader.

Two things fail closed rather than degrading. A compiler package that changes while an edit is
compiling produces no proposal, because a proposal pinned to content the runtime no longer has
cannot be reproduced. An operation that exists only in APIHub stops and asks, because importing a
specification creates catalog artifacts nobody requested; the resolved intent is held so that
approving the import continues the edit the reader saw rather than re-reading a sentence the
conversation has moved past.

The cost is honest and worth naming. Every edit now spends one model call resolving intent before
anything else happens, and an edit kind no skill owns is refused rather than attempted. Refusing is
the point: a failure now names the contract that is missing, instead of arriving as another
correction rule on a prompt.

This supersedes ADR 0003's direct model-authored property fast path. The rest of ADR 0003 — routing
by resolution need, clarifications growing with underspecification while approvals grow with
irreversibility, and visible escalation — is unchanged and still in force.
