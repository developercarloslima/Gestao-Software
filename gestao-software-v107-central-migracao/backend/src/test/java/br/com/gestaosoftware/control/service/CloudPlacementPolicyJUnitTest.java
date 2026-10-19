package br.com.gestaosoftware.control.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CloudPlacementPolicyJUnitTest {
 private static CloudPlacementPolicy.Node node(String id, int count, double committedCpu) {
  return new CloudPlacementPolicy.Node(id,id,true,true,count,10,0,8,16,200,
      18,22,20,committedCpu,0,0);
 }
 @Test void selectsOnlyEligibleNodeAmongFifty() {
  var demand=new CloudPlacementPolicy.Demand(2,4,25);
  int count=0;
  for(int i=0;i<50;i++){
    var entry=i==18?node("vps18",1,0):node("vps"+i,10,0);
    if(CloudPlacementPolicy.evaluate(entry,demand).eligible())count++;
  }
  assertEquals(1,count);
 }
 @Test void rejectsAlreadyReservedCapacity() {
  var result=CloudPlacementPolicy.evaluate(node("busy",1,5),new CloudPlacementPolicy.Demand(2,4,25));
  assertFalse(result.eligible());
  assertTrue(result.reasons().stream().anyMatch(x->x.contains("vCPU")));
 }
 @Test void preventsProvisioningOnUnreconciledServer() {
  var n=node("legacy",1,0);
  var legacy=new CloudPlacementPolicy.Node(n.id(),n.name(),true,true,1,10,1,
      n.cpuCores(),n.ramGb(),n.diskGb(),n.cpuUsage(),n.memoryUsage(),n.diskUsage(),0,0,0);
  assertFalse(CloudPlacementPolicy.evaluate(legacy,new CloudPlacementPolicy.Demand(2,4,25)).eligible());
 }
 @Test void protectsCredentialsAndNewCompanyOnInvalidMetrics() {
  var n=node("unknown",1,0);
  var bad=new CloudPlacementPolicy.Node(n.id(),n.name(),true,true,1,10,0,
      n.cpuCores(),n.ramGb(),n.diskGb(),Double.NaN,n.memoryUsage(),n.diskUsage(),0,0,0);
  assertFalse(CloudPlacementPolicy.evaluate(bad,new CloudPlacementPolicy.Demand(2,4,25)).eligible());
 }
}
