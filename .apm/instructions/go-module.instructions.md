---
description: Build conventions for the repository's Go module.
applyTo: "**"
---

`testing-service/` is a Go module, so the repository's Maven and npm commands do not
cover it: build and test it with the Go toolchain from inside that directory
(`go build ./...`, `go test ./...`), keep the `go` directive at 1.22, and read
`testing-service/AGENTS.md` before changing anything under it.
