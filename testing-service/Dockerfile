# GOTOOLCHAIN=local keeps the build on the image's own Go, so a dependency that
# asks for a newer toolchain fails here rather than silently raising the ceiling.
FROM golang:1.22-alpine AS build
ENV CGO_ENABLED=0 GOTOOLCHAIN=local
WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . ./
# The integration suite sits behind the `integration` build tag and needs a
# Docker daemon, so the image build runs the default suite only. The dependency
# and sanitization suites skip here as well, since they resolve ../scripts/,
# which sits outside the build context. CI runs all three.
RUN go test ./... \
    && go build -trimpath -o /out/testing-service ./cmd/testing-service

FROM alpine:3.21

RUN apk add --no-cache curl=8.14.1-r2

WORKDIR /app
COPY --from=build --chown=10001:0 /out/testing-service ./testing-service
COPY --chown=10001:0 application.yaml ./application.yaml

USER 10001
EXPOSE 8080
CMD ["/app/testing-service"]
