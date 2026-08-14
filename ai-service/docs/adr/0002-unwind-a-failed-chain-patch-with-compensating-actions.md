# Unwind a failed chain patch with compensating actions, not a snapshot revert

`ChainPatchWriter` applies a patch to a live catalog chain in five steps: create elements, write
properties, create connections, delete connections, delete elements. Any of them can fail on its
own, and until now a patch that failed partway was reported and left where it fell. The chain was
then in a state nobody chose: half of a change the reader approved as a whole.

The obvious fix is to take a snapshot before the write and revert to it on failure. Two facts,
both verified against a running catalog, rule that out as an automatic step:

- `POST /v1/catalog/chains/{id}/snapshots` answers 400 on a chain that fails property verification.
  An `http-trigger` with no `contextPath` is enough. That is exactly the mid-edit chain the semantic
  validator tolerates on purpose, so the snapshot would fail on the chains that most need it.
- Building a snapshot overwrites `currentSnapshot`, and deleting the temporary snapshot afterwards
  sets that pointer to NULL rather than restoring the one it replaced. A patch that took a snapshot
  and cleaned up after itself would quietly cost the chain its release pointer.

Snapshots do restore a deleted element under its original id — `ChainElement.copy()` records
`originalId`, and `SnapshotService.revertElements` writes it back — so the mechanism works. It is
the cost of taking one around every patch that does not.

Decided: the writer compensates for its own steps, in reverse order, using the same materializers
it wrote through. A created element is deleted by the id the skeleton materializer returned; a
created connection is resolved back to its dependency id and deleted; a deleted connection is drawn
again; a property is re-PATCHed with the value `PatchedChain.before()` still holds. Every catalog
call goes through a materializer, so the writer stays the one place that knows which step failed
without becoming a second REST client.

Two things cannot be compensated, and `ChainPatchWriteResult.RollbackOutcome` names both rather
than papering over them. A property key the patch introduced stays on the element, because the
properties merge never deletes a key — that write reports `PARTIAL`. A deleted element does not
come back, so the writer reports `REFUSED` and names what is gone; recreating an element under the
old name would read as the chain being whole when it is not.

Element deletion is the last step for this reason, and it is one atomic bulk call. The point of no
return is a line, not a smear: every step that can be taken back has been taken by the time the
first element is deleted.

Offering a restore point stays the reader's decision. `ChainSnapshotTool` lets the model save a
snapshot when asked, and the decision card warns that removing cannot be undone.
