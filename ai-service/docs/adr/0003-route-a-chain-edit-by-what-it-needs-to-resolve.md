# Route a chain edit by what it needs to resolve, not by the words it arrives in

`COMPARE_AND_PATCH` asks the model for the whole change in one tool call and applies what comes
back. That holds for the change it was built for: rewriting a property on an element the chain
already has. It does not hold as the change grows. Measured against `gpt-4o-mini` on a live chain:

| Change | Cards produced |
|---|---|
| Rewrite a property | works |
| Delete an element | works |
| Insert one element between two joined ones | 3 of 3 |
| Append one element after the last | 0 — the model reverses the new connection |
| Add a branch holding two elements | 0 of 9 — `operation` missing on the node patches |
| Anything naming an external system | impossible — see below |

The last row is not a model failure. Pointing a `service-call` at a different operation means
writing `integrationOperationProtocolType`, `integrationOperationId`, `integrationOperationMethod`
and `integrationOperationPath` so that together they describe one real operation from one real
specification. The patch agent holds two tools, `proposeChainPatch` and `createChainSnapshot`, and
no way to read the catalog at all -- while `CatalogRestClient` already exposes `searchSystems`,
`getApiSpecifications`, `getModel` and `/v1/operations`. Asked to change an operation, the model can
only invent, and it does: `url`, `method`, `serviceUrl`, none of which the schema defines.

CREATE has all of this. Its profile runs eight stages with typed artifacts, three approval gates and
a compiler pipeline, and its `design-execution` stage produces `catalog-binding-resolutions` and
`api-operation-bindings` before anything is written. The two paths already share the half that
writes: `ProductChainMaterializer` and `ChainPatchWriter` both go through the same skeleton,
properties and connections materializers, and the same `MaterializationMap`. What the patch path
skips is the half that decides *what* to write.

Decided: an edit is a build whose starting point is not empty, and it is routed by what the change
needs resolved rather than by the scenario name it arrived under.

A change that only rewrites properties of elements the chain already has stays on the one-shot path:
one model call, one card, seconds. This is the case that works today and the one a redesign is most
likely to break, so it keeps its own route rather than being folded into the general machine.

A change that adds elements needs generation that reads the element schemas, because a model writing
element configuration from memory produces types and property keys that do not exist. It does not
need requirement analysis: the request is already specific. It enters the shared machinery at
planning, with the imported chain as its starting artifact instead of an empty one.

A change that names something outside the chain -- a system, an operation, a specification -- needs
binding resolution whatever its size. Changing which operation a `service-call` points at looks like
a property edit and is not one. The IDS and design stages are dropped for an edit; binding
resolution is not.

Two things grow, and they grow for different reasons. **Clarifying questions** grow with how
underspecified the request is; they cost the reader a sentence and write nothing, so there may be
several. **Approvals** grow with how large and how irreversible the change is; they cost the reader
a decision, so a one-line script fix gets exactly one. Conflating the two is what would turn "change
the operation" into a ceremony.

Resolution is a loop, and it looks up before it asks. A reader who says "change the operation" and
names none cannot answer "which id?" -- they do not know the ids. The element already carries its
current operation, and therefore its system, which bounds the search: offer the one candidate that
fits, or list the few that do, or say plainly that there are none. Only then the card, which already
renders "now" beside "after", and only then the write.

Half of that loop exists. The patch skill already refuses to guess which *element* a description
means: it names the candidates and asks, calling no tool that turn, and tests pin the behaviour.
Extending the same shape from elements to operations changes where the candidates come from, not how
the turn behaves.

Escalation is visible. A reader who asked for something small and turns out to need the long route
is told so and can decline. Silently promoting a small request into a staged process is the failure
this whole path was built to avoid, and it has already been observed once, when a hint from the UI
sent "delete a step" into CREATE and answered it with a requirement-analysis approval card.

What is not yet known: how much of the measured failure is the dev model rather than the design.
`gpt-4o-mini` drops required fields as the patch grows, and a stronger model may not. Binding
resolution is not in that category -- no model can infer an operation catalogue it cannot read.
