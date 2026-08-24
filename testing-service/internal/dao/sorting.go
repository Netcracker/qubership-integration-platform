package dao

import (
	"fmt"
	"slices"
	"strings"

	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// normalizeOrder makes the order the caller sent comparable. The API has always
// taken it straight off the query string, where "asc" is as common as "ASC".
func normalizeOrder(order string) string {
	return strings.ToUpper(strings.TrimSpace(order))
}

// GetSqlSortingOrder renders the ORDER BY direction. An order it does not know
// falls back to ascending rather than reaching the clause verbatim: the clause
// is built as text, and ValidateSortOptions is what rejects the value.
func GetSqlSortingOrder(order string) string {
	switch normalizeOrder(order) {
	case model.OrderDescending:
		return "DESC NULLS FIRST"
	default:
		return "ASC NULLS LAST"
	}
}

func ValidateSortOptions(options model.SortOptions, sortingFields *[]string) error {
	if options.By != "" && !slices.Contains(*sortingFields, options.By) {
		return invalidSelection(
			"wrong sorting field %q, expected one of: %v", options.By, strings.Join(*sortingFields, ", "))
	}
	orders := []string{model.OrderAscending, model.OrderDescending}
	if !slices.Contains(orders, normalizeOrder(options.Order)) {
		return invalidSelection(
			"wrong sorting order %q, expected one of: %v", options.Order, strings.Join(orders, ", "))
	}
	return nil
}

// AddSorting orders query by sorting.By. Both the field and the order reach the
// ORDER BY clause as text rather than as bound arguments, so the field list the
// listing declares is a parameter and not an assumption: this is the check that
// keeps a value straight off the query string out of the clause.
func AddSorting(query *bun.SelectQuery, sorting model.SortOptions, sortingFields *[]string) (*bun.SelectQuery, error) {
	if err := ValidateSortOptions(sorting, sortingFields); err != nil {
		return nil, err
	}
	if sorting.By == "" {
		return query, nil
	}
	return query.Order(fmt.Sprintf("%v %v", sorting.By, GetSqlSortingOrder(sorting.Order))), nil
}
