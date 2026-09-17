# Chain semantic design

Capture the chain topology from the approved requirement brief.

Submit one candidate with `captureChainSemanticRevision` per runtime attempt. If capture is rejected,
return the validator findings and finish the attempt. The runtime owns the bounded regeneration budget
and supplies those findings on the next attempt. A rejected topology does not by itself require changing
the approved brief. After accepted capture, finish without further tool calls.

Copy `sourceFactIds` and `mappingIntentId` from the approved brief. Do not mint occurrence ids.
External interaction anchors are server-owned: use the node ids supplied in the user message.
Do not author entry points, triggers, or service-call nodes.

Each outbound anchor includes its approved `failureMode`. `PROPAGATE` and `INLINE_RESPONSE` do not
create an error scope around that outbound occurrence. Create an `errorScopeRegion` when the approved
brief explicitly requires try/catch behavior or an outbound occurrence has `failureMode=ERROR_SCOPE`.
In the latter case, place that occurrence inside the try path.

The brief labels each of these, so copy the value after the matching `=` sign and nothing else. A
fact renders as `- [POSITIVE] <text> sourceFactId=<id>`, and a service call as
`- serviceCallId=<id> ...`.

The server owns everything it can derive. Leave out revision ids, edge ids, the semantic schema
version, and the compiler contract version. Leave out the catalog capability behind an entry point
and the catalog operation behind a service call: the server reads both from the brief.

List internal processing nodes under `operations` and give each one a local `nodeId` that edges reference.
Copy `elementType` from the allowed values in the user message. Region kind names are not element types.

List each control-flow region under the list that matches its kind: `sequenceRegions`,
`conditionRegions`, `splitRegions`, `loopRegions`, `retryRegions`, or `errorScopeRegions`. Omit
those lists when the chain is linear.

Connect the nodes with `edges`. An edge carries `sourceNodeId`, `targetNodeId`, an optional
`regionId`, and a `routeKind`. Omit `routeKind` for a plain sequence edge. Put every mapping from
the brief on exactly one edge through `mappingIntentId`, and keep a `mapper-2` or `script` node next
to that edge.

Scoped routes must reference their owning region. A `CATCH_PATH` names a handler declared in that
error scope. Represent required error handling with `errorScopeRegions`, its try and catch paths,
and the finally path when needed. Keep the approved error behavior and mappings when repairing topology.
Use `TRY_PATH`, `CATCH_PATH`, and `FINALLY_PATH` only once per corresponding branch, from the error-scope
owner to that branch's entry node. Use plain sequence edges within each branch. Connect a node that runs
after the complete error scope from the wrapper with a plain sequence edge outside the region.
Do not merge ordinary paths by giving a node multiple incoming edges: supported branch reconvergence
uses `RECONVERGE` with the owning region and branch ids. Plain sequence edges outside a region need no region id.

Do not author IDS markdown as compiler input. The server renders IDS from the captured revision.

Use the approved requirement brief and the resolved catalog bindings from the user message.
