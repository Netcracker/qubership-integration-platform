# Confirm a chain patch with a decision card, not an approval record

`COMPARE_AND_PATCH` writes a `GraphPatch` onto an existing, possibly production, catalog chain. Two
confirmation mechanisms already exist. The CREATE pipeline builds an `ApprovalRecordV2` before
`MaterializationCapability` writes anything: a digest-chained record that ties an approval to one
artifact revision across a multi-stage run. Separately, `ChatEvent.Decision` presents a gate as a
card in the transcript, answered by a typed `ChatDecisionCommand` that carries its own binding.

Decided: the patch skill emits a decision card describing the patch (what it adds, what it changes,
before and after for property edits) and applies nothing until the reader answers it. No
`ApprovalRecordV2`, no digest chain -- those exist to audit an artifact as it moves through the
stages of a CREATE run, and a one-shot chat bugfix has no such stages. `GraphPatchOwnershipPolicy`
still bounds what the patch may touch, independent of the answer.

Confirmation by prose is not an option: matching a typed English word was deliberately removed from
the codebase, because a reply in any other language never matched it.

For unattended runs -- a future deploy failure triggering a repair with no reader present -- there
is no card to answer. Such a run gets 2-3 repair attempts and then reports the failure to the user,
rather than retrying without limit. Deploy does not exist yet, so the retry mechanics (backoff,
idempotency, what counts as an attempt) are deferred until it does.
