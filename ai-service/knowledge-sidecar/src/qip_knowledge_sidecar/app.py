from __future__ import annotations

import os
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import AsyncIterator

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from knowledge_sdk import KnowledgeSDK
from qip_knowledge_sidecar.documentation import DocumentationStore
from qip_knowledge_sidecar.models import (
    ContextQueryRequest,
    ContextQueryResponse,
    DocumentationReadRequest,
    DocumentationSearchRequest,
    ExactQueryRequest,
    ExactQueryResponse,
    FilterQueryRequest,
    PackageRefModel,
    PackageResponse,
    RelationsQueryRequest,
    RelationsQueryResponse,
    SearchQueryResponse,
)
from qip_knowledge_sidecar.package import (
    PackageEligibilityError,
    PackageRef,
    ValidatedPackage,
    validate_package,
)
from qip_knowledge_sidecar.runtime import RuntimeAdapter, RuntimeErrorResponse


@dataclass
class AppState:
    package: ValidatedPackage | None = None
    adapter: RuntimeAdapter | None = None
    startup_error: PackageEligibilityError | None = None
    sdk: KnowledgeSDK | None = None
    documentation: DocumentationStore | None = None
    documentation_error: RuntimeErrorResponse | None = None


def _close_quietly(sdk: KnowledgeSDK | None) -> None:
    if sdk is None:
        return
    try:
        sdk.close()
    except Exception:  # pylint: disable=broad-exception-caught
        pass


def _open_candidate(
    path: Path,
) -> tuple[ValidatedPackage, KnowledgeSDK]:
    package = validate_package(path)
    sdk: KnowledgeSDK | None = None
    try:
        sdk = KnowledgeSDK.create(
            provider="lancedb",
            runtime_knowledge_dir=str(package.path),
            product=package.manifest["product"],
            sdk_version="1.0.0",
        )
        loaded_count = sdk.statistics().total_objects
        expected_count = package.manifest["total_objects"]
        if loaded_count != expected_count:
            raise ValueError(
                f"SDK loaded {loaded_count} objects; expected {expected_count}"
            )
        return package, sdk
    except Exception as error:
        _close_quietly(sdk)
        raise PackageEligibilityError(
            "KNOWLEDGE_RUNTIME_INCOMPATIBLE",
            f"Knowledge SDK cannot load package: {error}",
        ) from error


def _load_runtime(path: Path) -> tuple[ValidatedPackage, KnowledgeSDK]:
    return _open_candidate(path)


def _ref(value: PackageRef) -> PackageRefModel:
    return PackageRefModel(
        packageKey=value.package_key,
        knowledgeVersion=value.knowledge_version,
        schemaVersion=value.schema_version,
        packageChecksum=value.package_checksum,
        certificationStatus=value.certification_status,
        certificationDigest=value.certification_digest,
    )


