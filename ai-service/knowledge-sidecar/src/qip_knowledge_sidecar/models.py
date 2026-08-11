from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class CanonicalRelationModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    from_id: str = Field(alias="from")
    kind: str
    to_id: str = Field(alias="to")
    attributes: dict[str, Any] = Field(default_factory=dict)


class CanonicalContentModel(BaseModel):
    format: str
    body: str | None
    raw: str | None
    sections: list[dict[str, Any]] = Field(default_factory=list)


class CanonicalSourceModel(BaseModel):
    format: str
    document: str | None
    section_id: str | None
    hash: str | None
    knowledge_version: str | None


class CanonicalObjectModel(BaseModel):
    ir_version: Literal["1.0"]
    id: str
    type: str
    title: str
    summary: str
    metadata: dict[str, Any]
    relations: list[CanonicalRelationModel]
    content: CanonicalContentModel
    version: str
    status: str
    source: CanonicalSourceModel


class PackageRefModel(BaseModel):
    packageKey: str
    knowledgeVersion: str
    schemaVersion: str
    packageChecksum: str
    certificationStatus: Literal["CERTIFIED"]
    certificationDigest: str


class PinnedRequest(BaseModel):
    expectedPackageChecksum: str


class ExactQueryRequest(PinnedRequest):
    id: str


class FilterQueryRequest(PinnedRequest):
    type: str | None = None
    limit: int = Field(default=20, ge=1, le=100)


class RelationsQueryRequest(PinnedRequest):
    id: str
    kinds: list[str] = Field(default_factory=list)


class ContextQueryRequest(PinnedRequest):
    requestText: str
    capabilityId: str
    phase: str
    elementTypes: list[str] = Field(default_factory=list)
    maxObjects: int = Field(default=12, ge=1, le=50)
    maxChars: int = Field(default=20_000, ge=1, le=100_000)


class PackageResponse(BaseModel):
    packageRef: PackageRefModel


class ExactQueryResponse(BaseModel):
    packageRef: PackageRefModel
    object: CanonicalObjectModel


class SearchQueryResponse(BaseModel):
    packageRef: PackageRefModel
    objects: list[CanonicalObjectModel]


class RelationsQueryResponse(BaseModel):
    packageRef: PackageRefModel
    relations: list[CanonicalRelationModel]


class ContextQueryResponse(BaseModel):
    packageRef: PackageRefModel
    capabilities: list[str]
    objects: list[CanonicalObjectModel]
    contentChars: int
