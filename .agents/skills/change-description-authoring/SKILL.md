---
name: change-description-authoring
description: >-
  Load before writing the commit message, the pull request title and description, or the changelog
  or release-note entry for a change, including at the end of a coding task ("fix the bug",
  "implement the feature", "prepare the PR"): load it when the code is done and before git commit.
  Also load to rewrite, review, or compare any of them, or to decide whether a change needs a
  changelog entry at all. Governs what each artifact says and for whom: the reviewer, the future
  git blame reader, the on-call engineer holding a stack trace, the upgrading user, the backporter;
  the slots of a commit body and a description; the merge model and what survives it; trailers;
  house style gated on the workflow; identifiers that are never invented. Wording is
  english-developer-style's; load both.
---

# Authoring a change description

This skill governs **what a change description says, in what order, and for whom**. Wording, tense,
mood, sentence length, punctuation, and dialect belong to `english-developer-style`; load it too,
and let it own the sentences. Subject length limits, prefix grammar, and trailer syntax belong to
commitlint or the platform, not to a prompt.

A report of a problem nobody has fixed yet is a different artifact with a different reader, and it is
`problem-report-authoring`'s: an issue, a feature request, a question, a support ticket, or a message
to a colleague. Its reader holds no diff, so it establishes the problem before anything else, and what
it may then say about the cause and the fix is that skill's to decide. Do not carry the mechanism of
the Why slot or the change of the What slot into one.

A change description is three artifacts written at once: the commit message, the pull request
description, and the changelog entry. They are read by five readers, at five different moments,
holding five different things. The rules below name the reader every time, because the same
sentence is essential to one reader and noise to another, and the only way to decide is to ask
whose question it answers.

## 1. The correction that matters most

**Write for the readers who are not in the room.** The reviewer can ask; the other four cannot. The
archaeologist holds a line of code years later, the on-call engineer holds a stack trace at 3 a.m.,
the upgrading user holds release notes, and the backporter holds a list of subjects. Each of them
has
one artifact and one search string, and a description that answers the reviewer's questions in the
pull request while leaving `git log` with a title has served the one reader who needed it least.

| Reader | Holds | Opens | Asks |
| --- | --- | --- | --- |
| **R1 Reviewing maintainer** | The diff | The pull request description | Was something wrong? Is this behavior right? How was it checked? What is left out? |
| **R2 Archaeologist** | A line of code, via `git blame` | The commit message | Why does this exist? Why this approach? Which constraint forced it? |
| **R3 On-call engineer** | A stack trace or a log line, and a version | The changelog, then the commit a bisect reaches | Is this my incident? What triggers it? Am I on an affected version? Is there a workaround? |
| **R4 Upgrading user** | The version they run and the one they want | The release notes | What changed that I can observe? Is it compatible? What must I do? |
| **R5 Backporter** | A maintenance branch | Labels, trailers, and the subject list | Is this a fix? How severe? Which versions? Does it need another change first? |

The test that follows from this, applied to every sentence: **name the reader and the question it
answers.** A sentence that answers none of the questions above belongs in another artifact, or
nowhere.

**The permanent record is the commit, not the pull request.** In a repository that rebases, or
merges with merge commits that carry only the title, the description never reaches `git log`; in a
repository that squashes with the default setting, the body of a multi-commit squash is the list of
branch commit messages, not the description. Write the commit message as if the pull request did
not exist. The description still carries the symptom and the literal error text: search engines
index a pull request, and a user holding an exception has no clone to run `git blame` in.

## 2. The slots

### Commit subject

One slot: **the change, as what it does to behavior**, in a form that stands alone in a one-line
listing. Reader R2, then R1. Test: *R2, reading `git log --oneline`, asks what this commit did.* A
file name, a phase, a ticket number, or `Fix bug` fails it. The subject is change-first even though
the body is problem-first; the two lines serve different reading moments (§4). A type prefix and a
length limit are house style (§6).

- *Fails:* `Fix bug`, `Phase 1`, `Update JobController.go`, `JIRA-1234`.
- *Passes:* `Retry finalizer removal when the orphan pod sync fails`.

### Commit body

Five slots, in this order; the fourth is conditional. Verification is not one of them (§4).

| # | Slot | Reader | Answers | Skip when |
| --- | --- | --- | --- | --- |
| 1 | **Problem** | R2, R1, R3 | What was wrong or missing, before any word about the fix? | Never |
| 2 | **Impact and trigger** | R3, R5, R1 | What does a user or operator observe, under which condition? The diagnostic, quoted as a literal | The change is not a fix, or the defect has no symptom by construction, and the body says so in one clause; for a defect nobody has observed, the slot is the trigger condition alone |
| 3 | **Change and approach** | R2, R1 | What was done; the constraint that forced it; the alternative a reader would propose and why not; the measured trade-off | The problem statement makes the approach obvious |
| 4 | **Outcome** | R3, R4 | For each outcome the change adds or alters that a caller can observe (a test's own assertion message is not one): what the caller observes and the condition, plus the exact text and its error code or exception class where the outcome emits them, one line each under the label `User-visible:`; a deliberate non-change a reader would expect, one line under `Not changed:` | The change alters nothing a caller observes and keeps nothing a reader would expect it to change |
| 5 | **References** | R5, R2, R3, tools | Issue, report, discussion, introducing commit, backport range, as trailers (§5) | Nothing to reference |

Slot 1 opens the body. A description that §4 rule 5 copies into the body and that leads with its
breaks (§2, pull request description) puts the problem right after them. Convince the reader that
there is a problem worth fixing before saying what you did about it. The cheapest shape is "When X,
the caller got Y", and where a few lines of code show it, those lines (§2, show it).

Slot 2 carries what a person outside the code observes: the exception, the log line, the wrong
result, the hang, the regression in latency, and the circumstances that provoke it. Where nobody has
observed the defect, the slot is the condition that reaches it, and it ends there: no predicted
failure, no sentence that none was seen, and no word on the review, tool, or sweep that found it
(measured: drafts for an audited defect predicted a failure, and under a ban on that, five of ten
wrote "no failure has been observed"). Where the defect has no symptom by construction (a leak on an
error path with nothing logged), say so in one clause.

Trace a consequence derived from the code to the point where a caller sees it, through every
mechanism on the way (a lazy initialization, a retry, a fallback), before writing it; then write the
end point alone, marked as derived. The chain is the diff's (measured: two of two drafts that
predicted a symptom from one missing call stopped one step short, and the failure they described
cannot happen).

Slot 3 justifies the way the change solves the problem: why the result with the change is better,
and which alternative was considered and discarded. A rejected alternative earns its sentence only
where a reader would propose it, and a mechanism or a measurement makes it checkable where a
preference does not. A performance claim carries its number and its cost.

