package dao

import (
	"fmt"
	"slices"
	"strings"

	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

func GetSqlSortingOrder(order string) string {
	switch order {
	case model.OrderAscending:
		return "ASC NULLS LAST"
	case model.OrderDescending:
		return "DESC NULLS FIRST"
	default:
		return order
	}
}

func ValidateSortOptions(options model.SortOptions, sortingFields *[]string) error {
	if options.By != "" && !slices.Contains(*sortingFields, options.By) {
		return fmt.Errorf("wrong sorting field %q, expected one of: %v", options.By, strings.Join(*sortingFields, ", "))
	}
	orders := []string{model.OrderAscending, model.OrderDescending}
	if !slices.Contains(orders, options.Order) {
		return fmt.Errorf("wrong sorting order %q, expected one of: %v", options.Order, strings.Join(orders, ", "))
	}
	return nil
}

func AddSorting(query *bun.SelectQuery, sorting model.SortOptions) *bun.SelectQuery {
	if sorting.By == "" {
		return query
	}
	return query.Order(fmt.Sprintf("%v %v", sorting.By, GetSqlSortingOrder(sorting.Order)))
}
