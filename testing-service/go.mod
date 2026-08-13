module github.com/Netcracker/qubership-integration-platform/testing-service

// Downstream builds this module with GOTOOLCHAIN=local on a 1.22 toolchain, so
// neither this directive nor any dependency may require a newer Go.
go 1.22

require (
	github.com/stretchr/testify v1.10.0
	github.com/uptrace/bun v1.2.1 // held back: v1.2.18 declares go 1.24
)

require (
	github.com/davecgh/go-spew v1.1.1 // indirect
	github.com/jinzhu/inflection v1.0.0 // indirect
	github.com/kr/text v0.2.0 // indirect
	github.com/pmezard/go-difflib v1.0.0 // indirect
	github.com/tmthrgd/go-hex v0.0.0-20190904060850-447a3041c3bc // indirect
	github.com/vmihailenco/msgpack/v5 v5.4.1 // indirect
	github.com/vmihailenco/tagparser/v2 v2.0.0 // indirect
	golang.org/x/sys v0.18.0 // indirect
	gopkg.in/yaml.v3 v3.0.1 // indirect
)
