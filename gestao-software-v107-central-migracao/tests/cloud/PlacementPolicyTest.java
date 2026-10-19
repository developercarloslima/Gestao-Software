import br.com.gestaosoftware.control.service.CloudPlacementPolicy;
import java.util.*;

/** Executar usando javac/java puros, sem dependências Spring. */
public class PlacementPolicyTest {
  static int total=0;
  static void ok(boolean condition,String message) {total++;if(!condition)throw new AssertionError(message);}
  static CloudPlacementPolicy.Node node(String name,double cpu,double mem,double disk,double useCpu,double useMem,double useDisk,double committedCpu,double committedMem,double committedDisk,int unknown){
    return new CloudPlacementPolicy.Node(name,name,true,true,1,10,unknown,cpu,mem,disk,useCpu,useMem,useDisk,committedCpu,committedMem,committedDisk);
  }
  public static void main(String[] args){
    var d=new CloudPlacementPolicy.Demand(2,4,20);
    var good=CloudPlacementPolicy.evaluate(node("a",8,16,200,20,20,15,0,0,0,0),d);
    ok(good.eligible(),"Máquina com recursos livres deve ser elegível");
    ok(good.freeCpuAfter()>0,"Reserva de CPU computada");
    ok(!CloudPlacementPolicy.evaluate(node("b",2,4,100,5,5,5,0,0,0,0),d).eligible(),"Não ultrapassar 70/75%");
    ok(!CloudPlacementPolicy.evaluate(node("c",8,16,200,5,5,5,0,0,0,1),d).eligible(),"Clientes antigos não contabilizados bloqueiam");
    ok(!CloudPlacementPolicy.evaluate(node("d",8,16,200,85,10,10,0,0,0,0),d).eligible(),"Alta CPU bloqueia");
    ok(!CloudPlacementPolicy.evaluate(node("e",8,16,200,20,10,10,4,0,0,0),d).eligible(),"Reserva concorrente consome quota");
    var off=node("f",8,16,200,10,10,10,0,0,0,0);
    ok(!CloudPlacementPolicy.evaluate(new CloudPlacementPolicy.Node(off.id(),off.name(),true,false,0,10,0,8,16,200,0,0,0,0,0,0),d).eligible(),"Agente offline bloqueia");
    ok(!CloudPlacementPolicy.evaluate(new CloudPlacementPolicy.Node("full","full",true,true,10,10,0,8,16,200,0,0,0,0,0,0),d).eligible(),"Vagas esgotadas bloqueiam");
    var busy=CloudPlacementPolicy.evaluate(node("busy",8,16,200,60,60,60,0,0,0,0),d);
    ok(good.score()>busy.score(),"Priorizar host saudável");
    try {new CloudPlacementPolicy.Demand(Double.NaN,4,20);throw new AssertionError("Aceitou NaN");}catch(IllegalArgumentException expected){total++;}
    // Cenário de 50 VPS: uma com 100% de CPU, 48 sem recursos e uma saudável.
    int eligible=0;
    for(int i=0;i<50;i++){
      CloudPlacementPolicy.Node candidate=(i==18)
        ? node("vps-18",12,32,500,12,18,22,0,0,0,0)
        : node("vps-"+i,2,2,30,10,10,10,0,0,0,0);
      if(CloudPlacementPolicy.evaluate(candidate,d).eligible())eligible++;
    }
    ok(eligible==1,"Seleção encontra a única VPS adequada entre 50");
    System.out.println("OK: "+total+" cenários de política de posicionamento");
  }
}