def create_app(
    package_path: Path | None = None,
    documentation_path: Path | None = None,
) -> FastAPI:
    resolved_package = package_path or Path(
        os.getenv("QIP_KNOWLEDGE_PATH", "/knowledge")
    )
    resolved_documentation = documentation_path or Path(
        os.getenv("QIP_DOCUMENTATION_PATH", "/documentation")
    )
    state = AppState()

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        try:
            state.documentation = DocumentationStore(resolved_documentation)
        except RuntimeErrorResponse as error:
            state.documentation_error = error
        try:
            package, sdk = _load_runtime(resolved_package)
            state.package = package
            state.sdk = sdk
            state.adapter = RuntimeAdapter(
                sdk,
                package.path,
            )
        except PackageEligibilityError as error:
            state.startup_error = error
        yield
        _close_quietly(state.sdk)

    app = FastAPI(lifespan=lifespan)  # pylint: disable=redefined-outer-name

    @app.exception_handler(RuntimeErrorResponse)
    async def runtime_error_handler(
        _: object,
        error: RuntimeErrorResponse,
    ) -> JSONResponse:
        return JSONResponse(
            status_code=error.status,
            content={
                "code": error.code,
                "message": error.message,
                "retryable": error.retryable,
            },
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(
        request: Request,
        error: RequestValidationError,
    ) -> JSONResponse:
        if not request.url.path.startswith("/v1/documentation/"):
            return JSONResponse(status_code=422, content={"detail": error.errors()})
        return JSONResponse(
            status_code=422,
            content={
                "code": "DOCUMENTATION_INVALID_REQUEST",
                "message": "Request does not match the endpoint contract",
                "retryable": False,
            },
        )

    def require_runtime() -> tuple[ValidatedPackage, RuntimeAdapter]:
        if state.startup_error is not None:
            raise RuntimeErrorResponse(
                code=state.startup_error.code,
                message=state.startup_error.message,
                status=503,
                retryable=False,
            )
        if state.package is None or state.adapter is None:
            raise RuntimeErrorResponse(
                code="KNOWLEDGE_TEMPORARILY_UNAVAILABLE",
                message="Knowledge runtime startup has not completed",
                status=503,
                retryable=True,
            )
        return state.package, state.adapter

    def pinned(
        expected_checksum: str,
    ) -> tuple[ValidatedPackage, RuntimeAdapter]:
        package, adapter = require_runtime()
        if package.ref.package_checksum != expected_checksum:
            raise RuntimeErrorResponse(
                code="KNOWLEDGE_PACKAGE_PIN_MISMATCH",
                message=("expectedPackageChecksum does not match the active package"),
                status=409,
                retryable=False,
            )
        return package, adapter

    def require_documentation() -> DocumentationStore:
        if state.documentation_error is not None:
            raise state.documentation_error
        if state.documentation is None:
            raise RuntimeErrorResponse(
                code="DOCUMENTATION_UNAVAILABLE",
                message="Documentation runtime startup has not completed",
                status=503,
                retryable=True,
            )
        return state.documentation

    @app.get("/v1/documentation/manifest")
    def documentation_manifest() -> dict[str, object]:
        return require_documentation().manifest()

    @app.post("/v1/documentation/search")
    def documentation_search(request: DocumentationSearchRequest) -> dict[str, object]:
        return require_documentation().search(
            request.expectedChecksum, request.query, request.elementTypes, request.limit
        )

    @app.post("/v1/documentation/read")
    def documentation_read(request: DocumentationReadRequest) -> dict[str, object]:
        return require_documentation().read(
            request.expectedChecksum, request.id, request.cursor, request.maxBytes
        )

    @app.get("/v1/health/live")
    def live() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/v1/health/ready")
    def ready() -> dict[str, object]:
        package, _ = require_runtime()
        return {"status": "ok", "packageRef": _ref(package.ref)}

    @app.get("/v1/package", response_model=PackageResponse)
    def package() -> PackageResponse:
        package, _ = require_runtime()
        return PackageResponse(packageRef=_ref(package.ref))

    @app.post(
        "/v1/query/exact",
        response_model=ExactQueryResponse,
        response_model_by_alias=True,
    )
    def exact(request: ExactQueryRequest) -> ExactQueryResponse:
        package, adapter = pinned(request.expectedPackageChecksum)
        return ExactQueryResponse.model_validate(
            dict(
                packageRef=_ref(package.ref),
                object=adapter.exact(request.id),
            )
        )

    @app.post(
        "/v1/query/filter",
        response_model=SearchQueryResponse,
        response_model_by_alias=True,
    )
    def filter_query(request: FilterQueryRequest) -> SearchQueryResponse:
        package, adapter = pinned(request.expectedPackageChecksum)
        return SearchQueryResponse.model_validate(
            dict(
                packageRef=_ref(package.ref),
                objects=adapter.filter(
                    type_name=request.type,
                    limit=request.limit,
                ),
            )
        )

    @app.post(
        "/v1/query/relations",
        response_model=RelationsQueryResponse,
        response_model_by_alias=True,
    )
    def relations(
        request: RelationsQueryRequest,
    ) -> RelationsQueryResponse:
        package, adapter = pinned(request.expectedPackageChecksum)
        return RelationsQueryResponse.model_validate(
            dict(
                packageRef=_ref(package.ref),
                relations=adapter.relations(request.id, request.kinds),
            )
        )

    @app.post(
        "/v1/query/context",
        response_model=ContextQueryResponse,
        response_model_by_alias=True,
    )
    def context(request: ContextQueryRequest) -> ContextQueryResponse:
        package, adapter = pinned(request.expectedPackageChecksum)
        value = adapter.context(
            request_text=request.requestText,
            capability_id=request.capabilityId,
            phase=request.phase,
            element_types=request.elementTypes,
            max_objects=request.maxObjects,
            max_chars=request.maxChars,
        )
        return ContextQueryResponse.model_validate(
            dict(
                packageRef=_ref(package.ref),
                capabilities=value.capabilities,
                objects=value.objects,
                contentChars=value.content_chars,
            )
        )

    return app


app = create_app()
