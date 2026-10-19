package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);


    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(new ApiError(
                OffsetDateTime.now(),
                exception.getStatusCode().value(),
                exception.getReason() == null ? "Não autorizado." : exception.getReason(),
                Map.of()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBusiness(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiError(
                OffsetDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage(),
                Map.of()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage())
        );
        return ResponseEntity.badRequest().body(new ApiError(
                OffsetDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Revise os dados informados.",
                errors
        ));
    }



    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException exception) {
        log.warn("Conflito de integridade ao salvar alteração administrativa", exception);
        String cause = exception.getMostSpecificCause() == null
                ? ""
                : String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
        String message;
        if (cause.contains("value too long") || cause.contains("too long for type")) {
            message = "Um dos dados informados ultrapassa o tamanho permitido. Revise os campos e tente novamente.";
        } else if (cause.contains("duplicate key") || cause.contains("unique constraint")) {
            message = "Já existe outro registro utilizando uma informação que precisa ser única. Atualize a tela e tente novamente.";
        } else {
            message = "Não foi possível salvar a alteração por um conflito de integridade dos dados. Atualize a tela e tente novamente.";
        }
        return ResponseEntity.badRequest().body(new ApiError(
                OffsetDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                message,
                Map.of()
        ));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadSize(MaxUploadSizeExceededException exception) {
        return ResponseEntity.badRequest().body(new ApiError(
                OffsetDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Cada arquivo enviado deve possuir no máximo 15 MB.",
                Map.of()
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleConfiguration(IllegalStateException exception) {
        log.error("Falha de infraestrutura ao processar a solicitação", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError(
                OffsetDateTime.now(),
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                exception.getMessage(),
                Map.of()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        log.error("Erro inesperado ao processar a solicitação", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
                OffsetDateTime.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Ocorreu um erro interno ao processar a solicitação.",
                Map.of()
        ));
    }
}
