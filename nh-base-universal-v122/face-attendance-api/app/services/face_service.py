import io
import json
from dataclasses import dataclass
from typing import Sequence

import face_recognition
import numpy as np
from PIL import Image, ImageOps

from app.config import settings
from app.models import Employee


class FaceProcessingError(ValueError):
    """Erro de validação/processamento de uma imagem facial."""


@dataclass
class FaceAnalysis:
    encoding: np.ndarray
    quality: float
    face_count: int


def validate_image_content_type(content_type: str | None) -> None:
    allowed = {"image/jpeg", "image/jpg", "image/png", "image/webp"}
    if content_type not in allowed:
        raise FaceProcessingError("Formato de imagem inválido. Envie JPEG, PNG ou WEBP.")


def validate_image_size(image_bytes: bytes) -> None:
    if not image_bytes:
        raise FaceProcessingError("O arquivo de imagem está vazio.")
    if len(image_bytes) > settings.MAX_IMAGE_SIZE_BYTES:
        max_mb = settings.MAX_IMAGE_SIZE_BYTES // (1024 * 1024)
        raise FaceProcessingError(f"A imagem excede o limite permitido de {max_mb} MB.")


def _prepare_image(image_bytes: bytes) -> np.ndarray:
    try:
        image = Image.open(io.BytesIO(image_bytes))
        image = ImageOps.exif_transpose(image).convert("RGB")
        max_dimension = max(320, settings.MAX_IMAGE_DIMENSION)
        image.thumbnail((max_dimension, max_dimension), Image.Resampling.LANCZOS)
        return np.asarray(image)
    except Exception as exc:
        raise FaceProcessingError("Não foi possível ler a imagem enviada.") from exc


def _quality(image: np.ndarray, location: tuple[int, int, int, int]) -> float:
    top, right, bottom, left = location
    height, width = image.shape[:2]
    face_area = max(1, right - left) * max(1, bottom - top)
    area_ratio = face_area / max(1, width * height)
    size_score = min(1.0, max(0.0, area_ratio / 0.11))

    gray = image.mean(axis=2)
    brightness = float(gray.mean())
    contrast = float(gray.std())
    brightness_score = max(0.0, 1.0 - abs(brightness - 128.0) / 128.0)
    contrast_score = min(1.0, contrast / 55.0)
    return float(max(0.0, min(1.0, size_score * 0.65 + brightness_score * 0.15 + contrast_score * 0.20)))


def extract_single_face_analysis(image_bytes: bytes) -> FaceAnalysis:
    image = _prepare_image(image_bytes)
    try:
        face_locations = face_recognition.face_locations(image, model=settings.FACE_DETECTION_MODEL)
    except Exception as exc:
        raise FaceProcessingError("Falha durante a detecção facial.") from exc

    if len(face_locations) == 0:
        raise FaceProcessingError("Nenhum rosto foi detectado na imagem.")
    if len(face_locations) > 1:
        raise FaceProcessingError("Mais de um rosto foi detectado. Envie uma imagem com apenas uma pessoa.")

    encodings = face_recognition.face_encodings(
        image,
        known_face_locations=face_locations,
        num_jitters=1,
        model="small",
    )
    if len(encodings) != 1:
        raise FaceProcessingError("Não foi possível extrair o vetor facial da imagem.")

    encoding = np.asarray(encodings[0], dtype=np.float64)
    if encoding.shape != (128,):
        raise FaceProcessingError("O vetor facial gerado não possui 128 dimensões.")

    quality = _quality(image, face_locations[0])
    return FaceAnalysis(encoding=encoding, quality=quality, face_count=1)


def serialize_encoding(encoding: np.ndarray) -> str:
    return json.dumps(encoding.tolist(), separators=(",", ":"))


def deserialize_encoding(value: str) -> np.ndarray:
    try:
        encoding = np.asarray(json.loads(value), dtype=np.float64)
    except (TypeError, ValueError, json.JSONDecodeError) as exc:
        raise FaceProcessingError("Existe um vetor facial inválido armazenado no banco.") from exc
    if encoding.shape != (128,):
        raise FaceProcessingError("Existe um vetor facial com dimensão inválida armazenado no banco.")
    return encoding


def face_distance(reference: np.ndarray, unknown: np.ndarray) -> float:
    return float(face_recognition.face_distance([reference], unknown)[0])


def find_best_match(
    employees: Sequence[Employee],
    unknown_encoding: np.ndarray,
    tolerance: float | None = None,
) -> tuple[Employee | None, float | None]:
    if not employees:
        return None, None
    effective_tolerance = settings.FACE_TOLERANCE if tolerance is None else tolerance
    known_encodings = np.vstack([deserialize_encoding(employee.face_encoding) for employee in employees])
    distances = face_recognition.face_distance(known_encodings, unknown_encoding)
    best_index = int(np.argmin(distances))
    best_distance = float(distances[best_index])
    if best_distance <= effective_tolerance:
        return employees[best_index], best_distance
    return None, best_distance
