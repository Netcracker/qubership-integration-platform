# Chain semantic design

Capture the chain topology from the approved requirement brief.

Realize each approved elementIntent exactly. A LOCAL_NODE uses its elementRef as nodeId and
its declared elementType; attach its facts to that node and preserve before/after order.
For elementRef A, predecessorElementRef B means B executes before A: the required path is B -> A.
For elementRef A, successorElementRef B means A executes before B: the required path is A -> B.
Follow requiredExecutionOrder from the brief when constructing edges. Reordering the operations
array does not change execution; edges determine the path. Preserve these constraints during repair.
For parentElementRef B, place the element inside B through containment or B's region. Preserve
requiredContainment from the brief. Reuse body membership has no execution edge from its parent;
only predecessorElementRef and successorElementRef request execution order.
An ANCHOR keeps its interaction node. BEFORE_HOOK and AFTER_HOOK facts belong only to the
owning Service Call anchor; they cannot justify a standalone Script. A requested Checkpoint
is a separate OPERATION with elementType=checkpoint, even though it calls no external actor.
Copy a CHAIN requestedName verbatim to chainIdentity. Names and exact values are author input.
Use only fact IDs from the supplied brief. Example IDs are never valid requirement evidence.

Submit one candidate with `captureChainSemanticRevision` per runtime attempt. If capture is rejected,
return the validator findings and finish the attempt. The runtime owns the bounded regeneration budget
and supplies those findings on the next attempt. A rejected topology does not by itself require changing
the approved brief. After accepted capture, finish without further tool calls.

Copy `sourceFactIds` and `mappingIntentId` from the approved brief. Do not mint occurrence ids.
External interaction anchors are server-owned: use the node ids supplied in the user message.
The server projects entry points, triggers, service calls, and native outbound nodes from capability facts.
To attach additional `sourceFactIds` to an existing anchor, list its exact `nodeId` and `elementType`
under `operations`. Use the supplied trigger type, `service-call` for a catalog call, or the native
outbound type. This adds provenance to the existing node without creating or replacing an interaction.
Attach event and call behavior facts to their anchors, and sender configuration facts to that sender.
Implement standalone local script facts on a `script` node in the approved execution order.
An explicitly requested service-call `before` or `after` Script is an inline hook on that call.
Attach its fact IDs to the existing `service-call` anchor under `operations`; do not create a
separate Script operation for that hook. The service-call generator supplies the hook body.

For example, when the supplied anchors are `entry` and `call`, and `prepare-body` requests
an inline before-call script, capture the call's provenance and a direct execution edge:

```json
{
  "capture": {
    "chainIdentity": "Example inline hook",
    "operations": [
      {"nodeId": "call", "elementType": "service-call", "sourceFactIds": ["prepare-body"]}
    ],
    "edges": [{"sourceNodeId": "entry", "targetNodeId": "call", "routeKind": "SEQUENCE"}]
  }
}
```

The hook runs inside `call`; its fact does not own a standalone processing node.
Copy the actual anchor IDs and all other applicable fact IDs from the approved brief.

Combine input parsing, context retention, and request mapping in one script when they run consecutively
on the same branch and in the same error scope. Save the required original values to exchange properties
before replacing the body with the mapped request. Put every implemented behavior and field-mapping
fact in that script's `sourceFactIds`; different fact kinds do not require different nodes.
Keep separate scripts when the author explicitly requests separate steps or a service call,
branch, loop, retry, or error-scope boundary separates the work. Keep response assembly after its
producing call; it cannot share the request script before that call.
Preserve an explicitly requested Script as an operation with `elementType=script`.
A conditional check inside its Groovy body does not require a condition region or if/else containers.

Every positive BEHAVIOR and CAPABILITY fact must appear in a semantic node's `sourceFactIds`.
The server initially attaches each anchor's primary fact. Include its
configuration facts yourself, including connection, topic, serializers, security, and propagation.
Check the complete fact list before capture and again after correcting a rejection. Keep each fact
on the node that implements it. Copy the entire approved chain name to `chainIdentity`, preserving
its words, spaces, and run id. A run id or a slug alone does not preserve the requested name.

