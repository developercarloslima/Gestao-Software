package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.service.PublicAcceptancePaymentService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** O token forte da própria vistoria é a autorização da consulta após o aceite. */
@RestController
public class PublicAcceptancePaymentController {
    private final PublicAcceptancePaymentService payments;

    public PublicAcceptancePaymentController(PublicAcceptancePaymentService payments) {
        this.payments = payments;
    }

    @GetMapping("/api/public/inspections/{token}/adhesion-payment")
    public ResponseEntity<PublicAcceptancePaymentService.PaymentView> afterAcceptance(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payments.findAdhesion(token));
    }
}
