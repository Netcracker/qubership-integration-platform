---
name: update-docs
description: >
  Document a feature tied to a GitHub issue end to end: read the issue, diff
  the branch, update the affected pages under help/docs/, and leave the edits
  unstaged for review. Use only when the user asks to "document issue #N",
  "update the docs for this feature", or invokes this skill by name with an
  issue number. Do not auto-fire on unrelated turns.
---

# Update docs for a feature

This is the deliberate, run-by-name workflow for documenting a feature. It
gathers the context — the issue and the code diff — then applies the
`docs-authoring` skill for the doc mapping, style, and task flow.
That skill is the single source of truth for *how* to write the pages; this
one is the *invocation*, so don't restate its rules here.

Take a GitHub issue number as input. If the user didn't give one, ask for it
(or for the branch to diff) before editing anything.

## Steps

1. **Read the issue in full.** Run `gh issue view <issue-number>`. The issue
   is the source of truth for *why* the feature exists and how it behaves —
   edge cases the diff alone won't show.
2. **Review the change.** Run `git status --short` and
   `git diff origin/main...HEAD` to see which UI areas and elements changed.
3. **Apply `docs-authoring`.** Use its code-to-docs mapping to find
   the affected page(s), follow its `help/docs/` style rules, and work
   through its task flow. Search `help/docs/` for the closest match if a
   changed path isn't in the mapping; ask before creating a new page or
   section folder.
4. **Verify against the source.** Every parameter, label, route, and sample
   you write must match the diff and the issue — don't document planned
   behavior as shipped.

## Constraints

- **Edit files only.** Do not run `git add`, `git commit`, `git push`, or
  open a pull request unless the prompt explicitly asks. Leave the doc edits
  as unstaged working-tree changes for a human to review and commit.
- **End with a plain-text summary** of which files you changed and why, so it
  can be reviewed before committing. Do not write that summary into any file.
