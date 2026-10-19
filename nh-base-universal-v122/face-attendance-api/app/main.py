from fastapi import FastAPI

from app.database import Base, engine
from app.routers.biometrics import router as biometrics_router
from app.schemas import HealthResponse

Base.metadata.create_all(bind=engine)

app = FastAPI(
    title="NH Face Attendance API",
    description="Serviço interno de cadastro e reconhecimento facial do Apontamento Digital.",
    version="1.1.0",
    docs_url=None,
    redoc_url=None,
)
app.include_router(biometrics_router)


@app.get("/health", response_model=HealthResponse, tags=["Health"])
def health() -> HealthResponse:
    return HealthResponse(status="ok", engine="face_recognition/dlib")
