package dao

import (
	"time"

	"github.com/google/uuid"
	"github.com/uptrace/bun"
)

// Values of the run_status enum.
const (
	RunStatusPending  = "pending"
	RunStatusRunning  = "running"
	RunStatusCanceled = "canceled"
	RunStatusFinished = "finished"
	RunStatusSkipped  = "skipped"
)

// Values of the entity_type enum: what part of a message a matcher inspects.
const (
	EntityTypeBody           = "body"
	EntityTypeHeader         = "header"
	EntityTypeStatus         = "status"
	EntityTypePathParameter  = "path_parameter"
	EntityTypeQueryParameter = "query_parameter"
)

// Values of the http_method enum.
const (
	HttpMethodGet    = "GET"
	HttpMethodPost   = "POST"
	HttpMethodPut    = "PUT"
	HttpMethodDelete = "DELETE"
	HttpMethodPatch  = "PATCH"
	HttpMethodHead   = "HEAD"
)

// Metadata carries the audit columns. Embedding it also installs the
// BeforeAppendModel hook that fills them in.
type Metadata struct {
	CreatedBy *string    `bun:"created_by" json:"createdBy"`
	CreatedAt *time.Time `bun:"created_at,type:timestamptz" json:"createdAt"`

	UpdatedBy *string    `bun:"updated_by" json:"updatedBy"`
	UpdatedAt *time.Time `bun:"updated_at,type:timestamptz" json:"updatedAt"`
}

type Header struct {
	bun.BaseModel `bun:"table:headers"`

	ID        uuid.UUID `bun:"id,pk,type:uuid,default:gen_random_uuid()" json:"-"`
	MessageID uuid.UUID `bun:"message_id,type:uuid" json:"-"` // not a pointer, works around uptrace/bun#884
	Name      string    `json:"name"`
	Value     string    `json:"value"`
} // @name Header

type Message struct {
	bun.BaseModel `bun:"table:messages"`

	ID      uuid.UUID `bun:"id,pk,type:uuid,default:gen_random_uuid()" json:"-"`
	Body    *string   `json:"body"`
	OwnerID uuid.UUID `bun:"owner_id,type:uuid" json:"-"` // not a pointer, works around uptrace/bun#884
	Headers []*Header `bun:"rel:has-many,join:id=message_id" json:"headers"`
} // @name Message

type QueryParameter struct {
	bun.BaseModel `bun:"table:query_parameters"`

	ID                uuid.UUID `bun:"id,pk,type:uuid,default:gen_random_uuid()" json:"-"`
	RequestSettingsID uuid.UUID `bun:"request_settings_id,type:uuid" json:"-"` // not a pointer, works around uptrace/bun#884
	Name              string    `json:"name"`
	Value             string    `json:"value"`
} // @name QueryParameter

type PathParameter struct {
	bun.BaseModel `bun:"table:path_parameters"`

	ID                uuid.UUID `bun:"id,pk,type:uuid,default:gen_random_uuid()" json:"-"`
	RequestSettingsID uuid.UUID `bun:"request_settings_id,type:uuid" json:"-"` // not a pointer, works around uptrace/bun#884
	Name              string    `json:"name"`
	Value             string    `json:"value"`
} // @name PathParameter

type RequestSettings struct {
	bun.BaseModel `bun:"table:request_settings"`

	ID              uuid.UUID         `bun:"id,pk,type:uuid,default:gen_random_uuid()" json:"-"`
	QueryParameters []*QueryParameter `bun:"rel:has-many,join:id=request_settings_id" json:"queryParameters"`
	PathParameters  []*PathParameter  `bun:"rel:has-many,join:id=request_settings_id" json:"pathParameters"`
	Message         *Message          `bun:"rel:has-one,join:id=owner_id" json:"message"`
	Method          string            `bun:"method,type:http_method" json:"method"`
	Timeout         int               `json:"timeout"`
	TestCaseID      uuid.UUID         `bun:"test_case_id,type:uuid" json:"-"`
} // @name RequestSettings

type ResponseSettings struct {
	bun.BaseModel `bun:"table:response_settings"`

	ID             uuid.UUID `bun:"id,pk,type:uuid,default:gen_random_uuid()" json:"-"`
	Message        *Message  `bun:"rel:has-one,join:id=owner_id" json:"message"`
	Status         int       `json:"status"`
	Delay          int       `json:"delay"`
	EndpointMockID uuid.UUID `bun:"endpoint_mock_id,type:uuid" json:"-"`
} // @name ResponseSettings

type TriggerReference struct {
	bun.BaseModel `bun:"table:trigger_references"`

	ID         uuid.UUID `bun:"id,pk,type:uuid,default:gen_random_uuid()" json:"-"`
	ChainID    string    `bun:"chain_id" json:"chainId"`
	ElementID  string    `bun:"element_id" json:"elementId"`
	TestCaseID uuid.UUID `bun:"test_case_id,type:uuid" json:"-"`
} // @name TriggerReference

type EndpointReference struct {
	bun.BaseModel `bun:"table:endpoint_references"`

	ID             uuid.UUID `bun:"id,pk,type:uuid,default:gen_random_uuid()" json:"-"`
	ChainID        string    `bun:"chain_id" json:"chainId"`
	ElementID      string    `bun:"element_id" json:"elementId"`
	EndpointMockID uuid.UUID `bun:"endpoint_mock_id,type:uuid" json:"-"`
} // @name EndpointReference

type MatcherParameter struct {
	bun.BaseModel `bun:"table:matcher_parameters"`

	ID        uuid.UUID `bun:"id,pk,type:uuid,default:gen_random_uuid()" json:"-"`
	Name      string    `json:"name"`
	Value     string    `json:"value"`
	MatcherID uuid.UUID `bun:"matcher_id,type:uuid" json:"-"` // not a pointer, works around uptrace/bun#884
} // @name MatcherParameter

