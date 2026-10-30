package br.com.nh.cotacao.service;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class PasswordResetMailerTest {
    @Test void encodesUtf8AndIsValidMime() {
        String message=PasswordResetMailer.mimeMessage(
            "gestaosoftware2026@gmail.com",
            "tester@example.com",
            "https://example.com/admin/redefinir-senha.html?token=123");
        assertTrue(message.contains("From: gestaosoftware2026@gmail.com\r\n"));
        assertTrue(message.contains("To: tester@example.com\r\n"));
        assertTrue(message.contains("Subject: =?UTF-8?B?"));
        assertTrue(message.contains("Content-Transfer-Encoding: base64"));
        String encoded=message.split("\\r\\n\\r\\n",2)[1];
        String decoded=new String(Base64.getMimeDecoder().decode(encoded),StandardCharsets.UTF_8);
        assertTrue(decoded.contains("https://example.com/admin/redefinir-senha.html?token=123"));
        assertTrue(decoded.contains("solicitação"));
    }
    @Test void rejectsHeaderInjection() {
        assertThrows(IllegalArgumentException.class,()-> PasswordResetMailer.mimeMessage(
          "gestaosoftware2026@gmail.com","victim@example.com\r\nBcc: attacker@example.com",
          "https://example.com/reset"));
    }
    @Test void rejectsNonHttpsTokenLink() {
        assertThrows(IllegalArgumentException.class,()->PasswordResetMailer.mimeMessage(
          "gestaosoftware2026@gmail.com","tester@example.com","http://example.com/reset"));
    }
}
