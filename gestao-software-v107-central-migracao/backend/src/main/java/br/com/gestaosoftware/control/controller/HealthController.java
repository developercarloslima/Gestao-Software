package br.com.gestaosoftware.control.controller;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController public class HealthController { @GetMapping("/api/health") public Map<String,Object> health(){return Map.of("status","ok","service","gestao-software-control-plane");} }