Each outbound anchor includes its approved `failureMode`. `PROPAGATE` and `INLINE_RESPONSE` do not
create an error scope around that outbound occurrence. Create an `errorScopeRegion` when the approved
brief explicitly requires try/catch behavior or an outbound occurrence has `failureMode=ERROR_SCOPE`.
In the latter case, place that occurrence inside the try path.
For `INLINE_RESPONSE`, put the failure field assembly on the same response script as the success
fields, after the failed call and before the outbound result delivery. Include the error fact's
`sourceFactId` on that script. The call still runs on every path; do not add a shortcut from the
entry to delivery. For an inbound HTTP response without an outbound delivery, put that script
at the end of the approved path. Retain original event values before the call replaces the body.

Example with server-owned anchors `entry`, `call`, and `deliver`, approved transitions
`entry -> call -> deliver`, `call.failureMode=INLINE_RESPONSE`, behavior fact IDs
`receive-event`, `invoke-call`, `retain-input`, `assemble-result`, and `failure-fields`, field-mapping fact IDs `request-fields`
and `response-fields`, and mapping intent IDs `entry-to-call-request` and `call-to-deliver-request`:

```json
{
  "capture": {
    "chainIdentity": "Example result delivery",
    "operations": [
      {"nodeId": "entry", "elementType": "async-api-trigger", "sourceFactIds": ["receive-event"]},
      {"nodeId": "call", "elementType": "service-call", "sourceFactIds": ["invoke-call"]},
      {"nodeId": "prepare-request", "elementType": "script", "sourceFactIds": ["retain-input", "request-fields"]},
      {"nodeId": "build-result", "elementType": "script", "sourceFactIds": ["assemble-result", "response-fields", "failure-fields"]}
    ],
    "edges": [
      {"sourceNodeId": "entry", "targetNodeId": "prepare-request", "routeKind": "SEQUENCE"},
      {"sourceNodeId": "prepare-request", "targetNodeId": "call", "mappingIntentId": "entry-to-call-request", "routeKind": "SEQUENCE"},
      {"sourceNodeId": "call", "targetNodeId": "build-result", "routeKind": "SEQUENCE"},
      {"sourceNodeId": "build-result", "targetNodeId": "deliver", "mappingIntentId": "call-to-deliver-request", "routeKind": "SEQUENCE"}
    ]
  }
}
```

The example assumes `entry.elementType=async-api-trigger` and `call.elementType=service-call`.
Copy the supplied fact IDs and anchor types. The two anchor entries only extend existing nodes.
Keep response payload facts on `build-result`. Omit `errorScopeRegions` for this inline response.

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
those lists when the chain is linear. Omit optional fields you do not use; do not send `null`.
For a condition whose branches finish independently, omit `reconvergenceNodeId`.
If/Else and other branch containers are projected from their region; do not list them as operations.
Keep those branches terminal. Do not invent a shared no-op Script to join them or fill a
reconvergence field. Include an empty Script only when the approved brief explicitly requests it.
An empty optional node ID also means absent. Do not replace it with an invented node.

Example for terminal condition branches: the supplied anchors are `inbound` and `call`,
and the Else branch contains a requested error-response Script. Both branches end independently.

