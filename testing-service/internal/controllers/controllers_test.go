package controllers

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/services"
)

const knownID = "11111111-2222-3333-4444-555555555555"

type fakes struct {
	testCases         *fakeTestCasesService
	testsRuns         *fakeTestsRunsService
	testCaseRuns      *fakeTestCaseRunsService
	testCaseRunErrors *fakeTestCaseRunErrorsService
	endpointMocks     *fakeEndpointMocksService
}

// newFakes answers every call the route table makes, so a handler that reports
// anything but success is reporting a defect rather than an unset fake.
func newFakes() *fakes {
	id := uuid.MustParse(knownID)
	return &fakes{
		testCases: &fakeTestCasesService{
			findAll: func(context.Context, *model.SelectionSpecification, model.SortOptions, *model.PaginationOptions, bool) (*[]dao.TestCaseView, error) {
				return &[]dao.TestCaseView{{TestCase: dao.TestCase{ID: id}}}, nil
			},
			findByID: func(context.Context, uuid.UUID) (*dao.TestCaseView, error) {
				return &dao.TestCaseView{TestCase: dao.TestCase{ID: id}}, nil
			},
			create: func(_ context.Context, testCase *dao.TestCase) (*dao.TestCase, error) {
				return testCase, nil
			},
			update: func(_ context.Context, testCase *dao.TestCase) (*dao.TestCase, error) {
				return testCase, nil
			},
			delete: func(context.Context, uuid.UUID) error { return nil },
			export: func(context.Context, *[]uuid.UUID) (*[]byte, error) {
				data := []byte("PK")
				return &data, nil
			},
		},
		testsRuns: &fakeTestsRunsService{
			findAll: func(context.Context, *model.SelectionSpecification, model.SortOptions, *model.PaginationOptions) (*[]dao.TestsRunView, error) {
				return &[]dao.TestsRunView{{TestsRun: dao.TestsRun{ID: id}}}, nil
			},
			findByID: func(context.Context, uuid.UUID) (*dao.TestsRunView, error) {
				return &dao.TestsRunView{TestsRun: dao.TestsRun{ID: id}}, nil
			},
			startNew: func(context.Context, *[]uuid.UUID, string) (*uuid.UUID, error) {
				return &id, nil
			},
			cancel: func(context.Context, uuid.UUID) error { return nil },
			export: func(context.Context, *[]uuid.UUID) (string, error) { return "id\n", nil },
		},
		testCaseRuns: &fakeTestCaseRunsService{
			findAll: func(context.Context, *model.SelectionSpecification, model.SortOptions, *model.PaginationOptions) (*[]dao.TestCaseRunView, error) {
				return &[]dao.TestCaseRunView{{TestCaseRun: dao.TestCaseRun{ID: id}}}, nil
			},
		},
		testCaseRunErrors: &fakeTestCaseRunErrorsService{
			findByTestCaseRunID: func(context.Context, uuid.UUID, bool) (*[]dao.ValidationError, error) {
				return &[]dao.ValidationError{}, nil
			},
		},
		endpointMocks: &fakeEndpointMocksService{
			findAll: func(context.Context, *model.SelectionSpecification, model.SortOptions, *model.PaginationOptions, bool) (*[]dao.EndpointMock, error) {
				return &[]dao.EndpointMock{{ID: id}}, nil
			},
			call: func(context.Context, dao.EndpointReference, model.Exchange) (*model.Exchange, error) {
				return &model.Exchange{Status: http.StatusOK, Body: []byte("mocked")}, nil
			},
		},
	}
}

func (f *fakes) services() *services.Services {
	return &services.Services{
		TestCasesService:         f.testCases,
		TestsRunsService:         f.testsRuns,
		TestCaseRunsService:      f.testCaseRuns,
		TestCaseRunErrorsService: f.testCaseRunErrors,
		EndpointMocksService:     f.endpointMocks,
	}
}

