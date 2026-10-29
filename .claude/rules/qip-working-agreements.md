---
paths:
  - "**"
---

Add a defensive mechanism — an advisory lock, a cache, a size limit, a null guard, a data
backfill — only when a real failure motivates it: a reported incident, a failing test, or a
case the user named. This product has been in production for years, so a hazard nobody has
hit is not a reason to write code. Reread the diff before handing work back and drop
whatever guards a hypothetical, and with it any file, doc section, config value, or field
the task did not ask for. Reviewers have repeatedly had to ask why such a file was there.

A defect needs a product path that produces it: a UI form, the product's own export and
import, a real deployment. A state made by a hand-written API call, such as a `PATCH` that
stores the string `"false"` where the form writes a boolean, is not a defect on its own.
Look for a real producer first; if there is none, report the case as reachable only through
a hand-made call and let the user decide whether to file or fix it.

Present findings and stop. When a review, an investigation, or a batch of agents turns up
problems, make no edit until the user picks which ones to fix — some are deliberate
decisions, and some are worth reverting rather than patching. The `fix-issue` pipeline and
`/planning:exec` are the exceptions: they act on their own review findings.

Write a comment only for a reason the code cannot show, and keep it to one line where
possible. Add a Javadoc or a docstring only where a reader would get something wrong
without it: a non-obvious invariant, an edge case, a side effect. A comment that restates
the name or the signature, a numbered step marker, and a banner over a workflow job are
noise.

Edit an unpushed migration in place. A Flyway or import-file migration that exists only on
an unpushed branch is still editable, so keep the branch down to one new migration instead
of layering a second one on top. Never add compatibility code between two migrations that
no deployment has ever run.

New PostgreSQL columns use `timestamptz` rather than `timestamp`, `text` rather than `varchar`,
and `bytea` rather than a Large Object. The reasoning is in
`infrastructure/docs/adr/0001-guideline-on-postgresql-types.md`.

The repository is Apache 2.0. Existing sources carry the NetCracker copyright header; a file
you create does not need it.
