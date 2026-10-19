package br.com.gestaosoftware.control.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** Exibe ao operador mensagens de validação já definidas no domínio de implantação.
 * Erros internos inesperados continuam com mensagem genérica do Spring Boot.
 */
@RestControllerAdvice(assignableTypes={CloudInfrastructureController.class, ProvisioningController.class})
public class CloudApiErrorAdvice {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String,String>> validation(ResponseStatusException ex){
    String message=ex.getReason()==null?"Não foi possível concluir. Confira os dados e tente novamente.":ex.getReason();
    return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", message));
  }
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException ex){
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message","Os dados informados são inválidos. Revise os campos destacados."));
  }
}