func mount(t *testing.T, f *fakes, cfg config.Config, deps config.Deps) *fiber.App {
	t.Helper()
	if deps.Logger == nil {
		deps.Logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	app := fiber.New()
	New(cfg, deps, f.services()).Mount(app)
	return app
}

func request(t *testing.T, app *fiber.App, method, target, body string) *http.Response {
	t.Helper()
	req := httptest.NewRequest(method, target, strings.NewReader(body))
	req.Header.Set("Content-Type", fiber.MIMEApplicationJSON)
	response, err := app.Test(req, 2000)
	require.NoError(t, err)
	t.Cleanup(func() { _ = response.Body.Close() })
	return response
}

func TestMountRegistersTheWholeRouteTable(t *testing.T) {
	tests := []struct {
		name   string
		method string
		target string
		body   string
		status int
	}{
		{"list test cases", http.MethodPost, "/test-cases", "{}", http.StatusOK},
		{"create test case", http.MethodPost, "/test-cases/create", "{}", http.StatusCreated},
		{"export test cases", http.MethodPost, "/test-cases/export", "[]", http.StatusOK},
		{"get test case", http.MethodGet, "/test-cases/" + knownID, "", http.StatusOK},
		{"update test case", http.MethodPost, "/test-cases/" + knownID, "{}", http.StatusOK},
		{"bulk delete test cases", http.MethodDelete, "/test-cases", "[]", http.StatusNoContent},
		{"delete test case", http.MethodDelete, "/test-cases/" + knownID, "", http.StatusNoContent},

		{"list tests runs", http.MethodPost, "/tests-runs", "{}", http.StatusOK},
		{"bulk delete tests runs", http.MethodDelete, "/tests-runs", "[]", http.StatusNoContent},
		{"start tests run", http.MethodPost, "/tests-runs/create", "[]", http.StatusCreated},
		{"bulk cancel tests runs", http.MethodPost, "/tests-runs/cancel", "[]", http.StatusNoContent},
		{"bulk export tests runs", http.MethodPost, "/tests-runs/export", "[]", http.StatusOK},
		{"get tests run", http.MethodGet, "/tests-runs/" + knownID, "", http.StatusOK},
		{"delete tests run", http.MethodDelete, "/tests-runs/" + knownID, "", http.StatusNoContent},
		{"cancel tests run", http.MethodPost, "/tests-runs/" + knownID + "/cancel", "", http.StatusNoContent},
		{"export tests run", http.MethodPost, "/tests-runs/" + knownID + "/export", "", http.StatusOK},

		{"list test case runs", http.MethodPost, "/test-case-runs", "{}", http.StatusOK},
		{"bulk cancel test case runs", http.MethodPost, "/test-case-runs/cancel", "[]", http.StatusNoContent},
		{"bulk export test case runs", http.MethodPost, "/test-case-runs/export", "[]", http.StatusOK},
		{"export validation errors", http.MethodPost, "/test-case-runs/errors/export", "[]", http.StatusOK},
		{"get test case run", http.MethodGet, "/test-case-runs/" + knownID, "", http.StatusNotFound},
		{"cancel test case run", http.MethodPost, "/test-case-runs/" + knownID + "/cancel", "", http.StatusNoContent},
		{"export test case run", http.MethodPost, "/test-case-runs/" + knownID + "/export", "", http.StatusOK},
		{"get test case run errors", http.MethodGet, "/test-case-runs/" + knownID + "/errors", "", http.StatusOK},

		{"list endpoint mocks", http.MethodPost, "/endpoint-mocks", "{}", http.StatusOK},
		{"create endpoint mock", http.MethodPost, "/endpoint-mocks/create", "{}", http.StatusCreated},
		{"export endpoint mocks", http.MethodPost, "/endpoint-mocks/export", "[]", http.StatusOK},
		{"get endpoint mock", http.MethodGet, "/endpoint-mocks/" + knownID, "", http.StatusNotFound},
		{"update endpoint mock", http.MethodPost, "/endpoint-mocks/" + knownID, "{}", http.StatusOK},
		{"bulk delete endpoint mocks", http.MethodDelete, "/endpoint-mocks", "[]", http.StatusNoContent},
		{"delete endpoint mock", http.MethodDelete, "/endpoint-mocks/" + knownID, "", http.StatusNoContent},

		{"get service mode", http.MethodGet, "/mode", "", http.StatusOK},
	}

	app := mount(t, newFakes(), config.Config{}, config.Deps{})
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			response := request(t, app, test.method, test.target, test.body)
			assert.Equal(t, test.status, response.StatusCode)
		})
	}
}

