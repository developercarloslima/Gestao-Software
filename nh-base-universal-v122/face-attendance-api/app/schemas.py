from pydantic import BaseModel, Field


class EmployeeRegisteredResponse(BaseModel):
    id: int
    external_id: str
    name: str
    status: str
    quality: float = Field(ge=0, le=1)
    encoding: list[float]


class EncodingRegistrationRequest(BaseModel):
    external_id: str = Field(min_length=3, max_length=80)
    name: str = Field(min_length=2, max_length=120)
    encoding: list[float] = Field(min_length=128, max_length=128)


class RecognitionResponse(BaseModel):
    id: int
    external_id: str
    name: str
    status: str
    distance: float = Field(ge=0)
    tolerance: float = Field(ge=0)
    quality: float = Field(ge=0, le=1)


class VerificationResponse(BaseModel):
    external_id: str
    matched: bool
    distance: float = Field(ge=0)
    tolerance: float = Field(ge=0)
    quality: float = Field(ge=0, le=1)
    status: str


class HealthResponse(BaseModel):
    status: str
    engine: str | None = None
