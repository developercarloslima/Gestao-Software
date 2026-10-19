import os
from dataclasses import dataclass


def _float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, str(default)))
    except ValueError:
        return default


def _int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    DATABASE_URL: str = os.getenv("DATABASE_URL", "sqlite:///./face_attendance.db")
    FACE_TOLERANCE: float = _float("FACE_TOLERANCE", 0.50)
    FACE_DETECTION_MODEL: str = os.getenv("FACE_DETECTION_MODEL", "hog")
    MAX_IMAGE_SIZE_BYTES: int = _int("MAX_IMAGE_SIZE_BYTES", 6 * 1024 * 1024)
    MAX_IMAGE_DIMENSION: int = _int("MAX_IMAGE_DIMENSION", 720)
    MIN_FACE_QUALITY: float = _float("MIN_FACE_QUALITY", 0.42)
    API_KEY: str = os.getenv("FACE_API_KEY", "")


settings = Settings()
