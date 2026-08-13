module github.com/Netcracker/qubership-integration-platform/testing-service

// Downstream builds this module with GOTOOLCHAIN=local on a 1.22 toolchain, so
// neither this directive nor any dependency may require a newer Go.
go 1.22

require (
	github.com/PaesslerAG/jsonpath v0.1.1
	github.com/google/uuid v1.6.0
	github.com/santhosh-tekuri/jsonschema/v6 v6.0.1
	github.com/stretchr/testify v1.10.0
	github.com/uptrace/bun v1.2.1 // held back: v1.2.18 declares go 1.24
	github.com/wI2L/jsondiff v0.6.0
)

require (
	github.com/gofiber/fiber/v2 v2.52.15 // held back: v3 requires go 1.23
	github.com/uptrace/bun/dialect/pgdialect v1.2.1
	github.com/uptrace/bun/driver/pgdriver v1.2.1
)

require (
	github.com/knadh/koanf/parsers/yaml v0.1.0
	github.com/knadh/koanf/providers/env v1.0.0
	github.com/knadh/koanf/providers/file v1.1.2
	github.com/knadh/koanf/v2 v2.1.2
	github.com/prometheus/client_golang v1.20.5
)

require (
	github.com/PaesslerAG/gval v1.0.0 // indirect
	github.com/andybalholm/brotli v1.1.0 // indirect
	github.com/beorn7/perks v1.0.1 // indirect
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	github.com/davecgh/go-spew v1.1.1 // indirect
	github.com/fsnotify/fsnotify v1.7.0 // indirect
	github.com/go-viper/mapstructure/v2 v2.2.1 // indirect
	github.com/jinzhu/inflection v1.0.0 // indirect
	github.com/klauspost/compress v1.17.9 // indirect
	github.com/knadh/koanf/maps v0.1.1 // indirect
	github.com/kr/text v0.2.0 // indirect
	github.com/mattn/go-colorable v0.1.13 // indirect
	github.com/mattn/go-isatty v0.0.20 // indirect
	github.com/mattn/go-runewidth v0.0.16 // indirect
	github.com/mitchellh/copystructure v1.2.0 // indirect
	github.com/mitchellh/reflectwalk v1.0.2 // indirect
	github.com/munnerz/goautoneg v0.0.0-20191010083416-a7dc8b61c822 // indirect
	github.com/pmezard/go-difflib v1.0.0 // indirect
	github.com/prometheus/client_model v0.6.1 // indirect
	github.com/prometheus/common v0.55.0 // indirect
	github.com/prometheus/procfs v0.15.1 // indirect
	github.com/rivo/uniseg v0.2.0 // indirect
	github.com/tidwall/gjson v1.17.1 // indirect
	github.com/tidwall/match v1.1.1 // indirect
	github.com/tidwall/pretty v1.2.1 // indirect
	github.com/tidwall/sjson v1.2.5 // indirect
	github.com/tmthrgd/go-hex v0.0.0-20190904060850-447a3041c3bc // indirect
	github.com/valyala/bytebufferpool v1.0.0 // indirect
	github.com/valyala/fasthttp v1.51.0 // indirect
	github.com/valyala/tcplisten v1.0.0 // indirect
	github.com/vmihailenco/msgpack/v5 v5.4.1 // indirect
	github.com/vmihailenco/tagparser/v2 v2.0.0 // indirect
	golang.org/x/crypto v0.21.0 // indirect
	golang.org/x/sys v0.28.0 // indirect
	golang.org/x/text v0.16.0 // indirect
	google.golang.org/protobuf v1.34.2 // indirect
	gopkg.in/yaml.v3 v3.0.1 // indirect
	mellium.im/sasl v0.3.1 // indirect
)
