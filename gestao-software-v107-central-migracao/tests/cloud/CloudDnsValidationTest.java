import br.com.gestaosoftware.control.service.CloudDnsValidation;
public class CloudDnsValidationTest {
 public static void main(String[] args) {
   int checks=0;
   for(String ip:new String[]{"127.0.0.1","10.1.1.1","192.168.1.2","172.16.4.5","169.254.2.2","100.64.0.1","192.0.2.1","203.0.113.10","198.18.0.1","999.2.3.4","01.2.3.4"}){
     try{CloudDnsValidation.requirePublicIpv4(ip);throw new AssertionError("IP reservado aceito: "+ip);}catch(IllegalArgumentException e){checks++;}
   }
   if(!CloudDnsValidation.requirePublicIpv4("8.8.8.8").equals("8.8.8.8"))throw new AssertionError("IP público recusado");checks++;
   if(!CloudDnsValidation.requireHost("cliente.gestaosoftware.com.br","gestaosoftware.com.br").startsWith("cliente"))throw new AssertionError();checks++;
   for(String host:new String[]{"cliente.gestaosoftware.com.br.attacker.com","cliente..gestaosoftware.com.br","-cliente.gestaosoftware.com.br"}){
     try{CloudDnsValidation.requireHost(host,"gestaosoftware.com.br");throw new AssertionError("Host inválido aceito "+host);}catch(IllegalArgumentException e){checks++;}
   }
   System.out.println("OK: "+checks+" cenários de validação DNS");
 }
}
