---
description: Build conventions for the repository's Go module.
applyTo: "**"
---

## The Go module `testing-service/`

`testing-service/` is a Go module, so the repository's Maven and npm commands do not
cover it: build and test it with the Go toolchain from inside that directory
(`go build ./...`, `go test ./...`), keep the `go` directive at 1.22, and read
`testing-service/AGENTS.md` before changing anything under it.

`testing-service/AGENTS.md` is the one exception to the rule that an `AGENTS.md` is
generated and must not be hand-edited. No APM primitive targets `testing-service/**`,
so `apm compile` never writes that file: it is maintained by hand, and you edit it
directly.
