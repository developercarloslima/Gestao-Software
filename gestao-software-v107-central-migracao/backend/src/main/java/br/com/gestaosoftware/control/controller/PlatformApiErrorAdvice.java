package br.com.gestaosoftware.control.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** Apenas erros de negócio explicitamente controlados da administração. */
@RestControllerAdvice(assignableTypes={PlatformManagementController.class})
public class PlatformApiErrorAdvice {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String,String>> business(ResponseStatusException ex){
    String message=ex.getReason()==null ? "Operação não permitida." : ex.getReason();
    return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message",message));
  }
}
