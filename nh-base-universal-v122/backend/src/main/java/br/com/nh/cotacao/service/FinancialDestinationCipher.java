package br.com.nh.cotacao.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM. Nenhuma conta ou chave Pix é persistida em texto puro. */
@Component
public class FinancialDestinationCipher {
    private final String encodedKey;
    private final SecureRandom random = new SecureRandom();

    public FinancialDestinationCipher(@Value("${app.company-payout.encryption-key:}") String encodedKey) {
        this.encodedKey = encodedKey == null ? "" : encodedKey.trim();
    }

    private SecretKeySpec key() {
        if (encodedKey.isBlank()) throw new IllegalStateException("Configure a chave de proteção dos dados bancários no servidor.");
        try {
            byte[] bytes = Base64.getDecoder().decode(encodedKey);
            if (bytes.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("A chave de proteção bancária deve conter 32 bytes em Base64.");
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
            return "v1:" + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível proteger os dados de recebimento.", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || !ciphertext.startsWith("v1:"))
            throw new IllegalStateException("Versão de criptografia bancária não reconhecida.");
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext.substring(3));
            if (combined.length <= 28) throw new IllegalArgumentException();
            byte[] nonce = java.util.Arrays.copyOfRange(combined, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(combined, 12, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível ler os dados bancários protegidos.", e);
        }
    }
}
