import secrets
from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, UploadFile, status
from fastapi.concurrency import run_in_threadpool
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import settings
from app.database import get_db
from app.models import Employee
from app.schemas import EncodingRegistrationRequest, EmployeeRegisteredResponse, RecognitionResponse, VerificationResponse
from app.services.face_service import (
    FaceProcessingError,
    deserialize_encoding,
    extract_single_face_analysis,
    face_distance,
    find_best_match,
    serialize_encoding,
    validate_image_content_type,
    validate_image_size,
)

router = APIRouter(prefix="/api/v1", tags=["Biometria facial"])


def require_api_key(x_api_key: Annotated[str | None, Header()] = None) -> None:
    if not settings.API_KEY:
        return
    if not x_api_key or not secrets.compare_digest(x_api_key, settings.API_KEY):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Credencial interna inválida.")


async def read_and_validate_upload(image: UploadFile) -> bytes:
    validate_image_content_type(image.content_type)
    content = await image.read()
    validate_image_size(content)
    return content


@router.post("/register", response_model=EmployeeRegisteredResponse, dependencies=[Depends(require_api_key)])
async def register_employee(
    external_id: Annotated[str, Form(min_length=3, max_length=80)],
    name: Annotated[str, Form(min_length=2, max_length=120)],
    image: Annotated[UploadFile, File(...)],
    db: Annotated[Session, Depends(get_db)],
):
    clean_name = name.strip()
    clean_external_id = external_id.strip()
    try:
        image_bytes = await read_and_validate_upload(image)
        analysis = await run_in_threadpool(extract_single_face_analysis, image_bytes)
    except FaceProcessingError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    finally:
        await image.close()

    if analysis.quality < settings.MIN_FACE_QUALITY:
        raise HTTPException(status_code=400, detail="A selfie está com pouca qualidade. Melhore a iluminação, centralize o rosto e tente novamente.")

    employee = db.scalar(select(Employee).where(Employee.external_id == clean_external_id))
    if employee is None:
        employee = Employee(external_id=clean_external_id, name=clean_name, face_encoding=serialize_encoding(analysis.encoding))
        db.add(employee)
    else:
        employee.name = clean_name
        employee.face_encoding = serialize_encoding(analysis.encoding)
    db.commit()
    db.refresh(employee)

    return EmployeeRegisteredResponse(
        id=employee.id,
        external_id=employee.external_id,
        name=employee.name,
        status="Referência facial cadastrada com sucesso",
        quality=analysis.quality,
        encoding=analysis.encoding.tolist(),
    )


@router.post("/register-encoding", dependencies=[Depends(require_api_key)])
def register_employee_encoding(payload: EncodingRegistrationRequest, db: Annotated[Session, Depends(get_db)]):
    import numpy as np
    encoding = np.asarray(payload.encoding, dtype=np.float64)
    if encoding.shape != (128,) or not np.all(np.isfinite(encoding)):
        raise HTTPException(status_code=400, detail="Embedding facial inválido.")
    external_id = payload.external_id.strip()
    employee = db.scalar(select(Employee).where(Employee.external_id == external_id))
    if employee is None:
        employee = Employee(external_id=external_id, name=payload.name.strip(), face_encoding=serialize_encoding(encoding))
        db.add(employee)
    else:
        employee.name = payload.name.strip()
        employee.face_encoding = serialize_encoding(encoding)
    db.commit()
    db.refresh(employee)
    return {"ok": True, "id": employee.id, "external_id": employee.external_id}


@router.post("/verify", response_model=VerificationResponse, dependencies=[Depends(require_api_key)])
async def verify_employee(
    external_id: Annotated[str, Form(min_length=3, max_length=80)],
    image: Annotated[UploadFile, File(...)],
    db: Annotated[Session, Depends(get_db)],
):
    employee = db.scalar(select(Employee).where(Employee.external_id == external_id.strip()))
    if employee is None:
        raise HTTPException(status_code=404, detail={"code": "EMPLOYEE_NOT_REGISTERED", "message": "Referência facial ainda não cadastrada nesta API."})
    try:
        image_bytes = await read_and_validate_upload(image)
        analysis = await run_in_threadpool(extract_single_face_analysis, image_bytes)
        distance = await run_in_threadpool(face_distance, deserialize_encoding(employee.face_encoding), analysis.encoding)
    except FaceProcessingError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    finally:
        await image.close()

    matched = distance <= settings.FACE_TOLERANCE and analysis.quality >= settings.MIN_FACE_QUALITY
    return VerificationResponse(
        external_id=employee.external_id,
        matched=matched,
        distance=round(distance, 4),
        tolerance=settings.FACE_TOLERANCE,
        quality=analysis.quality,
        status="Reconhecimento confirmado" if matched else "Rosto não corresponde à referência cadastrada",
    )


@router.delete("/employees/{external_id}", dependencies=[Depends(require_api_key)])
def delete_employee(external_id: str, db: Annotated[Session, Depends(get_db)]):
    employee = db.scalar(select(Employee).where(Employee.external_id == external_id.strip()))
    if employee is None:
        return {"ok": True, "deleted": False}
    db.delete(employee)
    db.commit()
    return {"ok": True, "deleted": True}


@router.post("/recognize", response_model=RecognitionResponse, dependencies=[Depends(require_api_key)])
async def recognize_employee(
    image: Annotated[UploadFile, File(...)],
    db: Annotated[Session, Depends(get_db)],
    exclude_external_id_prefix: Annotated[str | None, Form(max_length=40)] = None,
):
    employees = list(db.scalars(select(Employee)).all())
    if exclude_external_id_prefix:
        prefix = exclude_external_id_prefix.strip()
        if prefix:
            employees = [employee for employee in employees if not employee.external_id.startswith(prefix)]
    if not employees:
        raise HTTPException(status_code=404, detail="Nenhum funcionário cadastrado para reconhecimento.")
    try:
        image_bytes = await read_and_validate_upload(image)
        analysis = await run_in_threadpool(extract_single_face_analysis, image_bytes)
        employee, best_distance = await run_in_threadpool(find_best_match, employees, analysis.encoding, settings.FACE_TOLERANCE)
    except FaceProcessingError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    finally:
        await image.close()
    if employee is None:
        raise HTTPException(status_code=404, detail={"status": "Funcionário não encontrado", "best_distance": round(best_distance, 4) if best_distance is not None else None, "tolerance": settings.FACE_TOLERANCE})
    return RecognitionResponse(id=employee.id, external_id=employee.external_id, name=employee.name, status="Reconhecimento bem-sucedido", distance=round(float(best_distance), 4), tolerance=settings.FACE_TOLERANCE, quality=analysis.quality)
