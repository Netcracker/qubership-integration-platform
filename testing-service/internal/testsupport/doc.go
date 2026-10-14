// Package testsupport runs the integration suite against a real PostgreSQL.
// Everything in it is behind the integration build tag, so the default build
// pulls in neither Docker nor testcontainers.
package testsupport