```json
{
  "capture": {
    "chainIdentity": "Example terminal condition",
    "operations": [
      {"nodeId": "choose", "elementType": "condition", "sourceFactIds": ["condition-policy"]},
      {"nodeId": "reject", "elementType": "script", "sourceFactIds": ["error-response"]}
    ],
    "conditionRegions": [{
      "regionId": "choice", "ownerNodeId": "choose",
      "branches": [
        {"branchId": "valid", "role": "IF", "predicate": "$\{header.valid} == \"true\"", "priority": 0, "entryNodeId": "call", "exitNodeIds": ["call"]},
        {"branchId": "invalid", "role": "ELSE", "priority": 1, "entryNodeId": "reject", "exitNodeIds": ["reject"]}
      ]
    }],
    "edges": [
      {"sourceNodeId": "inbound", "targetNodeId": "choose", "routeKind": "SEQUENCE"},
      {"sourceNodeId": "choose", "targetNodeId": "call", "regionId": "choice", "routeKind": "CONDITION_BRANCH", "branchId": "valid"},
      {"sourceNodeId": "choose", "targetNodeId": "reject", "regionId": "choice", "routeKind": "CONDITION_BRANCH", "branchId": "invalid"}
    ]
  }
}
```

There is no reconvergence node, no `RECONVERGE` edge, and no Script after the condition.
Use the actual anchor IDs, predicate, and facts from the approved brief.

For `split-2`, use a split region with `mode=SYNC`. Its parallel branches finish before the
following node processes their aggregated result. Use `mode=ASYNC` only with `split-async-2`
when the approved brief requests an asynchronous split. Parallel processing alone does not
select ASYNC. The region mode must match its owner's element type.
Copy each requested branch name exactly into `branchId`; the server uses it as `splitName`.
For SYNC, set `mainBranchId` to the branch explicitly requested as Main. When no Main branch is
specified, the server uses the first ordered branch. ASYNC has no Main branch; omit `mainBranchId`.
List branch work as operations. The server projects `main-split-element-2`, `split-element-2`, and
`async-split-element-2` containers; do not list those containers as operations.
Connect the owner to each branch's entry with `SPLIT_BRANCH`. For SYNC, connect each branch's exit
to the following node with `RECONVERGE`, naming that branch in `branchIds`. Setting
`reconvergenceNodeId` does not create those edges. For ASYNC, connect the owner directly to the
main flow's next node with a plain sequence edge. The asynchronous branch ends independently;
omit `reconvergenceNodeId`, `mainBranchId`, and branch-to-continuation edges. Use `splitRegions`
to declare branch membership; do not add containment for branch work. All edges follow execution order.

Example for an asynchronous split with one outbound call and a requested main-flow Script.
The supplied anchors are `inbound` and `call`; replace IDs and facts with the approved values.

```json
{
  "capture": {
    "chainIdentity": "Example asynchronous split",
    "operations": [
      {"nodeId": "split", "elementType": "split-async-2", "sourceFactIds": ["async-policy"]},
      {"nodeId": "continue", "elementType": "script", "sourceFactIds": ["main-continuation"]}
    ],
    "splitRegions": [{
      "regionId": "async-region", "ownerNodeId": "split", "mode": "ASYNC",
      "branches": [{"branchId": "background", "order": 0, "entryNodeId": "call", "exitNodeIds": ["call"]}]
    }],
    "edges": [
      {"sourceNodeId": "inbound", "targetNodeId": "split", "routeKind": "SEQUENCE"},
      {"sourceNodeId": "split", "targetNodeId": "call", "regionId": "async-region", "routeKind": "SPLIT_BRANCH", "branchId": "background"},
      {"sourceNodeId": "split", "targetNodeId": "continue", "routeKind": "SEQUENCE"}
    ]
  }
}
```

Example for a synchronous split with two script branches and a script after aggregation.
The anchor is `inbound`; replace the names and fact IDs with those from the approved brief.
When a branch consists of one outbound call, use its existing anchor as both branch entry and exit.
Do not add a no-op Script to create a branch entry; the server creates the branch container.

