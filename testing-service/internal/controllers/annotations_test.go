package controllers

import (
	"go/ast"
	"go/parser"
	"go/token"
	"regexp"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
)

var (
	sortByEnums = regexp.MustCompile(`@Param\s+sort_by\s+.*enums\(([^)]*)\)`)
	routerPath  = regexp.MustCompile(`@Router\s+(\S+)`)
)

// The sort_by enums of a listing and the fields its repository accepts are the
// same list written twice: the annotation goes into the published spec, the
// table is what AddSorting validates against. They drifted apart once, and a
// reader of the spec has no way to tell. This is what keeps them together.
func TestTheSortByEnumsMatchTheSortingFieldsOfTheListing(t *testing.T) {
	want := map[string][]string{
		"/api/v1/test-cases":     *dao.GetTestCasesSortingFields(),
		"/api/v1/endpoint-mocks": *dao.GetEndpointMocksSortingFields(),
		"/api/v1/tests-runs":     *dao.GetTestsRunsSortingFields(),
		"/api/v1/test-case-runs": *dao.GetTestCaseRunsSortingFields(),
	}

	got := annotatedSortByEnums(t)

	for route, fields := range want {
		t.Run(route, func(t *testing.T) {
			declared, annotated := got[route]
			require.True(t, annotated, "the listing handler declares no sort_by enums")
			assert.Equal(t, fields, declared)
		})
	}
	assert.Len(t, got, len(want), "every sort_by annotation belongs to a listing checked here")
}

// annotatedSortByEnums reads the sort_by enums out of the handler comments of
// this package, keyed by the route the handler is annotated with.
func annotatedSortByEnums(t *testing.T) map[string][]string {
	t.Helper()
	fileSet := token.NewFileSet()
	packages, err := parser.ParseDir(fileSet, ".", nil, parser.ParseComments)
	require.NoError(t, err)

	enums := map[string][]string{}
	for _, pkg := range packages {
		for _, file := range pkg.Files {
			for _, declaration := range file.Decls {
				function, ok := declaration.(*ast.FuncDecl)
				if !ok || function.Doc == nil {
					continue
				}
				doc := function.Doc.Text()
				fields := sortByEnums.FindStringSubmatch(doc)
				route := routerPath.FindStringSubmatch(doc)
				if fields == nil || route == nil {
					continue
				}
				enums[route[1]] = splitEnums(fields[1])
			}
		}
	}
	return enums
}

func splitEnums(list string) []string {
	fields := strings.Split(list, ",")
	for i, field := range fields {
		fields[i] = strings.TrimSpace(field)
	}
	return fields
}
