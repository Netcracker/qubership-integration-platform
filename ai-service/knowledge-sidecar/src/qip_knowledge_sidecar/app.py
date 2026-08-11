from __future__ import annotations

import os
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import AsyncIterator

from fastapi import FastAPI
from fastapi.responses import JSONResponse
from knowledge_sdk import KnowledgeSDK

from qip_knowledge_sidecar.models import (
    ContextQueryRequest,
    ContextQueryResponse,
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


def _close_quietly(sdk: KnowledgeSDK | None) -> None:
    if sdk is None:
        return
    try:
        sdk.close()
    except Exception:
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


def create_app(package_path: Path | None = None) -> FastAPI:
    resolved_package = package_path or Path(os.getenv("QIP_KNOWLEDGE_PATH", "/knowledge"))
    state = AppState()

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
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

    app = FastAPI(lifespan=lifespan)

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
                message=(
                    "expectedPackageChecksum does not match the active package"
                ),
                status=409,
                retryable=False,
            )
        return package, adapter

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
        return ExactQueryResponse(
            packageRef=_ref(package.ref),
            object=adapter.exact(request.id),
        )

    @app.post(
        "/v1/query/filter",
        response_model=SearchQueryResponse,
        response_model_by_alias=True,
    )
    def filter_query(request: FilterQueryRequest) -> SearchQueryResponse:
        package, adapter = pinned(request.expectedPackageChecksum)
        return SearchQueryResponse(
            packageRef=_ref(package.ref),
            objects=adapter.filter(
                type_name=request.type,
                limit=request.limit,
            ),
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
        return RelationsQueryResponse(
            packageRef=_ref(package.ref),
            relations=adapter.relations(request.id, request.kinds),
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
        return ContextQueryResponse(
            packageRef=_ref(package.ref),
            capabilities=value.capabilities,
            objects=value.objects,
            contentChars=value.content_chars,
        )

    return app


app = create_app()