type Matcher struct {
	bun.BaseModel `bun:"table:matchers"`

	ID          uuid.UUID           `bun:"id,pk,type:uuid,default:gen_random_uuid()" json:"id"`
	OwnerID     uuid.UUID           `bun:"owner_id,type:uuid" json:"-"` // not a pointer, works around uptrace/bun#884
	Name        string              `json:"name"`
	Description string              `json:"description"`
	Enabled     bool                `bun:"enabled,type:boolean" json:"enabled"`
	Type        string              `bun:",type:matcher_type" json:"type"`
	EntityType  string              `bun:"entity_type,type:entity_type" json:"entityType"`
	EntityName  *string             `bun:"entity_name" json:"entityName"`
	Parameters  []*MatcherParameter `bun:"rel:has-many,join:id=matcher_id" json:"parameters"`
} // @name Matcher

type TestCase struct {
	bun.BaseModel `bun:"table:test_cases"`

	Metadata

	ID                      uuid.UUID         `bun:"id,pk,type:uuid,default:gen_random_uuid()" json:"id"`
	Name                    string            `json:"name"`
	Description             string            `json:"description"`
	Enabled                 bool              `json:"enabled"`
	TriggerReference        *TriggerReference `bun:"rel:has-one,join:id=test_case_id" json:"triggerReference"`
	RequestSettings         *RequestSettings  `bun:"rel:has-one,join:id=test_case_id" json:"requestSettings"`
	ResponseValidationRules []*Matcher        `bun:"rel:has-many,join:id=owner_id" json:"responseValidationRules"`
} // @name TestCase

type EndpointMock struct {
	bun.BaseModel `bun:"table:endpoint_mocks"`

	Metadata

	ID                uuid.UUID          `bun:"id,pk,type:uuid,default:gen_random_uuid()" json:"id"`
	Name              string             `json:"name"`
	Description       string             `json:"description"`
	Enabled           bool               `bun:"enabled,type:boolean" json:"enabled"`
	EndpointReference *EndpointReference `bun:"rel:has-one,join:id=endpoint_mock_id" json:"endpointReference"`
	ResponseSettings  *ResponseSettings  `bun:"rel:has-one,join:id=endpoint_mock_id" json:"responseSettings"`
	RequestMatchers   []*Matcher         `bun:"rel:has-many,join:id=owner_id" json:"requestMatchers"`
} // @name EndpointMock

type ValidationError struct {
	bun.BaseModel `bun:"table:validation_errors"`

	ID            uuid.UUID  `bun:"id,pk,type:uuid,default:gen_random_uuid()" json:"id"`
	TestCaseRunID *uuid.UUID `bun:"test_case_run_id,type:uuid" json:"testCaseRunId"`
	MatcherID     *uuid.UUID `bun:"matcher_id,type:uuid" json:"matcherId"`
	Matcher       *Matcher   `bun:"rel:belongs-to,join:matcher_id=id" json:"matcher"`
	Message       string     `json:"message"`
} // @name ValidationError

type TestCaseRun struct {
	bun.BaseModel `bun:"table:test_case_runs"`

	ID         uuid.UUID          `bun:"id,pk,type:uuid,default:gen_random_uuid()" json:"id"`
	TestsRunID *uuid.UUID         `bun:"tests_run_id,type:uuid" json:"testsRunId"`
	TestCaseID *uuid.UUID         `bun:"test_case_id,type:uuid" json:"testCaseId"`
	TestCase   *TestCase          `bun:"rel:belongs-to,join:test_case_id=id" json:"-"`
	Start      *time.Time         `bun:"start,type:timestamptz" json:"start"`
	Finish     *time.Time         `bun:"finish,type:timestamptz" json:"finish"`
	Status     *string            `bun:"status,type:run_status" json:"status"`
	SessionID  *string            `json:"sessionId"`
	Errors     []*ValidationError `bun:"rel:has-many,join:id=test_case_run_id" json:"-"`
}

type TestsRun struct {
	bun.BaseModel `bun:"table:tests_runs"`

	Metadata

	ID           uuid.UUID      `bun:"id,pk,type:uuid,default:gen_random_uuid()" json:"id"`
	TestCaseRuns []*TestCaseRun `bun:"rel:has-many,join:id=tests_run_id" json:"-"`
}

type TestsRunView struct {
	bun.BaseModel `bun:"table:tests_runs_view"`

	TestsRun

	Start     *time.Time `bun:"start,type:timestamptz" json:"start"`
	Finish    *time.Time `bun:"finish,type:timestamptz" json:"finish"`
	Status    *string    `bun:"status,type:run_status" json:"status"`
	Errors    int        `bun:"errors,type:integer" json:"errors"`
	TestCases int        `bun:"test_cases,type:integer" json:"testCases"`
} // @name TestsRunView

type TestCaseRunView struct {
	bun.BaseModel `bun:"table:test_case_runs_view"`

	TestCaseRun

	TestCaseName        *string `json:"testCaseName"`
	TestCaseDescription *string `json:"testCaseDescription"`
	ChainID             *string `bun:"chain_id" json:"chainId"`
	Errors              int     `bun:"errors,type:integer" json:"errors"`
} // @name TestCaseRunView

type TestCaseView struct {
	bun.BaseModel `bun:"table:test_cases_view"`

	TestCase

	ChainID             string `bun:"chain_id" json:"-"`
	ElementID           string `bun:"element_id" json:"-"`
	ValidationRuleCount int    `bun:"validation_rule_count,type:integer" json:"validationRuleCount"`
	EnabledRuleCount    int    `bun:"enabled_rule_count,type:integer" json:"enabledRuleCount"`
}
