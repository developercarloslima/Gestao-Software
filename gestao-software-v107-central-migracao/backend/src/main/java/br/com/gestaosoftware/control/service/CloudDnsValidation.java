package br.com.gestaosoftware.control.service;

import java.net.InetAddress;
import java.util.regex.Pattern;

/** Restrições rígidas para criação de registros A públicos. Sem DNS externo nem rede local. */
public final class CloudDnsValidation {
 private CloudDnsValidation() {}
 public static String requirePublicIpv4(String ip){
   if(ip==null || !Pattern.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}",ip))
      throw new IllegalArgumentException("É necessário um IPv4 público configurado na VPS.");
   String[] parts=ip.split("\\.");
   int[] a=new int[4];
   for(int i=0;i<4;i++){
      a[i]=Integer.parseInt(parts[i]);
      if(a[i]>255 || (parts[i].length()>1 && parts[i].startsWith("0")))
         throw new IllegalArgumentException("Endereço IPv4 inválido.");
   }
   if(a[0]==0||a[0]==10||a[0]==127||a[0]>=224||a[0]==169&&a[1]==254||a[0]==172&&a[1]>=16&&a[1]<=31
      ||a[0]==192&&a[1]==168||a[0]==100&&a[1]>=64&&a[1]<=127||a[0]==192&&a[1]==0
      ||a[0]==192&&a[1]==0&&a[2]==2||a[0]==198&&a[1]==51&&a[2]==100
      ||a[0]==203&&a[1]==0&&a[2]==113||a[0]==198&&a[1]>=18&&a[1]<=19)
        throw new IllegalArgumentException("Endereço privado ou reservado não permitido como DNS público.");
   return ip;
 }
 public static String requireHost(String host,String suffix){
    if(host==null || suffix==null || host.length()>240 || !host.endsWith("."+suffix)
       || !host.matches("[a-z0-9](?:[a-z0-9-]{1,46}[a-z0-9])\\.[a-z0-9-]+(?:\\.[a-z0-9-]+)+"))
      throw new IllegalArgumentException("Subdomínio fora da zona permitida.");
    return host;
 }
}
