package br.com.gestaosoftware.control.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@Component
public class ProvisioningAgentAuth {
 private final String master;
 public ProvisioningAgentAuth(@Value("${app.provisioning.worker-token:}") String master){this.master=master;}
 public void requireAgent(String supplied,UUID vpsId){
  if(vpsId==null || supplied==null || master==null || master.length()<32)
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Agente não autorizado.");
  try{
    Mac mac=Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(master.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
    byte[] actual=mac.doFinal(vpsId.toString().getBytes(StandardCharsets.UTF_8));
    StringBuilder expected=new StringBuilder(64);
    for(byte b:actual)expected.append(String.format("%02x",b&255));
    if(!MessageDigest.isEqual(expected.toString().getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Agente não autorizado.");
  }catch(ResponseStatusException e){throw e;}
  catch(Exception e){throw new IllegalStateException("Erro ao verificar identidade do agente.");}
 }
}