```json
{
  "capture": {
    "chainIdentity": "Example split",
    "operations": [
      {"nodeId": "split", "elementType": "split-2", "sourceFactIds": ["split-policy"]},
      {"nodeId": "left-work", "elementType": "script", "sourceFactIds": ["left-body"]},
      {"nodeId": "right-work", "elementType": "script", "sourceFactIds": ["right-body"]},
      {"nodeId": "check-result", "elementType": "script", "sourceFactIds": ["check-aggregation"]}
    ],
    "splitRegions": [{
      "regionId": "split-region", "ownerNodeId": "split", "mode": "SYNC", "mainBranchId": "left-branch",
      "branches": [
        {"branchId": "left-branch", "order": 0, "entryNodeId": "left-work", "exitNodeIds": ["left-work"]},
        {"branchId": "right-branch", "order": 1, "entryNodeId": "right-work", "exitNodeIds": ["right-work"]}
      ],
      "reconvergenceNodeId": "check-result"
    }],
    "edges": [
      {"sourceNodeId": "inbound", "targetNodeId": "split", "routeKind": "SEQUENCE"},
      {"sourceNodeId": "split", "targetNodeId": "left-work", "regionId": "split-region", "routeKind": "SPLIT_BRANCH", "branchId": "left-branch"},
      {"sourceNodeId": "split", "targetNodeId": "right-work", "regionId": "split-region", "routeKind": "SPLIT_BRANCH", "branchId": "right-branch"},
      {"sourceNodeId": "left-work", "targetNodeId": "check-result", "regionId": "split-region", "routeKind": "RECONVERGE", "branchIds": ["left-branch"]},
      {"sourceNodeId": "right-work", "targetNodeId": "check-result", "regionId": "split-region", "routeKind": "RECONVERGE", "branchIds": ["right-branch"]}
    ]
  }
}
```

Execution edges form a DAG, including loops. Never draw an edge back to a previous node or trigger.
An inbound HTTP response uses the current exchange. Setting its response body, status, or failure
state does not require an edge back to the HTTP trigger or a new outbound interaction.
The final response script is terminal unless the approved brief specifies a following step.
A loop region describes repetition; never draw an edge back to its owner. For `loop-2`, supply `ownerNodeId`,
`bodyEntryNodeId`, `bodyExitNodeIds`, `exitNodeId`, `loopMode`, and `loopExpression`.
Copy the approved mode and expression exactly. Include a positive `loopSafetyBound` only when
the approved brief explicitly requests an iteration cap. Otherwise omit it or use `0`; the server applies
the element schema default. Do not infer a cap from the loop expression or choose a sample value.
`LOOP_BODY` connects the owner
to the first body node; `LOOP_EXIT` connects the last body node to `exitNodeId`. Both edges name the
loop's `regionId`. Use sequence edges between body nodes. Loop membership comes from `loopRegions`;
do not add containment with `role=body`, which `loop-2` does not declare.

Example for an approved do-while loop without a requested cap, a body script, and a script after the loop.
The anchor is `inbound`; the approved facts are `loop-policy`, `body-work`, and `after-work`.
Replace the example ids and expression with values from the supplied brief.

```json
{
  "capture": {
    "chainIdentity": "Example loop",
    "operations": [
      {"nodeId": "repeat", "elementType": "loop-2", "sourceFactIds": ["loop-policy"]},
      {"nodeId": "work", "elementType": "script", "sourceFactIds": ["body-work"]},
      {"nodeId": "after", "elementType": "script", "sourceFactIds": ["after-work"]}
    ],
    "loopRegions": [{
      "regionId": "repeat-region", "ownerNodeId": "repeat",
      "bodyEntryNodeId": "work", "bodyExitNodeIds": ["work"], "exitNodeId": "after",
      "loopMode": "DO_WHILE", "loopExpression": "$\{header.more} == \"true\""
    }],
    "edges": [
      {"sourceNodeId": "inbound", "targetNodeId": "repeat", "routeKind": "SEQUENCE"},
      {"sourceNodeId": "repeat", "targetNodeId": "work", "regionId": "repeat-region", "routeKind": "LOOP_BODY"},
      {"sourceNodeId": "work", "targetNodeId": "after", "regionId": "repeat-region", "routeKind": "LOOP_EXIT"}
    ]
  }
}
```

