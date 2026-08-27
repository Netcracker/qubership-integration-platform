# Key the run recovery budget to the input artifact, not to author text

ADR 0005 keys a capture-layer budget to normalized rejection text because Java writes that
text. The run-layer halt budget cannot key on author text at all. It keys on whether the
owning producer's input artifact changed.

Capture and run sit on opposite sides of the same question. A generator answering a refused
capture re-emits the whole artifact, so the payload changes while the complaint stays put.
Keying the soft credit on the payload would hand out a new credit every time; keying it on
the normalized complaint spends one credit per defect. That works because Java authors the
rejection. `ToolCallFingerprints.failureSignature` (the method ADR 0005 still names
`signature`) can mask identifiers for that reason: the same rule on node A and node B is
one defect.

A chain author answering a halt is not a generator. The budget has to ask whether anything
the failing stage consumes actually changed. Masking identifiers in the author's words makes
`orders.v2` and `orders.v3` the same correction, so the usual fix spends a credit without
being tried. Leaving the words unmasked lets a rephrase buy an unlimited number of attempts.
No normalization of free author text closes both holes.

Decided: `RecoveryAttemptKey(ownerStageId, causeCode, evidenceIdentity, correctionEpoch)` is
the run-layer identity.

- `causeCode` and `evidenceIdentity` come from the typed `RecoveryCause`. Findings are
  hashed as structured `code` plus `message` plus `requestedFact`, with no identifier
  masking. Two findings that name different properties are two defects.
- `correctionEpoch` advances only when an accepted correction changed the approved input
  artifacts the owning producer consumes. Questions, Retry clicks, and Revise clicks do not
  advance it. A rephrasing that leaves those artifacts identical does not advance it.
- `RecoveryAttemptLedger` is the only module that applies the per-key limits and the
  absolute per-run ceiling. Guards ask it; they do not count journal transitions themselves.
- A trusted adapter records `InputOrigin` on `AcceptInputCommand`. Absent or untrusted
  origin uses the flat budget: epoch is ignored, so a new artifact does not earn another
  attempt. The transport does not prove a human typed the text.

Author-initiated and automatic reopens are written under distinct journal prefixes. The
automatic budget counts only the automatic prefix. The owner-already-reopened membership
test counts both, and still reads the legacy `causal reopen of ` prefix, because it asks
whether an owner has seen this defect, not who sent the command.

This does not supersede ADR 0005. The capture ledger still bounds a turn by the rejection.
The run ledger bounds a halt by the input artifact. Each layer keys on the thing that is
stable for the actor it is budgeting.
