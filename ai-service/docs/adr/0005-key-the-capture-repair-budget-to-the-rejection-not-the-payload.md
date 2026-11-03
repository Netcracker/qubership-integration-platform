# Key the capture repair budget to the rejection, not to the payload that caused it

A capture tool is how a generator hands a typed artifact to Java: a requirement brief, a plan, a
chain structure, a naming manifest, the subgraph an edit adds. Java validates what arrives, and a
refusal has to answer two questions at once. Does the generator get another try inside the turn it
is already in, and may `CaptureRepairRunner` open a fresh turn afterwards? Both answers used to be
written per tool, in the `catch` block that noticed the problem, and the tools did not agree.

Decided: `CaptureToolOutcomeGateway` is the only exit for a refused capture, and
`CaptureFailurePolicy.decide` is the pure function behind it. An adapter classifies the failure and
does nothing else; a failure class and an attempt state produce one `CaptureFailureDecision`, which
carries a soft tool result, a `CaptureValidationException` (CVE), and permission for an outer repair
turn.

| Class | Attempt state | Soft result | CVE | Outer turn |
|---|---|---|---|---|
| `CORRECTABLE` | soft credit unspent for this fingerprint | yes | no | allowed |
| `CORRECTABLE` | soft credit spent for this fingerprint | no | yes | refused |
| `IDENTICAL_SPAM` | any | no | yes | refused |
| `PERMANENT` | any | no | yes | refused |
| `ACCEPTED` | any | no | yes | refused |
| `DUPLICATE` | any | no | yes | refused |
| `TOOL_ARGUMENTS` | any | no | no | refused |

An adapter picks between `CORRECTABLE` and `PERMANENT`, and only those two. The second row is where
`IDENTICAL_SPAM` comes from: no adapter classifies it, because no adapter knows what the generator
has already been told. A `CORRECTABLE` failure whose credit is gone degrades into it, and the
decision that comes back carries `IDENTICAL_SPAM` as its class, so what the ledger, the metrics,
and the repair message builder see is one class rather than "correctable, but the second time".

The remaining rows need less argument:

- A soft result is a string returned to the model as the tool's answer, so the tool loop continues
  and the generator can call the tool again in the same turn. That is the only outcome the model
  gets to keep working from.
- A CVE ends the stream. It implements `PreventsErrorHandlerExecution`, so quarkus-langchain4j
  rethrows it rather than turning it into another tool result and letting the loop run on.
- `ACCEPTED` and `DUPLICATE` are terminators rather than failures. The artifact is in hand, and the
  turn is stopped on purpose so harvest runs immediately instead of waiting for the model to write a
  closing sentence.
- `PERMANENT` is a capture that cannot exist. Asking a CREATE run for an edit subgraph, or an edit
  run for a whole graph, is refused by the intent itself, and a retry would spend the turn restating
  an impossible request.
- `TOOL_ARGUMENTS` is the framework's channel, not the domain's. The arguments failed to
  deserialize, so the tool never ran, and the assistant message requesting it is already in chat
  memory with no result beside it. It gets a plain tool result, no CVE, and no repair turn.

The identity that the soft credit is spent against is the rejection, not the payload that earned it:
`tool + NUL + capabilityOrEmpty + NUL + sha256Hex(signature(message))`, in
`ToolCallFingerprints.failureFingerprint`. This is the load-bearing decision of the design.

A generator answering a refusal does not send a patch. It re-emits the artifact: a merge refusal on
one node comes back as the whole graph again, differing by hundreds of characters from the attempt
before it while earning the identical complaint. Keying the budget on the payload therefore hands
out a fresh credit on every attempt, and `IDENTICAL_SPAM` is unreachable. The loop it exists to stop
runs until some other budget runs out. Keying on the normalized complaint spends one credit per
distinct defect, which is what a budget for defects is for. It works because Java writes the
rejection text: the signature is stable across attempts for the same reason the payload is not.

`ToolCallFingerprints.signature` lowercases the message, collapses whitespace, and masks UUIDs,
single-quoted values, and bracketed lists into `<id>` and `<ids>`. Masking identifiers is
deliberate, not incidental normalization. The same rule tripping on node A and then on node B is one
defect reported twice, and a generator that fixes A and trips B has not found a new problem to spend
a second credit on. Counts are left alone for the mirror-image reason: two rejections that differ
only by a number are usually two different states of the artifact, and collapsing them would charge
a generator that is making progress for a defect it has already partly fixed. The tool and
capability names stay outside the hash, so the same sentence from two tools is two identities.

A `PERMANENT` failure and a repeated identical rejection both refuse an outer repair turn, and they
refuse it for the same reason under two different descriptions. `CaptureFailureDecision` sets
`outerAllowed` to false for both, `CaptureAttemptFeedback` carries it to the runner, and
`CaptureRepairRunner.outerAllowed` honors it on both paths into a retry. An outer turn sends "fix it
and call the tool again". For a permanent failure the same skill cannot answer that at all; for a
repeated rejection the generator has already failed to answer that exact complaint twice. Spending a
model call to ask a third time is the behavior the matrix exists to prevent. The policy owns
permission only: how many repair turns a run may take is the runner's budget, and the two are not
the same number.