func TestImportRoutesAreReachable(t *testing.T) {
	// A request carrying no multipart body fails in the handler, which proves the
	// route is registered without building an archive.
	app := mount(t, newFakes(), config.Config{}, config.Deps{})
	for _, target := range []string{"/test-cases/import", "/endpoint-mocks/import"} {
		t.Run(target, func(t *testing.T) {
			response := request(t, app, http.MethodPost, target, "")
			assert.Equal(t, http.StatusBadRequest, response.StatusCode)
		})
	}
}

func TestLiteralSegmentsWinOverTheIdPattern(t *testing.T) {
	f := newFakes()
	var lookedUp []uuid.UUID
	f.testCases.findByID = func(_ context.Context, id uuid.UUID) (*dao.TestCaseView, error) {
		lookedUp = append(lookedUp, id)
		return nil, nil
	}
	app := mount(t, f, config.Config{}, config.Deps{})

	// "call" would parse as an :id segment, and does not: the literal route is
	// registered first.
	response := request(t, app, http.MethodGet, "/endpoint-mocks/call", "")
	assert.Equal(t, http.StatusBadRequest, response.StatusCode, "the mock call reports the missing header")

	response = request(t, app, http.MethodPost, "/test-cases/create", "{}")
	assert.Equal(t, http.StatusCreated, response.StatusCode)
	assert.Empty(t, lookedUp, "no request reached the lookup by id")
}

func TestListEndpointsReturnIdsOnRequest(t *testing.T) {
	tests := []struct {
		name   string
		target string
	}{
		{"test cases", "/test-cases"},
		{"tests runs", "/tests-runs"},
		{"test case runs", "/test-case-runs"},
		{"endpoint mocks", "/endpoint-mocks"},
	}

	app := mount(t, newFakes(), config.Config{}, config.Deps{})
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			response := request(t, app, http.MethodPost, test.target+"?return_ids=true", "{}")
			require.Equal(t, http.StatusOK, response.StatusCode)
			body, err := io.ReadAll(response.Body)
			require.NoError(t, err)
			var ids []uuid.UUID
			require.NoError(t, json.Unmarshal(body, &ids))
			assert.Equal(t, []uuid.UUID{uuid.MustParse(knownID)}, ids)
		})
	}
}

func TestReturnIdsDropsPagination(t *testing.T) {
	f := newFakes()
	var seen *model.PaginationOptions
	var called bool
	f.testCases.findAll = func(
		_ context.Context,
		_ *model.SelectionSpecification,
		_ model.SortOptions,
		pagination *model.PaginationOptions,
		_ bool,
	) (*[]dao.TestCaseView, error) {
		seen, called = pagination, true
		return &[]dao.TestCaseView{}, nil
	}
	app := mount(t, f, config.Config{}, config.Deps{})

	request(t, app, http.MethodPost, "/test-cases?limit=5&return_ids=true", "{}")
	require.True(t, called)
	assert.Nil(t, seen)

	request(t, app, http.MethodPost, "/test-cases?limit=5", "{}")
	require.NotNil(t, seen)
	assert.Equal(t, 5, seen.Limit)
}

func TestSortOrderDefaultsToAscending(t *testing.T) {
	f := newFakes()
	var seen model.SortOptions
	f.testCases.findAll = func(
		_ context.Context,
		_ *model.SelectionSpecification,
		sorting model.SortOptions,
		_ *model.PaginationOptions,
		_ bool,
	) (*[]dao.TestCaseView, error) {
		seen = sorting
		return &[]dao.TestCaseView{}, nil
	}
	app := mount(t, f, config.Config{}, config.Deps{})

	request(t, app, http.MethodPost, "/test-cases?sort_by=name", "{}")
	assert.Equal(t, model.SortOptions{By: "name", Order: model.OrderAscending}, seen)

	request(t, app, http.MethodPost, "/test-cases?sort_by=name&sort_order=DESC", "{}")
	assert.Equal(t, model.SortOptions{By: "name", Order: model.OrderDescending}, seen)
}

