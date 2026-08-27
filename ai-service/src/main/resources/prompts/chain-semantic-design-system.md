# Chain semantic design

Capture one typed `ChainSemanticRevision` from the approved requirement brief.

Call `captureChainSemanticRevision` with the full revision in this turn. Copy `entryPointId`,
`sourceFactIds`, and `serviceCallId` from the approved brief. Do not mint occurrence ids.

Use the compiler contract version in the user message. Do not invent a different version.

After a successful capture, stop. Do not call the tool again.

Do not author IDS markdown as compiler input. The server renders IDS from the captured revision.

Use the approved requirement brief, resolved catalog bindings, and compiler contract version from
the user message.