Ending a turn with a CVE leaves chat memory in one of two shapes OpenAI refuses on the next request:
an assistant `tool_calls` message with no matching result, and an assistant turn with neither text
nor a tool call. Either one costs the conversation every turn that follows, including the repair
turn that would have fixed the capture. `ChatMemorySanitizer` repairs both in one pass. Without it,
the matrix would be deciding whether a repair turn is worth running while the repair turn could not
run at all.

Two alternatives were considered and rejected. Counting attempts per conversation rather than per
defect is simpler and punishes the wrong generator: one that walks through three distinct defects
looks identical to one that resends the same mistake three times. Leaving soft-versus-throw to each
adapter is what the gateway replaced; the classification is domain knowledge and belongs to the
adapter, but the budget is a property of the conversation and no adapter can see it. The payload
fingerprint itself survives in `CaptureAttemptFeedbackStore`, where the legacy plan-validation
ratchet still uses it to notice a payload sent twice verbatim. That is a narrower question than the
soft budget, and the same answer does not fit both.

The cost is that a conversation carries a ledger. `ToolCallFingerprintStore` holds spent credits per
conversation in memory, so a restart returns every credit and a run that is already looping gets to
loop once more. That is an acceptable trade for now: the ledger bounds a turn, not a run, and the
run-level halt has its own budget.

One decision next to this one is still unrecorded. `ACCEPTED` and `DUPLICATE` leave through the
same CVE path the failure classes use, but why a successful capture stops the tool loop instead of
letting the model close the turn is a choice with its own reasoning, not a corollary of this
matrix. `CaptureFailureClass` and `CaptureToolOutcomeGateway.onTerminalAccept` cite ADR 0001 for
it, which in this repository is the compare-and-patch confirmation record and says nothing about
harvest. That decision needs a record of its own; this one does not cover it, and the citations
stay wrong until it exists.

## Amendment, November 3, 2026

A rejection used to end the turn. `ChainSemanticCaptureTool`, `DesignPlanCaptureTool`, and `HttpTriggerCaptureTool`
declared `ReturnBehavior.IMMEDIATE`, and the design-input tool told the generator to submit one candidate and finish.
On the blocking agents that made every soft result final: the model never saw it, and each correction cost an outer
repair turn from the stage budget. Coding agents work the other way, returning a tool error inside the turn so the
model fixes it and calls again, and these three tools now do the same.

Decided: a `CORRECTABLE` rejection keeps the turn open. The soft result carries every issue back to the model as the
tool's answer, and the tool description asks it to correct each listed issue and call the tool again. The turn ends
in three ways only. An accepted capture throws the `ACCEPTED` terminator, as `CompilerGraphPatchTool` does, and the
caller harvests the binding. `IDENTICAL_SPAM` still throws a CVE: the repeated proposal on the planning and HTTP
tools, and on design-input the third repeat of a rejected proposal, which answers `REPAIR_NO_PROGRESS`. On
design-input and
planning, a validated capture whose result says `STOP` throws as well; a tool-argument refusal stays a tool result.
`maxSequentialToolInvocations = 6` bounds one turn on all three agents. A design-input turn that reaches that limit
reports its last rejection, as a turn that ends after one rejection does. On the streaming HTTP agent `IMMEDIATE`
never took effect, because quarkus-langchain4j honors a return behavior only on the blocking path, so that tool
already corrected in the turn; it loses the inert annotation and gains three tool calls per turn.

A CVE thrown mid-loop leaves chat memory valid without the sanitizer on the blocking agents: quarkus-langchain4j
stores a blocking turn only when it completes, so the ended turn leaves nothing behind. The streaming path keeps the
repair `CompilerSkillRuntime` already runs before each turn.

The clean-regeneration permission of ADR 0008 is part of this record. A repeated correctable capture ends local
repetition and refuses another targeted correction, but it may spend an unspent clean regeneration from the existing
input, subject to the run ledger and the usual eligibility checks. ADR 0008 is superseded by this amendment.

The runner's budget is no longer the last word on repair turns. The stage executor grants another automatic
correction, and rebuilds the design or the plan, while the run stays below `qip.ai.create.recovery-attempt-ceiling`
(12 by default). A Retry on a model-error card grants a fresh budget of two targeted corrections and two clean
regenerations. Three limits bound the loop together: six tool calls bound a turn, the run ceiling bounds repairs,
clean rebuilds, and reopens, and `qip.ai.llm.max-requests-per-run` bounds the model calls of the whole run. Keeping
the turn open therefore adds no unbounded path.

Rejection identity splits in two. The capture fingerprint keeps named values and masks only UUIDs, so a rejection on
node A and one on node B are two defects, each with its own soft credit. The stage-level halt identity, which also keys
recovery ledger causes and narrative questions, still masks quoted and bracketed values, because those failures carry
values that change on every attempt and would otherwise never escalate. This replaces the masking rule described
above for the capture fingerprint only.