Connect the nodes with `edges`. An edge carries `sourceNodeId`, `targetNodeId`, an optional
`regionId`, and a required `routeKind`. Use `SEQUENCE` for a plain sequence edge. Put every mapping from
the brief on exactly one edge through `mappingIntentId`, and keep a `mapper-2` or `script` node next
to that edge. If both ends of a mapped edge are scripts, the source script owns the mapping.
For combined retention and request mapping, use `entry -> prepare-request -> call` and put
`mappingIntentId` on `prepare-request -> call`. Keep both retention and mapping facts on
`prepare-request`. When an approved boundary requires separate scripts, put the mapping only on
its implementing script's edge to the destination call; retain each script's behavior facts.
When a BEHAVIOR fact restates an entry event or external call, attach it to that existing anchor
using its exact nodeId and elementType. This provenance annotation adds no execution node.

Scoped routes must reference their owning region. A `CATCH_PATH` names a handler declared in that
error scope. For an approved `ERROR_SCOPE` or explicit try/catch requirement, use `errorScopeRegions`
with its try and catch paths and the finally path when needed. Keep the approved error behavior
and mappings when repairing topology.
Use `TRY_PATH`, `CATCH_PATH`, and `FINALLY_PATH` only once per corresponding branch, from the error-scope
owner to that branch's entry node. Use plain sequence edges within each branch. Connect a node that runs
after the complete error scope from the wrapper with a plain sequence edge outside the region.
The finally path ends at its last explicitly requested finally operation. A following verification script
is not a finally member: exclude it from the region's `exitNodeIds` and connect the wrapper directly to it.
For example, a Finally script that sets a property and a later Check values script remain separate.
Declare try, catch, and finally membership only in `errorScopeRegions`; omit containment for their work.
The server projects the native `try-2`, `catch-2`, and `finally-2` containers.
Example with the supplied HTTP anchor `http-in`, three requested branch scripts, and a later verification script.
Replace all names and fact IDs with the approved values. The verification script remains outside the scope.

```json
{
  "capture": {
    "chainIdentity": "Example try catch finally",
    "operations": [
      {"nodeId": "scope", "elementType": "try-catch-finally-2", "sourceFactIds": ["scope-policy"]},
      {"nodeId": "try-work", "elementType": "script", "sourceFactIds": ["try-work"]},
      {"nodeId": "catch-work", "elementType": "script", "sourceFactIds": ["catch-work"]},
      {"nodeId": "finally-work", "elementType": "script", "sourceFactIds": ["finally-work"]},
      {"nodeId": "verify", "elementType": "script", "sourceFactIds": ["verification"]}
    ],
    "errorScopeRegions": [{
      "regionId": "scope-region", "ownerNodeId": "scope", "tryEntryNodeId": "try-work",
      "handlers": [{
        "handlerId": "catch-exception", "exceptionClass": "java.lang.Exception",
        "entryNodeId": "catch-work", "exitNodeIds": ["catch-work"]
      }],
      "finallyEntryNodeId": "finally-work", "exitNodeIds": ["finally-work"]
    }],
    "edges": [
      {"sourceNodeId": "http-in", "targetNodeId": "scope", "routeKind": "SEQUENCE"},
      {"sourceNodeId": "scope", "targetNodeId": "try-work", "routeKind": "TRY_PATH", "regionId": "scope-region"},
      {
        "sourceNodeId": "scope", "targetNodeId": "catch-work", "routeKind": "CATCH_PATH",
        "regionId": "scope-region", "handlerId": "catch-exception"
      },
      {"sourceNodeId": "scope", "targetNodeId": "finally-work", "routeKind": "FINALLY_PATH", "regionId": "scope-region"},
      {"sourceNodeId": "scope", "targetNodeId": "verify", "routeKind": "SEQUENCE"}
    ]
  }
}
```

Do not merge ordinary paths by giving a node multiple incoming edges: supported branch reconvergence
uses `RECONVERGE` with the owning region and branch ids. Plain sequence edges outside a region need no region id.

Do not author IDS markdown as compiler input. The server renders IDS from the captured revision.

Use the approved requirement brief and the resolved catalog bindings from the user message.