func TestBadRequests(t *testing.T) {
	tests := []struct {
		name   string
		method string
		target string
		body   string
	}{
		{"malformed uuid", http.MethodGet, "/test-cases/not-a-uuid", ""},
		{"malformed specification", http.MethodPost, "/test-cases", "{"},
		{"malformed id list", http.MethodDelete, "/test-cases", "{"},
		{"malformed pagination", http.MethodPost, "/test-cases?limit=many", "{}"},
	}

	app := mount(t, newFakes(), config.Config{}, config.Deps{})
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			response := request(t, app, test.method, test.target, test.body)
			require.Equal(t, http.StatusBadRequest, response.StatusCode)

			var message ErrorMessage
			require.NoError(t, json.NewDecoder(response.Body).Decode(&message))
			assert.Equal(t, ServiceName, message.ServiceName)
			assert.NotEmpty(t, message.ErrorMessage)
			assert.Equal(t, "No Stacktrace Available", message.Stacktrace)
		})
	}
}

func TestFindByIdReportsNotFound(t *testing.T) {
	f := newFakes()
	f.testCases.findByID = func(context.Context, uuid.UUID) (*dao.TestCaseView, error) { return nil, nil }
	app := mount(t, f, config.Config{}, config.Deps{})

	response := request(t, app, http.MethodGet, "/test-cases/"+knownID, "")
	assert.Equal(t, http.StatusNotFound, response.StatusCode)
}

func TestFailingServiceReportsInternalError(t *testing.T) {
	f := newFakes()
	f.testCases.findByID = func(context.Context, uuid.UUID) (*dao.TestCaseView, error) {
		return nil, errors.New("connection refused")
	}
	app := mount(t, f, config.Config{}, config.Deps{})

	response := request(t, app, http.MethodGet, "/test-cases/"+knownID, "")
	assert.Equal(t, http.StatusInternalServerError, response.StatusCode)
}

func TestStartNewReportsAnEmptyTestCaseListAsBadRequest(t *testing.T) {
	f := newFakes()
	f.testsRuns.startNew = func(context.Context, *[]uuid.UUID, string) (*uuid.UUID, error) {
		return nil, services.ErrEmptyTestCaseList
	}
	app := mount(t, f, config.Config{}, config.Deps{})

	response := request(t, app, http.MethodPost, "/tests-runs/create", "[]")
	require.Equal(t, http.StatusBadRequest, response.StatusCode)

	var message ErrorMessage
	require.NoError(t, json.NewDecoder(response.Body).Decode(&message))
	assert.Equal(t, services.ErrEmptyTestCaseList.Error(), message.ErrorMessage)
}

func TestStartNewPassesTheEntityType(t *testing.T) {
	f := newFakes()
	var seen string
	f.testsRuns.startNew = func(_ context.Context, _ *[]uuid.UUID, entityType string) (*uuid.UUID, error) {
		seen = entityType
		id := uuid.MustParse(knownID)
		return &id, nil
	}
	app := mount(t, f, config.Config{}, config.Deps{})

	request(t, app, http.MethodPost, "/tests-runs/create", "[]")
	assert.Equal(t, services.EntityTypeTestCases, seen, "the default is test cases")

	request(t, app, http.MethodPost, "/tests-runs/create?from=tests_runs", "[]")
	assert.Equal(t, services.EntityTypeTestsRuns, seen)
}

func TestServiceModeReportsTheConfiguredFlag(t *testing.T) {
	for _, production := range []bool{false, true} {
		app := mount(t, newFakes(), config.Config{Production: production}, config.Deps{})
		response := request(t, app, http.MethodGet, "/mode", "")
		require.Equal(t, http.StatusOK, response.StatusCode)

		var mode ServiceMode
		require.NoError(t, json.NewDecoder(response.Body).Decode(&mode))
		assert.Equal(t, production, mode.Production)
	}
}

