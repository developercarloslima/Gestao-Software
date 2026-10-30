package br.com.nh.cotacao.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Recuperação de senha via Gmail API HTTPS (compatível com Render Free).
 * Nenhum token ou credencial é incluído em URLs, logs ou respostas ao cliente.
 * Requer OAuth offline, escopo mínimo https://www.googleapis.com/auth/gmail.send.
 */
@Service
public class PasswordResetMailer {
    private static final Pattern EMAIL =
        Pattern.compile("^[^\\s@\\r\\n]+@[^\\s@\\r\\n]+\\.[^\\s@\\r\\n]+$");
    private static final URI TOKEN_URL=URI.create("https://oauth2.googleapis.com/token");
    private static final URI SEND_URL=URI.create("https://gmail.googleapis.com/gmail/v1/users/me/messages/send");
    private final HttpClient http=HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    public PasswordResetMailer(ObjectMapper json){this.json=json;}

    public void send(String recipient,String link){
        String sender=env("GMAIL_SENDER", env("SMTP_FROM",""));
        String clientId=env("GMAIL_CLIENT_ID","");
        String clientSecret=env("GMAIL_CLIENT_SECRET","");
        String refreshToken=env("GMAIL_REFRESH_TOKEN","");
        if(clientId.isBlank()||clientSecret.isBlank()||refreshToken.isBlank())
            throw new IllegalStateException("Credenciais OAuth da Gmail API não configuradas no Render.");
        if(!validAddress(recipient)||!validAddress(sender))
            throw new IllegalArgumentException("Destinatário ou remetente Gmail inválido.");
        if(link==null||!link.startsWith("https://"))
            throw new IllegalArgumentException("Link HTTPS de recuperação inválido.");
        try {
            String form="client_id="+encode(clientId)
                +"&client_secret="+encode(clientSecret)
                +"&refresh_token="+encode(refreshToken)
                +"&grant_type=refresh_token";
            HttpRequest request=HttpRequest.newBuilder(TOKEN_URL)
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> tokenResponse=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(tokenResponse.statusCode()!=200)
                throw new IllegalStateException("Falha na autorização Gmail (HTTP "+tokenResponse.statusCode()+").");
            JsonNode tokenPayload=json.readTree(tokenResponse.body());
            String accessToken=tokenPayload.path("access_token").asText("");
            if(accessToken.isBlank()||tokenPayload.path("token_type").asText("Bearer")
                    .toLowerCase(Locale.ROOT).contains("error"))
                throw new IllegalStateException("Gmail não retornou token de acesso.");
            String rawMessage=Base64.getUrlEncoder().withoutPadding().encodeToString(
                mimeMessage(sender,recipient,link).getBytes(StandardCharsets.UTF_8));
            HttpRequest send=HttpRequest.newBuilder(SEND_URL)
                .timeout(Duration.ofSeconds(15))
                .header("Authorization","Bearer "+accessToken)
                .header("Content-Type","application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(Map.of("raw",rawMessage)),StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> sent=http.send(send,HttpResponse.BodyHandlers.ofString());
            if(sent.statusCode()!=200)
                throw new IllegalStateException("Gmail recusou o envio (HTTP "+sent.statusCode()+").");
            if(json.readTree(sent.body()).path("id").asText("").isBlank())
                throw new IllegalStateException("Gmail não confirmou o identificador da mensagem.");
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Envio por Gmail interrompido.",e);
        }catch(java.io.IOException e){
            throw new IllegalStateException("Falha de conexão HTTPS com a Gmail API.",e);
        }
    }

    static String mimeMessage(String sender,String recipient,String link){
        if(!validAddress(sender)||!validAddress(recipient))
            throw new IllegalArgumentException("Endereço de e-mail inválido.");
        if(link==null||!link.startsWith("https://")||link.contains("\r")||link.contains("\n"))
            throw new IllegalArgumentException("Link de recuperação inválido.");
        String subject=Base64.getEncoder().encodeToString(
            "Redefinição de senha de acesso".getBytes(StandardCharsets.UTF_8));
        String body="Recebemos uma solicitação de redefinição de senha.\n\n"
            +"Utilize este link em até 20 minutos (uso único):\n"+link
            +"\n\nSe você não solicitou a alteração, ignore esta mensagem. "
            +"Nunca compartilhe o link.";
        String encodedBody=Base64.getMimeEncoder(76,new byte[]{'\r','\n'})
            .encodeToString(body.getBytes(StandardCharsets.UTF_8));
        return "From: "+sender+"\r\n"
            +"To: "+recipient+"\r\n"
            +"Subject: =?UTF-8?B?"+subject+"?=\r\n"
            +"MIME-Version: 1.0\r\n"
            +"Content-Type: text/plain; charset=UTF-8\r\n"
            +"Content-Transfer-Encoding: base64\r\n"
            +"\r\n"+encodedBody+"\r\n";
    }

    private static boolean validAddress(String value){
        return value!=null&&EMAIL.matcher(value).matches();
    }
    private static String encode(String value){
        return URLEncoder.encode(value,StandardCharsets.UTF_8);
    }
    private static String env(String key,String fallback){
        String v=System.getenv(key);
        return v==null?fallback:v.trim();
    }
}
