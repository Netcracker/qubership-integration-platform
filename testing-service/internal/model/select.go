package model

import "github.com/google/uuid"

type PaginationOptions struct {
	Offset int `query:"offset"`
	Limit  int `query:"limit"`
}

type SortOptions struct {
	By    string `query:"sort_by"`
	Order string `query:"sort_order"`
}

const (
	OrderAscending  = "ASC"
	OrderDescending = "DESC"
)

type Filter struct {
	Feature   string   `json:"feature"`
	Condition string   `json:"condition"`
	Values    []string `json:"values"`
} // @name Filter

type SelectionSpecification struct {
	Ids        *[]uuid.UUID `json:"ids"`
	SearchText *string      `json:"searchText"`
	Filters    *[]Filter    `json:"filters"`
} // @name SelectionSpecification

// Conditions a Filter may use. Which ones a given feature accepts is declared
// by its FeatureFilterConfiguration.
const (
	ConditionIs             = "is"
	ConditionIsNot          = "is_not"
	ConditionContains       = "contains"
	ConditionDoesNotContain = "does_not_contain"
	ConditionStartsWith     = "starts_with"
	ConditionEndsWith       = "ends_with"
	ConditionIn             = "in"
	ConditionNotIn          = "not_in"
	ConditionEmpty          = "empty"
	ConditionNotEmpty       = "not_empty"
	ConditionIsAfter        = "is_after"
	ConditionIsBefore       = "is_before"
	ConditionIsWithin       = "is_within"
	ConditionLessThan       = "less_than"
	ConditionGreaterThan    = "greater_than"
)

type FeatureFilterConfiguration struct {
	Column     string
	Conditions []string
	// RequiresConversionToText casts the column to text before comparing.
	RequiresConversionToText bool
	Converter                func(s string) (any, error)
}

type FilterConfiguration map[string]FeatureFilterConfiguration