func TestEndpointMockCall(t *testing.T) {
	encoded := base64.StdEncoding.EncodeToString(
		[]byte(`{"chainId":"chain-1","elementId":"element-1","operationPath":"/pets/{id}","path":"/pets/7"}`))

	f := newFakes()
	var seen dao.EndpointReference
	f.endpointMocks.call = func(
		_ context.Context,
		reference dao.EndpointReference,
		exchange model.Exchange,
	) (*model.Exchange, error) {
		seen = reference
		return &model.Exchange{
			Status:  http.StatusTeapot,
			Body:    exchange.Body,
			Headers: map[string][]string{"X-Mocked": {"yes"}},
		}, nil
	}
	app := mount(t, f, config.Config{}, config.Deps{})

	req := httptest.NewRequest(http.MethodPost, "/endpoint-mocks/call", strings.NewReader("ping"))
	req.Header.Set(model.TestingContextHeader, encoded)
	response, err := app.Test(req, 2000)
	require.NoError(t, err)
	defer func() { _ = response.Body.Close() }()

	assert.Equal(t, http.StatusTeapot, response.StatusCode)
	assert.Equal(t, "yes", response.Header.Get("X-Mocked"))
	body, err := io.ReadAll(response.Body)
	require.NoError(t, err)
	assert.Equal(t, "ping", string(body))
	assert.Equal(t, dao.EndpointReference{ChainID: "chain-1", ElementID: "element-1"}, seen)
}

func TestEndpointMockCallRejectsABrokenTestingContext(t *testing.T) {
	tests := []struct {
		name   string
		header string
		set    bool
	}{
		{name: "missing header"},
		{name: "not base64", header: "!!not base64!!", set: true},
		{name: "not json", header: base64.StdEncoding.EncodeToString([]byte("{")), set: true},
	}

	app := mount(t, newFakes(), config.Config{}, config.Deps{})
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodPost, "/endpoint-mocks/call", nil)
			if test.set {
				req.Header.Set(model.TestingContextHeader, test.header)
			}
			response, err := app.Test(req, 2000)
			require.NoError(t, err)
			defer func() { _ = response.Body.Close() }()
			assert.Equal(t, http.StatusBadRequest, response.StatusCode)
		})
	}
}

func TestEndpointMockCallAnswersEveryMethod(t *testing.T) {
	encoded := base64.StdEncoding.EncodeToString([]byte(`{"chainId":"c","elementId":"e"}`))
	app := mount(t, newFakes(), config.Config{}, config.Deps{})

	methods := []string{
		http.MethodGet, http.MethodPost, http.MethodPut,
		http.MethodPatch, http.MethodDelete, http.MethodHead,
	}
	for _, method := range methods {
		t.Run(method, func(t *testing.T) {
			req := httptest.NewRequest(method, "/endpoint-mocks/call", nil)
			req.Header.Set(model.TestingContextHeader, encoded)
			response, err := app.Test(req, 2000)
			require.NoError(t, err)
			defer func() { _ = response.Body.Close() }()
			assert.Equal(t, http.StatusOK, response.StatusCode)
		})
	}
}

func TestGetEndpointReference(t *testing.T) {
	reference := GetEndpointReference(&model.TestingContext{ChainID: "chain", ElementID: "element", Path: "/pets"})
	assert.Equal(t, dao.EndpointReference{ChainID: "chain", ElementID: "element"}, reference)
}

func TestMiddlewarePutsTheCallerIntoTheRequestContext(t *testing.T) {
	tests := []struct {
		name        string
		currentUser config.CurrentUserFunc
		want        string
	}{
		{name: "no resolver", want: dao.DefaultUser},
		{
			name:        "resolver returns a name",
			currentUser: func(context.Context) string { return "alice" },
			want:        "alice",
		},
		{
			name:        "resolver returns nothing",
			currentUser: func(context.Context) string { return "" },
			want:        dao.DefaultUser,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			f := newFakes()
			var seen string
			f.testCases.findByID = func(ctx context.Context, _ uuid.UUID) (*dao.TestCaseView, error) {
				seen = dao.CurrentUser(ctx)
				return &dao.TestCaseView{}, nil
			}
			app := mount(t, f, config.Config{}, config.Deps{CurrentUser: test.currentUser})

			response := request(t, app, http.MethodGet, "/test-cases/"+knownID, "")
			require.Equal(t, http.StatusOK, response.StatusCode)
			assert.Equal(t, test.want, seen)
		})
	}
}

func TestExportEndpointsSetTheirContentType(t *testing.T) {
	app := mount(t, newFakes(), config.Config{}, config.Deps{})

	response := request(t, app, http.MethodPost, "/test-cases/export", "[]")
	assert.Contains(t, response.Header.Get(fiber.HeaderContentType), MIMEApplicationZIP)

	response = request(t, app, http.MethodPost, "/tests-runs/export", "[]")
	assert.Contains(t, response.Header.Get(fiber.HeaderContentType), MIMETextCSV)
}
