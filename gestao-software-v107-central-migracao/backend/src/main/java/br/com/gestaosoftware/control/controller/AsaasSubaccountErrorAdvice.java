package br.com.gestaosoftware.control.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** Retorna o erro controlado deste fluxo sem transformar validação Asaas em erro de gateway. */
@RestControllerAdvice(assignableTypes = AsaasSubaccountController.class)
public class AsaasSubaccountErrorAdvice {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> validation(ResponseStatusException ex) {
        String message = ex.getReason() == null
                ? "Não foi possível processar o cadastro da subconta."
                : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", message));
    }
}
