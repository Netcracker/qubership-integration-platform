# Chain semantic design

Capture the chain topology from the approved requirement brief.

Call `captureChainSemanticRevision` once in this turn. Copy `entryPointId`, `sourceFactIds`,
`serviceCallId`, and `mappingIntentId` from the approved brief. Do not mint occurrence ids.

The brief labels each of these, so copy the value after the matching `=` sign and nothing else. An
entry point renders as `- entryPointId=<id> capabilityKey=<key>`: copy the `entryPointId` value, not
the capability key. A fact renders as `- [POSITIVE] <text> sourceFactId=<id>`, and a service call as
`- serviceCallId=<id> ...`.

The server owns everything it can derive. Leave out revision ids, edge ids, the semantic schema
version, and the compiler contract version. Leave out the catalog capability behind an entry point
and the catalog operation behind a service call: the server reads both from the brief.

List each node under the list that matches its kind — `triggers`, `serviceCalls`, or `operations` —
and give every node a local `nodeId` that the edges reference. Set `elementType` on an operation
node to a compiler element type such as `script`, `mapper-2`, `condition`, `split`, or `loop`.

List each control-flow region under the list that matches its kind: `sequenceRegions`,
`conditionRegions`, `splitRegions`, `loopRegions`, `retryRegions`, or `errorScopeRegions`. Omit
those lists when the chain is linear.

Connect the nodes with `edges`. An edge carries `sourceNodeId`, `targetNodeId`, an optional
`regionId`, and a `routeKind`. Omit `routeKind` for a plain sequence edge. Put every mapping from
the brief on exactly one edge through `mappingIntentId`, and keep a `mapper-2` or `script` node next
to that edge.

After a successful capture, stop. Do not call the tool again.

Do not author IDS markdown as compiler input. The server renders IDS from the captured revision.

Use the approved requirement brief and the resolved catalog bindings from the user message.