Slot 4 is where the pull request's "User-visible changes" slot and the changelog entry take their
outcomes from, so it is written to be lifted, not summarized. A caller is whoever uses what the
change ships: the code that calls a library, the client of a service, the user of a command. The
slot is a paragraph that opens with `User-visible:` and holds one line per outcome, followed by any
`Not changed:` lines; a commit that only keeps an outcome has the `Not changed:` lines alone. Each
line names what the caller observes and the condition under which they get it. An outcome that
emits a diagnostic also carries the text as emitted and its identifier: the SQLState, the HTTP
status, the exit code, or the exception class. A return value, a timing, a side effect, or a
protocol exchange often has no such text, and its line gives the value or the behavior itself:

```text
User-visible:
When every connection is in use, Pool.acquire() waits up to acquireTimeout instead of failing at once.
```

The symptom a fix removes is slot 2's and is not repeated here.

A commit that deliberately leaves an outcome alone that a reader would expect it to change (a
message type left unbounded, a SQLState kept where a sibling's changed) states that in one line
under `Not changed:`. Both labels are plain text, not trailers, and sit after slot 3 and before the
trailers. A fact in slot 4 is not also buried in slot 3's prose, where a later reader summarizing
the body will drop it (measured: eleven drafts of one description dropped every SQLState that sat
inside a slot 3 paragraph, and kept the ones the changelog listed on their own line).

**The one-line why is not replaced by the issue link.** Links rot, trackers move, and a good share
of
linked issues do not contain the reason either. Summarize the relevant point instead of pointing at
the archive. Minimum inline content when an issue exists: one sentence of problem, the identifier,
and the symptom string.

*Before:*

```text
Fix checkpointer assertion

See #4711.
```

*After:*

```text
Fix checkpointer restartpoint assertion failure

When recovery starts from a backup without a signal file, pg_subtrans is
not started and stays unstarted throughout recovery. A restartpoint run
by the checkpointer during recovery nevertheless tried to truncate it,
which failed the assertion:

    TRAP: failed Assert("TransactionIdIsValid(initial)")

Track whether pg_subtrans was started during recovery and have the
checkpointer check the flag before truncating. Skipping the truncation
unconditionally was rejected: it leaves stale pages in the common case
where pg_subtrans is running.

Reported-by: ...
Backpatch-through: 14
```

Slot 1 is the first paragraph, slot 2 the literal `TRAP:` line and the condition "from a backup
without a signal file", slot 3 the fix and the rejected shortcut, slot 5 the trailers. Slot 4 is
absent: the one outcome that changes is the assertion failure slot 2 quotes, which no longer
happens. R3 greps the `TRAP:` line; R5 reads `Backpatch-through:`; R2 gets the why without opening a tracker.

### Pull request title

One slot: **a searchable, accurate summary**. A squash merge turns it into the commit subject,
except under GitHub's default message for a single-commit pull request; a merge commit carries it
as its subject or its first body line, as the setting decides; a rebase and merge leaves it on the
platform (§4). Reader R1, then R2. Test: *R1, reading a list of thirty open pull requests, asks
which one this is.* Accuracy beats brevity here, because the title is read in a list and searched;
the commit subject's limit belongs to the subject.

The imperative form, the prefix, and the absent trailing period carry over from the commit subject;
the length limit does not. A pull request title has about 100 characters, and below that ceiling
accuracy wins: an 82-character line that names the component and the observable change beats a
60-character one that makes the reader open the diff. The 72-character rule belongs to the subject,
which `git log --oneline` shows and whatever quotes it truncates. Where a project squash-merges, the
title seeds that subject rather than becoming it: the merging maintainer edits the line and the
platform may append or strip the number (§4).

### Pull request description

Six slots, in order; the last four are conditional.

| # | Slot | Reader | Answers | Test |
| --- | --- | --- | --- | --- |
| 1 | **Why** | R1; R2 and R3 where the description becomes the commit body | The problem, the symptom, the condition that reaches it | *R1 asks: is there a problem worth fixing?* |
| 2 | **What** | R1, R2 | The behavioral change, not the changed files; why this approach; the rejected alternative a reviewer would raise | *R1 asks: is this the right behavior, and would I have done it differently?* |
| 3 | **User-visible changes** | R3, R4 | Every outcome the change adds or alters that a caller can observe, one line each: what the caller observes and the condition, plus the exact text and its error code or exception class where the outcome emits them. Breaks first: what worked before and now fails or differs, with the version it worked in and what the reader does about it; ahead of slot 1 unless the next release is a major (below) | *R3 asks: is this new text my incident?* *R4 asks: will my upgrade break, and what do I do?* |
| 4 | **Verification** | R1 only | Only what the diff and the CI result do not show: a manual check and its literal output; a test that passes without the change, or needs what CI lacks; a test removed or narrowed; which test guards which site, where the change has several; for a claim about the code that no test establishes, the command or file that checks it | *R1, holding the diff and green checks, asks: what am I still taking on trust?* |
| 5 | **Scope** | R1, R5 | What is deliberately left out; the follow-up by number; related or stacked pull requests, where they overlap, which depends on which; a question from the discussion that only a maintainer can settle, with the decision it needs and the evidence for it | *R1 asks: is this gap intentional?* *R5 asks: does this need another change first?* |
| 6 | **Release note** | R4, R3 | The user-facing sentence, or `NONE`, where the repository's tooling reads a block for it | *R4 asks: what changed for me?* |

The Why slot has no skip condition. A fix, a feature, and a refactor each replace a version that
worked for someone, and the reviewer's first question is why that version should change.

Slots 1 and 2 are the commit body's slots 1 to 3 written for a reader who has the diff open, and
slot 3 is the body's slot 4; where the merge model copies the description into the commit, they
*are* the commit body, and slot 3 is written there as `User-visible:` and `Not changed:` lines (§4).
Slot 4
serves the reviewer and no one else, who already has the diff and the CI result; where neither
leaves anything on trust, the slot is absent. Slot 5 answers the two questions a reviewer finds hardest to settle from the
diff alone, whether this breaks something elsewhere and whether other places need the same change;
a sentence in it is falsifiable when it names a number or a path, and "a follow-up will handle that"
is not. Slot 6 is project-specific: Kubernetes reads a fenced `release-note` block from its
template, Prometheus a `release-notes` block.

Slot 3 carries what the changelog entry's symptom and compatibility parts carry, written where the
reviewer and the on-call reader look before release notes exist. It is present whenever the change
adds or alters an outcome a caller can observe: a new error text, a changed SQLState or status code,
a value that is now refused, a different return value, a behavior that worked before and now fails.
A new error is an outcome even where nothing that worked before breaks; the slot is not a list of
breaks only.

Its lines are data, not prose: one line per distinct outcome, with what the caller observes and the
condition, and, for an outcome that emits a diagnostic, the text quoted as emitted and its error code
or exception class. Breaks come first and add the version the old behavior had and the action the
reader takes, such as the property to raise. Under rebase and merge the lines are taken from the
commits' `User-visible:` and `Not changed:` lines, deduplicated: one line per distinct outcome however
many commits produce it, so eleven commits yield the eight or ten outcomes they share, not forty
lines. A line is carried over as written, not summarized; a commit whose outcome lines are missing
is the place to fix, not the description. A change that alters nothing a caller observes has no
slot 3 and does not write an empty one. A fact stated in slot 3 is not restated in slot 2.

**A break leads the description unless the next release from the target branch is a major.** A
break changes what code that works on the released version gets: a call that succeeded and now
fails, a different return value or result shape (`getUpdateCount()` returning `-1` where it returned
a count), a removed or renamed option, a changed default, a changed error code for a failure a
caller may catch. A failure the change extends to inputs that worked before is a break for those
inputs, even though other inputs failed the same way already; it goes with the breaks, not in the
Scope slot. Whether the old behavior was right does not decide it, with one exception: where the
old behavior broke a contract the API documents (a second `Closeable.close()` that throws, a read
from a closed stream that returns data), the change is a fix, and its outcome is a line in slot 3,
not a lead. A failure that now succeeds is a fix, not a break.

In a release that is not a major, the reviewer's first decision is whether the change may ship in it
at all. A Why and a What read before that decision are wasted on a reviewer who decides no. Read the
next release from the target branch: its version file (`gradle.properties`, `pom.xml`,
`package.json`, `Cargo.toml`), its milestone, or the name of a maintenance branch (`release/1.4`,
`REL_17_STABLE`). Where a release tool bumps the major on a `BREAKING CHANGE:` trailer or a `!` (§5),
the trailer decides, and it is written whatever the description does. Where nothing says which
release it is, the breaks lead: a break read first in a major release costs a paragraph, and a break
missed in a patch release ships.

The description then opens with slot 3's break lines, under a `Breaking changes` heading where the
description has headings, after at most a short paragraph that says which code breaks. Slot 3 keeps
the other outcomes, and the Why slot follows the breaks. Where the next release is a major, the
breaks stay first in slot 3. A change with no break has neither the lead nor an empty heading (§7).

The commit body keeps its order, problem first, with the breaks as the first `User-visible:` lines of
slot 4: R2 opens the commit for the reason, and the label is what R4 and R5 search for. Where §4
rule 5 makes the description the commit body, the lead stays, because the reviewer reads the
description before it becomes a body; that body opens with its breaks and then its problem.

*Before:*

```text
## Summary
Updated the job controller.

## Testing
Ran the unit tests, all green.
```

*After:*

```text
When the pod GC force-deletes a completed job pod and the job is deleted
before the pod's finalizer is removed, the orphan sync fails with
`"syncing orphan pod failed" err="Timeout: request did not complete within
requested timeout"` and is never retried. The pod keeps its finalizer and
cannot be removed.

The orphan sync is requeued with backoff on failure instead of being
dropped. Removing the finalizer synchronously in the GC path was rejected:
the GC has no client for the job namespace.

Fixes #141346. The stale-finalizer metric proposed in #140900 is not part
of this change.
```

The *After* has no Verification paragraph: its new test is in the diff, and CI shows that it passes.

The shape of most small fixes, three short paragraphs and no headings, with the problem shown rather
than told and the outcome stated once:

````text
`Pool.acquire()` after `close()` handed out a connection instead of failing:

```java
pool.close();
pool.acquire();   // returned a live connection
```

`acquire()` on a closed pool now throws `IllegalStateException: Pool is closed`. A connection
handed out before `close()` stays usable until it is returned. Reopening the pool on demand was
rejected: `close()` already returned the sockets of connections in flight, and a reopened pool
would hand them out again.

Fixes #812.
````

Reviewer-only content is not a slot. Checklists, screenshots, the template's own comments, and
round-by-round history live in comments or in a collapsed block, and never in a description that a
squash or merge-commit setting will copy into the commit body (§4).

The heading names are house style. `Why`, `What`, `User-visible changes`, `How to verify`, and
`Scope` is one set that matches the slots; a template's `Summary` and `Testing` are another. Where the repository has a
template, use its headings and fill them with the slots' content.

Where the repository has no template, headings are the exception, not the default. A description of
up to four paragraphs, each plainly answering one slot's question, is prose without headings, and
that is the shape most changes take. A code snippet or a fenced command does not call for a
heading. Use headings where the description leads with breaks, or describes a related or stacked
pull request. Under §4 rule 5 the description is the commit body and takes the body's shape instead.

Without headings, slot 3 is not a section of its own: the What paragraph states each outcome once,
with its literal text, code, and condition, and no line repeats it under a label. A separate
`User-visible changes` list belongs to a description long enough to carry headings.

#### What each paragraph must earn

**Show it; do not retell it.** Where two to six lines show the problem, write those lines and no
prose that retells them: the call with its result in a comment, the old lines marked `// throws` and
`// never reached`, a command with its literal output. A literal (an input, a port, an error text, a
SQLState) is never traded for a description of it: `docker ps shows 25433->5433 and 25434->5434` is
evidence, *each replica got its own port* is a claim. The literal error text is also what the reader
holding the exception searches for, so it is quoted whole: a message cut short with `...` no longer
matches it.

**A reviewer decides, and does not re-investigate.** Carry what changes that decision: what was
wrong, why this behavior is the right one, what changed that the diff does not show, how it was
checked, and what scope or risk is left. The reader should be able to tell whether to engage and
where to look.

**Ask of every paragraph: which review decision becomes harder if this disappears?** Delete a
paragraph with no concrete decision. This is sharper than whether the reader needs the fact,
because almost any true fact can be argued to be needed. *Whether `n <= 0` is the right condition*
is a decision; *whether `Long.MIN_VALUE` needs handling of its own* is one; *that a sibling pull
request touches the same class* usually is. Six assertion messages and a parameter matrix are not.

**Write the result, not the route.** Once the problem and the chosen behavior stand, add a link only
where a reviewer would otherwise need it, and as the conclusion (severity, affected users,
reachability, compatibility, remaining scope): `Inside the driver the defect is latent, and the
class is public API` is the conclusion; the property name, the header arithmetic, and the `assert`
that guards them are the route. How the defect was found (an audit, a review, a sweep, a tool), who
reviewed the change, and what the author checked on the way are not written. A change no caller can
reach (an inconsistency inside a class that no path triggers) gets no sentence: the diff shows it,
and no review decision rests on it. A claim about code outside the diff earns a sentence only where
it answers a reviewer's question (is the same defect elsewhere? does any test cover this?), and then
as the conclusion with its check in the same sentence: `no test turns escape processing off; git
grep setEscapeProcessing over the tests finds nothing` backs the problem a reviewer decides on,
while `, which nothing else in the repository uses` answers no question and is cut. An aside that is
not about this change's own risk (how another server version behaves) is cut, not proven in the
text.

**Length follows the change.** A fix of a few lines takes one to three short paragraphs; a fix in
one class rarely needs more than about 250 words of prose. Past that, re-read the description
paragraph by paragraph under the decision question (measured: a fix in one class was described in
807 words, its maintainer called it verbose, and 420 kept every fact a reviewer decides on; ten
fixes of one to twenty-five lines took 81 to 443 words, and the same maintainers asked for less). The
usual surplus is the old code retold in the Why slot, the tests narrated, a passing run, a neighbor
that has landed, and a derivation whose conclusion is one sentence.

The count is of prose outside fenced blocks. Outcome lines in slot 3, the error texts and other
literals a reader searches for, a snippet that shows the problem, and a manual check's command and
output are outside it; cutting one loses a fact
one to one and is never how a description gets shorter.

**State each causal link once.** The Why slot carries the symptom and the condition that makes it
reachable. The What slot carries the new behavioral boundary, and each outcome once. Verification
carries only what the diff and CI do not show.

**Each artifact has a primary level of detail.** Repeat a lower-level fact only where the reviewer
needs it to understand or challenge the decision.

| Artifact | Its level |
| --- | --- |
| the diff | implementation detail |
| the tests | individual cases, fixtures, parameter matrices, failure output |
| the doc comment | the durable API contract |
| the commit message | why the change was needed, for whoever runs `git blame` years later |
| the changelog | the released, user-visible symptom |
| the pull request | the problem, the decision, the scope, and what the diff and CI do not show |

**In the What slot, name the behavioral change, not the artifacts that carry it.** `Javadoc records
the rule` and `changelog entry added` are changed files the diff already shows. A doc comment earns
a line only where the contract it states is itself under review, and even then do not quote its
wording, which is the part most likely to change before merge. A documentation section the change
adds or rewrites is summarized in a sentence and linked; its configuration table or list of setups
is reviewed in the diff, not copied into the description.

The same holds for the code, in every slot: a sentence whose content the reviewer recovers from one
hunk (`X is removed`, `Y now calls Z`, `the accessor asserts W`, `the first two tested a != null &&
a.length == 0`) is translation, even under a lead-in that states a reason, and so is a sentence that
restates the code comment or the changelog line the same diff adds. The Why slot is where the old
code gets retold; where the old lines show the defect, show them instead. A decision does not
exempt the sentence: state the reason for the decision, not the edit that carries it. A public
identifier the change adds, removes, or renames (a system property, a flag, an option) is the
exception: name it once, because readers search for the change by that string (§5).

*Before:* `With the pool opened at startup, the lazy ensureOpen() calls are removed,
QueryExecutor.execute now uses sendDirect instead of sendWithRetry, and Pool.acquire() checks that
the pool is open.`

*After:* `The pool opens at startup, so a caller that runs before startup completes fails at once
instead of opening the pool on demand. QueryExecutor.execute used sendWithRetry only to reopen a
closed pool; with the pool open from startup it sends directly.`

The removed `ensureOpen()` calls are gone from the text: the diff shows them, and the first
sentence says why they are no longer needed. The `IllegalStateException` that the early caller now
gets from `Pool.acquire()` is an outcome, stated once, with its text (slot 3).

**Keep a value where the decision depends on it.** A threshold, a version, an identifier, or a
magnitude stays when changing it would change the conclusion, the compatibility boundary, or what a
test discriminates. A value that is only test construction leaves with the sentence that carries
it: the detail and the claim resting on it leave together, or neither does.

**An approach you tried and rejected earns a collapsed block, when a reviewer would propose it.**
The What slot names the alternative a reviewer would raise in one sentence, with the mechanism that
rules it out, stated as a fact about the merged code. An obstacle the change itself removes is a step
the change had to take, not a reason against the alternative; check the mechanism against the code
after the change, above all when it is retold from someone else's discussion. An approach you
actually tried, with a measurement, goes in a `<details>` block with a one-line `<summary>`, after
the What slot and before verification. Each entry states a mechanism or a measurement, in one short
paragraph: *the watcher's consumer thread needs the same lock, so the event cannot arrive while the
build waits; measured, 6 timeouts in 6 runs*. An entry that reports the alternative as considered
and this one preferred is unfalsifiable and belongs nowhere, and so does every idea anyone had.

#### Verification

**The reviewer has the diff and the CI result.** Do not write what either shows: which tests exist
or are new, what they run, their parameters, that they fail without the change or pass with it, how
many pass, or the command that runs tests CI already runs (measured: passing runs narrated in two
pgjdbc descriptions of thirteen, and a `./gradlew` command in eight of ten). A new test that
fails without the change is the default, and the default is not stated.

The slot carries the exceptions, one sentence each, and is absent where there are none:

- a test that passes without the change: it pins behavior that already worked, or the side the
  change must not move (an escaped quote that must still not end the string);
- a test that needs what CI does not provide (a server version, a JVM flag, a manual step);
- a test that guards a future change (one that fails when a constant is added without being
  classified);
- a test removed or narrowed, with the coverage lost and the reason;
- which test guards which site, where the change alters several and the diff does not pair them,
  written as the pairing (`| Site | Guarded by |`), not as the mutation run or the search that found it;
- a manual check no test repeats: the command and its literal output, as run.

Name a test class only for one of these. A list of test classes followed by one clause about all of
them is the diff read aloud, and the clause is false for the first class that differs (measured:
drafts named 12 to 16 classes each, and two of three said `all drive a scripted socket` over a list
that included three tests that need a server; with this rule, 2 to 5).

**A claim about the code that neither a hunk nor a test establishes names how it was checked**: the
command, or the file that carries the fact, in the sentence that makes the claim. A docs-only or
configuration change is made of such claims (*every test under this directory is JUnit 5*), and a
reviewer who is not told how they were checked takes them on trust or repeats the search.
*Documentation only* names the kind of change, not what was checked. A claim no review decision
needs is not made at all, and then needs no check.

#### Scope

**A claim about neighboring work is a claim about the world now.** A description that calls a merge
trivial, simple, or mechanical without having looked has predicted rather than reported; two changes
that create the same path conflict over the whole file. Describe the overlap and leave the
difficulty to whoever resolves it. Where both are still open, or one is stacked on the other, name
the paths they share, because that decides merge order. Once one has landed, or a split is done,
the relationship is history: omit it (`which no longer carries this commit`, `this branch is rebased
on #4400`).

For related or stacked pull requests, describe the relationship as scope: one sentence on where they
overlap and whether any of them is a dependency. Do not summarize what each one changes, and do not
describe a neighbor's implementation or tests unless that changes how this one is reviewed. Where a
follow-up exists, name its number; where it does not, state what this change leaves out (`GSS
handling is outside this change`). `A separate pull request follows for that` is unfalsifiable when
written and stale once the number exists.

**A question the issue or the review left open is answered, kept open, or left out.** Where the code,
a test, or another source you verified answers it, give the answer and its evidence, and do not
repeat the question as open. Where only a maintainer can answer it, as with whether a compatibility
break is intended, keep it in the Scope slot as a question, with the decision it needs and the
evidence the maintainer needs to make it: the code shows the break, and not whether the break is
wanted. Leave
a question out only when it does not bear on this change (measured: three of three drafts repeated
as open a question the code answered).

### Changelog entry

Five parts in order, under a category heading. §3 argues the order.

| # | Part | Reader | Answers |
| --- | --- | --- | --- |
| 1 | **Category**: Added, Changed, Deprecated, Removed, Fixed, Security | R4 | Which kind of change is this? |
| 2 | **The observable change**, self-describing without the heading | R4 | What can I now do, or what now happens differently? |
| 3 | **The symptom and the trigger**, diagnostic quoted as a literal, setting or condition named; for a defect nobody has observed, the condition alone (§2, slot 2) | R3 | Can I grep this with my log line? Does the condition match my deployment? |
| 4 | **References**: pull request or commit, issue, CVE | R3, R2, R5 | Where is the detail? |
| 5 | **Compatibility and action**: breaking or not, what the reader must do, and for a regression the version that introduced it | R4, R5, R3 | Will my upgrade break? Am I on an affected version? |

Each entry must be self-describing as if no category heading existed, because dependency bots
quote entries out of context. `json-parser 8.0.2 is fixed (#295)` is the antipattern: it names a
ticket and explains nothing. The introducing version in part 5 is written only where a `Fixes:`
trailer or a bisect established it, never guessed.

*Before:*

```text
### Fixed
- Bug fixes and performance improvements.
- Fixed #19016.
```

*After:*

```text
### Fixed
- Set a request timeout for `docker_sd` and `dockerswarm_sd`. Previously an
  unresponsive daemon could freeze discovery indefinitely, silently pinning
  targets to a stale snapshot. #19237
- Fix silent data loss and a crash loop when `stale_series_compaction_threshold`
  is set in the config file. Regression since 3.12.0. #19016
```

## 3. The changelog entry is the on-call reader's artifact too

The upgrading user and the on-call engineer open the same file with different questions, and most
changelog guidance writes for the first. One entry serves both if its parts are ordered so that each
reader stops where their question is answered: the observable change first, because R4 stops there
for a feature; the symptom and trigger second, because R3 greps for it and R4 skips one clause; the
references third; compatibility last, because R4 and R5 read it and R3 reads the version.

The order is a decision, and its argument is cost: the symptom in second place costs R4 one clause,
while the symptom omitted costs R3 the whole entry, because nothing in it matches their search.

**Breaking changes are marked in place and sorted first within their category**, with a
`**Breaking:**` prefix, so that anyone scanning Changed or Removed sees them where they are. A
separate top block is a house option for release notes long enough that R4 cannot scan every
category. **A Security entry leads with its CVE identifier** where one exists, because R3 and
security tooling match on it.

**Quote the diagnostic as a literal**, everywhere it appears: exception class, error text, error
code, log line, exactly as emitted, punctuation included. Under-reporting the detail means the
reader cannot make the match later; over-reporting buries the distinguishing detail. The entry names
the public identifier the reader will grep, spelled as the code spells it, and both versions where
the project versions its releases: the one that introduced the defect and the one that fixes it.

## 4. The merge model decides where the text ends up

The merge model changes three things: which line becomes the subject of the permanent commit, what
becomes its body, and which of the branch commits survive as commits of their own. Detect it before
applying anything in §5 or §6.

Count the last forty commits on the target branch by shape. Read the branch the pull request
merges into, first-parent only, so that neither the branch's own unmerged commits nor the commits
behind a merge commit enter the count. The second line takes the name of that branch from the pull
request; before the pull request exists, it takes the remote's default branch, the usual target and
the best guess at the merge setting, though a ruleset can allow other methods on another branch.

```bash
remote=origin
target=$(gh pr view --json baseRefName --jq .baseRefName 2>/dev/null) && target=$remote/$target || target=$(git rev-parse --abbrev-ref "$remote/HEAD")
git log -n 40 --first-parent --format='%P%x09%s' "$target" -- | awk -F'\t' '$1 ~ / / {m++; next} $2 ~ / \(#[0-9]+\)$/ {s++; next} {p++} END {if (NR == 0) {print "no commits read: the target branch is not on the remote"; exit 1} printf "merge %d squash %d plain %d\n", m, s, p}'
```

`remote` is the remote of the repository the pull request merges into: `origin` in a clone of that
repository, usually `upstream` where `origin` is a fork, whose copy of the branch may be stale. Set
it, and type nothing else into the lines; in particular, never a branch name. Whoever creates a
branch chooses its name, and git accepts names that contain `'`, `;`, or `$(`: typed into the
command, even between quotes, such a name can close the quote and run the rest as shell. Expanded
from the variable, the name reaches git as one argument and is never parsed as shell, and the `--`
after it makes git read it as a revision even where a file of the same name exists. Where
`$remote/HEAD` is missing, `git remote set-head "$remote" --auto` sets it; where the count reports
no commits, fetch the remote.

The squash settings, and the merge commit settings rule 5 depends on, differ only in the body. In
the same shell, `git log -n 10 --first-parent --format='%h %P%n%b%n' "$target" --` prints the bodies
of the latest ones.

A commit with two parents is a merge whatever its subject says; the parent count is what tells a
merge commit under GitHub's "pull request title" setting from a squash, since both end in `(#n)`.

| Shapes in the count | Model |
| --- | --- |
| `squash` only | Squash merge; a body of `* subject` bullets under one of them means the default setting or "title and commit details", no body at all means "pull request title", a body shaped like a description means "title and description", unless the pull request had one commit, whose message the first two settings copy as well |
| `plain` only | Rebase and merge, or a repository pushed to directly; the shape is the same and the consequences are too. On GitLab, also a squash under the default template, which appends no number, and only a house rule tells that apart; without one, the default at the end of this section |
| `squash` and `plain` both | Ambiguous: squash and rebase both allowed and chosen per pull request (below), or squash beside direct pushes; without a house rule, the default at the end of this section |
| `merge` present | Merge commits are enabled; a `squash` or `plain` line beside them is another method also enabled, or a direct push |

The shapes are evidence of what the maintainers did, not of what the repository setting allows: a
maintainer can type `(#n)` into a rebased subject or strip it from a squash, and a direct push
looks like a rebase. A `CONTRIBUTING.md` or `AGENTS.md` line that names the method wins over the
count; `references/merge-model.md` has the lines.

| Model | Subject of the permanent commit | Body of the permanent commit | Branch commits that survive |
| --- | --- | --- | --- |
| Squash, default message | For a single-commit pull request, that commit's subject, number appended; otherwise the title, number appended | That commit's body; otherwise the branch commit messages as a bulleted list | None, and every message lands as a bullet, `Fix lint` included |
| Squash, "pull request title" | The pull request title, number appended | Nothing | None, and no message reaches `git log` |
| Squash, "title and commit details" | The pull request title, number appended, for one commit or many | That commit's message for one commit; the branch commit messages as a bulleted list for several | None, and every message lands as a bullet, `Fix lint` included |
| Squash, "title and description" | The pull request title, number appended | The description | None |
| Merge commit | Each branch commit keeps its own; the merge commit's subject is `Merge pull request #n from …` under the default message, with the title as its body, and the title with the number appended under "pull request title" and "pull request title and description" | Each branch commit keeps its own; the merge commit's body is the description under "pull request title and description" and nothing under "pull request title" | Every one, behind a merge commit that a `--first-parent` reader sees instead |
| Rebase and merge | Each branch commit keeps its own; nothing is appended | Each branch commit keeps its own; the description is never copied | Every one with changes, as a separate commit that `git bisect` can stop on; GitHub rewrites the SHA and the committer, keeps the message, and drops a commit that was empty |

GitHub's default squash setting is the first row, and GitHub lets a repository allow several
methods at once, with the merging maintainer choosing one per pull request. GitLab's default squash
template is the title alone, and a project can compose the description, the first commit, all
commits, and the closing issues. The merging maintainer can edit the message before merging on both.

1. Every commit that will survive is a full commit message (§2). Under rebase and merge, and under
   a merge commit, that is every commit on the branch; under the default squash and under "title
   and commit details" it is the first, since the messages land as bullets; under "title and
   description" it is none, and the description takes the role; under "pull request title" it is
   none, and the title is all that lands.
2. Under rebase and merge, and under merge commits, tidy the branch before review. A `Fix lint` or
   `Apply suggestion` commit lands on the default branch as a commit of its own, so squash it into
   the commit it corrects before the merge. Under the default squash the same commit lands as a
   bullet in the body.
3. Under rebase and merge, the platform appends nothing. Where an issue exists, put its number in
   the commit body, as `Fixes #n` or in prose, or `git log` will never link the commit to its
   discussion. The issue number is known before the pull request exists; the pull request number
   reaches a commit only by amending after the pull request is opened, so the issue is the
   reference to rely on. Without an issue the body carries the problem itself, and no reference is
   invented for it.
4. Write the title as the commit subject it may become: under "pull request title", "title and
   commit details", and "title and description" it is the subject of every squash, under the
   default message it is the subject of a multi-commit squash, under a merge commit it is the
   subject or the first body line as the setting decides, and under rebase and merge it is the
   line the reviewer picks from a list. A prefix a release tool reads (rule 7) goes on the title
   for the same reason.
5. Under a squash or a merge commit with "title and description", the description is the body of
   the commit a `--first-parent` reader sees: shape it as §2's commit body, keep reviewer-only
   content out of it, and keep verification to at most one line of what the diff and CI do not
   show (§2), with transcripts and checklists moved to comments. Under every other setting the description stays
   on the platform, and a template's checklist may stay in it. Which merge commit setting a
   repository uses shows in the body of any merge commit in the count.
6. `Fixes #n` and `Closes #n` close the issue only when the change merges into the default branch;
   on any other target the keywords are ignored, so a backport carries the reference for humans.
7. A release tool reads either the commits on the default branch (release-please, semantic-release:
   the squash subject, or under rebase and merge every commit) or the pull request titles and labels
   (release-drafter, GitHub's generated release notes). Find out which tool the repository runs, and
   put the prefix or the label where that tool reads it.

**Where squash and rebase are both allowed**, the maintainer picks one per pull request, and the
author cannot tell in advance. Write for both: every commit self-standing with its number (rules 1
to 3) and the title as a subject (rule 4). Rule 5 applies only if the squash setting turns out to
be "title and description"; under the default it does not.

What rule 1 prevents, from a squash under "title and commit details" of a pull request whose
description ran to five paragraphs of mechanism and measurements:

```text
fix(cache): re-add cacache.verify() to garbage collect orphaned content (#44987)

* fix(cache): re-add cacache.verify() to garbage collect orphaned content

* Fix lint

* Apply suggestion from @reviewer

* Apply suggestion from @reviewer
```

That is all `git blame` will ever show. Under rebase and merge the same pull request lands as four
commits, three of them `Fix lint` and `Apply suggestion from @reviewer`, and under merge commits
the four sit behind a merge commit whose body, under GitHub's default message, is the title and
nothing more; in each case the description is never copied, so the commit messages carry problem,
impact, and approach whether or not the description repeats them, because the description is
reachable only while the platform is.

**Default when detection fails**: write every branch commit as a full commit message, with the
issue number in its body where an issue exists, and write the description so it can stand as a
commit body. This is what the strictest rows above demand at once; it costs one paragraph of
duplication when the repository turns out to merge or rebase, and loses nothing in every other
case.

**Verification does not belong in `git log`.** Email workflows strip it below `---`; pull request
templates ask for it in the description; a squash setting can copy it into the body. R1's need is
met in the pull request; R2's noise is kept out of the history. Where the description will become
the body, rule 5 applies.

Where every commit says `See #n` and none says `Fixes #n`, the description says in one sentence why
the issue stays open; the trailer choice was a decision and the reader should not have to infer it.

## 5. Trailers and the identifiers a reader greps

Trailers are a block of `Key: value` lines at the end of the message, after a blank line, with no
blank lines inside; keys are ASCII alphanumerics and hyphens. Each one has a consumer, and the
consumer decides whether it is worth writing.

| Trailer | Consumer | Reader | Write it when |
| --- | --- | --- | --- |
| `Fixes: <12+ hex sha> ("<subject>")` | Kernel stable scripts, to pick the branches that need the fix | R5, R3 | The project uses it; elsewhere it is prose only R5 reads, and still worth a line for a regression |
| `Fixes #n`, `Closes #n`, `Resolves #n` | GitHub and GitLab: link, and close on merge to the default branch | R2, R5 | An issue exists |
| `Closes: <url>` (kernel) | Humans and regression trackers; a public URL is required | R2, R3 | The project uses it |
| `Link:` | Humans | R2 | A discussion or archive exists |
| `Backpatch-through: <major>` | PostgreSQL's release-note tooling, which groups identical messages across branches | R5 | The project back-patches and uses it |
| `Cc: stable@vger.kernel.org # <ver>: <sha>: <subject>` | Kernel stable team; the sequence means `git cherry-pick <sha>` then this commit | R5 | Kernel only |
| `Reported-by:` | Credit | R2 | Someone reported it |
| `BREAKING CHANGE:`, or `!` after the type | release-please and semantic-release: major bump | R4, through the tool | The tool is present |
| `Co-authored-by:` | Platform attribution | none of the five | Provenance requires it |
| `Change-Id:`, `Signed-off-by:` | Gerrit; the DCO | none of the five | The workflow requires it |

**Identifiers.** Beyond the diagnostic and the versions of §3, these decide what a search finds.

- The public identifier the change touches: option, flag, class, method, configuration key, spelled
  as the code spells it. Future readers search the history by these strings. A private helper is
  named only where its name is what the reader will grep for.
- The issue identifier, beside the one-line why, never instead of it (§2).
- A section of a document in the repository, with its heading text at the first mention:
  `TESTING.md §4 Running the test suite` where the headings are numbered, or `the "Running the test
  suite" section of TESTING.md`; later mentions may say `§4` or `that section`. The number changes
  with the next inserted section and the description outlives it; the heading text is what the
  reader searches the file for. A published specification is cited by its own number, `RFC 9110
  §15.5.1`, `JLS §5.1.2`: the numbering is frozen with the document, and the number is what its
  readers search for.
- A link, in a pull request or issue description, to what the diff does not show. The diff shows
  changed lines as source, so a link is owed at the first mention of the rendered section a docs
  change edits, of code outside the diff, of a specification, of another project's documentation,
  and of a pull request or issue by number. Not of text inside the diff: the reviewer has it open.
  A specification links by its own anchor (`https://www.rfc-editor.org/rfc/rfc9110#section-15.5.1`,
  the JLS or PostgreSQL page with the release in its path). A file in the repository links by a
  permalink, `https://github.com/<owner>/<repo>/blob/<sha>/<path>`, with `#L<from>-L<to>` for code
  and the heading anchor for rendered Markdown (the heading lowercased, punctuation other than
  hyphens dropped, spaces turned into hyphens: `## 5 - Extending the test suite with new tests` is
  `#5---extending-the-test-suite-with-new-tests`). `<owner>/<repo>` is the repository the file lives
  in, and `<sha>` a tag or a full commit hash that repository has. For another repository, that is
  the tag or commit whose code the sentence is about. For the repository the pull request targets,
  the commit follows from whether the pull request changes what the link shows. Content the pull
  request leaves unchanged links at `git merge-base` with the target branch, which stays fixed while
  the branch is reworked. Content it adds or changes, such as the rendered section a docs change
  edits, is not there in that form at the merge base and links at the head commit of the pull
  request, still under the target repository's `<owner>/<repo>` when the branch lives in a fork,
  since GitHub serves a fork's commits there. A later push that changes the linked content leaves
  the link on a superseded version, which GitHub may also garbage-collect once a force-push leaves
  it unreachable, so rebuild those links from the new head after each such push; GitHub keeps the
  last head of a pull request under `refs/pull/<n>/head` after the merge. A branch name is never the
  `<sha>`: it moves with every push and is gone once the branch is deleted. Code in another
  repository is always a full permalink, never a bare path. The link text is the citation itself
  (`TESTING.md §5 Extending the test suite with new tests`,
  `V3ReplicationProtocol.configureSocketTimeout`), never `here`; one link per identifier, at its
  first mention. Code in a JDK or another project is named with its version and linked only where
  the URL was opened, and no link is written whose URL was neither built from one of these patterns
  nor opened, as `english-developer-style` §7 rules out an invented issue or pull request number.
- In a commit body, or in a description that a squash of "title and description" copies into one
  (§4 rule 5), code of this repository is named by path and identifier and carries no URL: the
  reader has `git show` and `git blame`. A specification, another project's documentation, or
  code in another repository keeps its full URL, moved out of the sentence into a `Link:` trailer,
  one per line, or into a `[1]` footnote above the trailers where the project writes them; the
  citation text stays in the sentence. Each footnote is referenced once in the body and each
  reference has its footnote.
- Identical messages across branches when back-patching, where tooling groups them; where the
  branches diverge in behavior, one branch-specific line beats a misdescription.

## 6. House style, gated on the workflow

These prescriptions serve a tool or a process, not one of the five readers. Apply each only where
the detected workflow consumes it; a repository's `CONTRIBUTING.md`, commitlint configuration, or
existing history shows which.

| Prescription | Serves | Apply when |
| --- | --- | --- |
| `type(scope):` prefix | release-please, semantic-release, commitlint | The history already carries prefixes or a config names the tool. Where it does, the prefix is mandatory and the imperative is the first word after it. The prefix gives authors the impression that the message is descriptive; the body still owes the why |
| 50- or 72-character subject | Terminal and email display | The project states a limit; it is a linter's check |
| `Signed-off-by:` | The DCO | The project enforces it |
| `Change-Id:` | Gerrit | The history shows it |
| `Discussion:` URL | PostgreSQL's mailing-list archive | PostgreSQL |
| `/kind`, `/release-note-none`, fenced `release-note` block | Prow | The pull request template has them |
| One news-fragment file per change | towncrier's merge-conflict avoidance | A `newsfragments/` or `changes/` directory exists |
| An `Unreleased` section | Entries written in the pull request | Keep it where entries are written in the pull request; drop it where they are curated at release. Default: keep |
| Content below `---` in the message | `git am` | Email patches only; in a pull request the separator is inert text and everything below it lands in the body |

The principle behind each transfers when the mechanism does not: mark fix versus feature where R5
reads it (a label, a category, or a prefix); name backport prerequisites where R5 looks (the scope
slot, a label); keep the user-facing sentence separate from the reviewer-facing description.

## 7. When to write nothing

- No changelog entry for maintenance noise: dotfiles, development-only dependencies, formatting,
  CI. Where the tooling demands a line, `NONE` is the line.
- No section for an absent concern. A description with no breaking change, no follow-up, and no
  rejected alternative has no such sections; an empty heading is not a gap to fill. A claim about
  the code that the diff does not show still names its check (§2).
- No tool-attribution line (`Generated with …`): it answers none of the five readers.
- No verification narrative in the commit body (§4).
- No paraphrase of the diff. A body whose sentences map one to one onto hunks adds no rationale;
  R2 has the diff. In a description, the same test applies sentence by sentence, in every slot
  (§2).

## 7a. Editing an existing description

The "which review decision becomes harder" test and the rule that each causal link is stated once
(§2), and the literal treatment of a quoted diagnostic (§3, and `english-developer-style` §5), apply
here unchanged. The growth budget is the authoring skills' shared rule: **restructuring is free, and growth
is paid for by a fact the old version did not carry** (a symptom, a condition, a version, a measured
value, a dependency between changes); name the fact, or keep the old wording. A description already
within §2's length for its change is presumed not to grow. Prefer cutting to rewording: where
the old text is right but carries noise, cut the noise and leave the rest as written, and never
trade a literal for a description of it. Two rules are this skill's own:

- **A released changelog entry is not yours to reword.** It has been read, quoted, and linked. Fix a
  wrong version or a wrong CVE and leave the wording.
- **A rewrite that moves a fact between artifacts says where it went.** A symptom cut from the
  description because the changelog entry now carries it is a move; the same cut with no entry is a
  loss.

## 7b. Comparing two versions

Read the old version first and reach the verdict last; the new version was written second and reads
better for it, and a fact that vanished leaves no trace in the text that replaced it.

1. List the old version's facts before you read the new one: a symptom, a condition, an identifier,
   a version, a measured value, a named test, a dependency between changes, a stated non-goal. A
   topic sentence is not a fact.
2. Mark each fact present, restated, or absent, and defend every absence in words. *The new wording
   covers it* is not a defense, and neither is *the diff implies it*, except for what §2 leaves to
   the diff and CI: a test's name, its construction, that it is new, that it fails without the
   change, and how many tests pass.
3. Only now count the delta, under §7a's budget.
4. Check what the new version asserts against the diff and the tests, not against the old text; the
   old text is provenance, not evidence.
5. Name the finding: lost fact, unpaid growth, unsupported claim, diff paraphrased, symptom dropped,
   diagnostic retyped, verification in the permanent body, reviewer content in a body the squash will
   copy, issue link standing in for the why, edited released entry, invented identifier.

## 8. Review checklist

- Reader: for every sentence, which of R1 to R5 asks the question it answers (§1)?
- Subject: does it name the change, and would it identify the commit in `git log --oneline` (§2)?
- Problem: does the body open with what was wrong, before the fix, or right after the breaks where
  §4 rule 5 copies a description that leads with them (§2)?
- Symptom: is the diagnostic quoted as a literal, with the trigger condition (§2, §3)? Is every
  symptom quoted from an observation, or traced in the code and marked as derived? For a defect
  nobody has observed, does the slot end at the condition, with no word on what found it? For a
  defect with no symptom by construction, is that stated?
- Why: is the slot present, and is there a sentence of reason beside the issue link, not only the
  link (§2)?
- Approach: is the alternative a reviewer would propose answered with a mechanism or a number (§2)?
- Verification: does the slot carry only what the diff and CI do not show (a test that passes
  without the change, a precondition CI lacks, a removed test, a test-to-site pairing, a manual check
  with its literal output), and is none of it in the commit body (§2, §4)? Is any test listed,
  marked new, said to fail without the change, counted, or given a command CI already runs? Does each
  claim about the code outside the diff name its check?
- Caller: where the change adds or alters an outcome a caller can observe, does the description
  state each distinct outcome once, as a line under "User-visible changes" where it has headings or
  in the What paragraph where it has none, breaks first, and does each
  commit that adds one carry it under `User-visible:` (§2)? Does a line carry the exact text and its
  error code or exception class only where the outcome emits them? Is a deliberate non-change a
  reader would expect stated under `Not changed:`?
- Breaks: where the change breaks code that works on the released version and the next release
  from the target branch is not a major, or is unknown, does the description open with the break
  lines, and does slot 3 keep only the other outcomes (§2)? Where nothing breaks, is there no lead
  and no empty heading?
- Headings: absent unless the description leads with breaks or describes a related or stacked
  change, and absent from a description that §4 rule 5 makes a commit body (§2)?
- Shown: where a few lines of code, a command and its output, or an error text show the problem, are
  they there in place of prose that retells them, and is no literal traded for a description (§2)?
- Route: is no finder, reviewer, tool, empty search, or aside left, and does every claim about code
  outside the diff answer a reviewer's question (§2)?
- Decision: for every paragraph, which review decision becomes harder without it (§2)?
- Translation: delete each sentence in turn, in the Why slot as in the What slot; where the diff, a
  code comment it adds, or its changelog line still says it, leave it deleted (§2).
- Length: is a fix of a few lines one to three short paragraphs, and was a description past about
  250 words of prose re-read under the decision question (§2)? Were the outcome lines and literals
  left out of the count, and left in the description?
- Alternatives: is an approach a reviewer would propose answered in one sentence, and a tried one in
  a collapsed block with a mechanism or a measurement (§2)? Is that mechanism a fact about the
  merged code, and not an obstacle the change itself removes?
- Discussion: where verified evidence settles a question from the issue or the review, does the
  description give the answer rather than repeat the question as open? Is a question that only a
  maintainer can settle kept in the Scope slot, with the decision it needs and the evidence for it?
  Is a question dropped only when it does not bear on this change (§2)?
- Title: does it name the component and the observable change, within about 100 characters (§2)?
- Scope: is every gap named as intentional, every follow-up by number, every stacked change by its
  overlap and order (§2)?
- Changelog: category, observable change, symptom, reference, compatibility, in that order; breaking
  marked in place and first; CVE leading a Security entry (§2, §3)?
- Merge model: read from the history; every commit that survives the merge written as a full body,
  with the issue number where an issue exists; no `Fix lint` commit left to land on its own under
  rebase and merge (§4)?
- Trailers: each one has a consumer in this repository, and the closing keyword targets the default
  branch (§5)?
- Open issue: where every commit says `See #n` and none says `Fixes #n`, does the description say in
  one sentence why the issue stays open (§4)?
- Identifiers: does every issue, pull request, commit, and version number exist, or stand as a visible
  placeholder (`english-developer-style` §7)? Does the first mention of a section of a repository
  document carry its heading text (§5)? Links: does each first mention of a rendered section, code
  outside the diff, a specification, or another project's documentation carry a link built from a
  known pattern, at the merge-base commit for content the pull request leaves unchanged and at its
  head for content it adds or changes, rebuilt after a push that changes it, never at a branch name,
  none to text inside the diff, and in a commit body or under §4 rule 5 none to this repository's
  code and the external ones only as a `Link:` trailer or a footnote (§5)?
- House style: prefix, limit, sign-off applied only where the workflow consumes them (§6)?
- Nothing: no entry for noise, no empty section, no diff paraphrase, no predicted merge difficulty
  (§7)?
- Rewrite: net delta defended, causal links stated once, released entries untouched (§7a)?
