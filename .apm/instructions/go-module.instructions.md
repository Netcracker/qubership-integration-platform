---
description: Build conventions for the repository's Go module.
applyTo: "**"
---

## The Go module `testing-service/`

`testing-service/` is a Go module, so the repository's Maven and npm commands do not
cover it: build and test it with the Go toolchain from inside that directory
(`go build ./...`, `go test ./...`), keep the `go` directive at 1.22, and read
`testing-service/AGENTS.md` before changing anything under it.

`testing-service/AGENTS.md`, `engine/AGENTS.md` and `micro-engine/AGENTS.md` are the
exceptions to the rule that an `AGENTS.md` is generated and must not be hand-edited.
No APM primitive targets those three directories, so `apm compile` never writes those
files: they are maintained by hand, and you edit them directly.
