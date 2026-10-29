# Separate capture repetition from recovery strategy permission

## Status

Proposed

### Date

### Owner

AI-service maintainers. Assumption: a maintainer will take ownership during review.

### Participants and approvers

The product owner approved implementation in the release-preparation conversation.
Repository review of this record is pending.

### Related ADRs

- [Capture repair budget](0005-key-the-capture-repair-budget-to-the-rejection-not-the-payload.md)
- [Run recovery budget](0006-key-the-run-recovery-budget-to-the-input-artifact-not-to-author-text.md)

## Context

A saved Greetings failure contains a repeated rejected requirement capture. The recovery model identifies a
derivation defect and requests regeneration from the existing author input. Execution refuses that action because
the local capture repetition has closed permission for another correction.

The runtime already supports targeted correction, clean regeneration, durable attempt accounting, and producer
ownership checks. Native capture recovery permits at most one combined targeted correction or clean regeneration.
Using the capture repetition flag as a veto for every strategy prevents that unused allowance from serving its purpose.

## Decision

We will distinguish another local correction from a clean regeneration in the existing recovery machinery.
A repeated correctable capture ends local repetition. It may use an unspent native recovery allowance for the existing
clean regeneration strategy, subject to the run ledger and normal eligibility checks.

The native combined allowance remains one. The change does not increase request, token, time, or run limits, reset
failure identity, or create new credits. A spent allowance remains spent after another refusal or reload.

Permanent refusals, successful capture terminators, malformed tool arguments, and missing permission evidence do not
gain clean regeneration permission through this change. Invocation proof, saved inputs, producer ownership, policy,
source evidence, approval, and catalog write checks continue to apply. An ineligible clean strategy cannot fall back
to a targeted correction forbidden by the capture policy.

### Justification

Strategy permission belongs with the existing recovery decision and execution checks. A local repetition supplies
evidence that targeted correction did not work; it does not establish that clean regeneration is unavailable.
Increasing limits would leave that distinction unresolved. Error-specific exceptions would apply the same contract
inconsistently across capture tools.

## Consequences

This record amends the scope of the outer-turn refusal described in ADR 0005 for repeated correctable captures.
That refusal continues to prohibit another targeted correction. The existing clean strategy can spend the unused
combined allowance. ADR 0006 retains ownership of durable run accounting.

Connected regressions must demonstrate repeated rejection, recovery strategy execution, accepted progress, and refusal
after the existing allowance is spent. A normal Greetings run checks construction on the resulting source revision;
it demonstrates this recovery path only if the trace actually exercises it.
