package br.com.nh.cotacao.service;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import java.util.Properties;
import java.util.regex.Pattern;

/** Envio exclusivamente ao e-mail cadastrado; SMTP via segredos do ambiente. */
@Service
public class PasswordResetMailer {
    private static final Pattern EMAIL=Pattern.compile("^[^\\s@\\r\\n]+@[^\\s@\\r\\n]+\\.[^\\s@\\r\\n]+$");
    public void send(String recipient,String link){
        String host=env("SMTP_HOST"),from=env("SMTP_FROM");
        if(host.isBlank()||from.isBlank())
            throw new IllegalStateException("SMTP_HOST/SMTP_FROM não configurados para recuperação de senha.");
        if(recipient==null||!EMAIL.matcher(recipient).matches())
            throw new IllegalArgumentException("E-mail de recuperação inválido.");
        JavaMailSenderImpl mail=new JavaMailSenderImpl();
        mail.setHost(host);mail.setPort(Integer.parseInt(env("SMTP_PORT","587")));
        mail.setUsername(env("SMTP_USERNAME"));mail.setPassword(env("SMTP_PASSWORD"));
        mail.setDefaultEncoding("UTF-8");
        Properties props=mail.getJavaMailProperties();
        props.put("mail.smtp.auth",String.valueOf(!env("SMTP_USERNAME").isBlank()));
        props.put("mail.smtp.starttls.enable",env("SMTP_STARTTLS","true"));
        props.put("mail.smtp.connectiontimeout","5000");
        props.put("mail.smtp.timeout","5000");
        props.put("mail.smtp.writetimeout","5000");
        SimpleMailMessage msg=new SimpleMailMessage();
        msg.setFrom(from);msg.setTo(recipient);
        msg.setSubject("Redefinição de senha de acesso");
        msg.setText("Recebemos uma solicitação de redefinição de senha.\n\n"+
            "Use o endereço abaixo em até 20 minutos (uso único):\n"+link+
            "\n\nSe você não solicitou, ignore esta mensagem. Não compartilhe este link.");
        mail.send(msg);
    }
    private static String env(String key){return env(key,"");}
    private static String env(String key,String fallback){
        String v=System.getenv(key);return v==null?fallback:v.trim();
    }
}
