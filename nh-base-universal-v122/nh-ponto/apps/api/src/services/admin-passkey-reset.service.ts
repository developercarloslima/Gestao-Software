import { Role } from '@prisma/client';
import { prisma } from '../lib/prisma.js';

const ACTION='V8320_ADMIN_PASSKEY_RESET';

export async function resetAdminPasskeysOnce(){
  const tenants=await prisma.tenant.findMany({select:{id:true}});
  let tenantsReset=0,credentialsDeleted=0;
  for(const tenant of tenants){
    const marker=await prisma.auditEvent.findFirst({where:{tenantId:tenant.id,action:ACTION,entityType:'Tenant',entityId:tenant.id},select:{id:true}});
    if(marker)continue;
    await prisma.$transaction(async tx=>{
      const admins=await tx.user.findMany({where:{tenantId:tenant.id,role:Role.ADMIN},select:{id:true}});
      const ids=admins.map(x=>x.id);
      if(ids.length){
        const deleted=await tx.webAuthnCredential.deleteMany({where:{tenantId:tenant.id,userId:{in:ids}}});
        credentialsDeleted+=deleted.count;
        await tx.webAuthnChallenge.deleteMany({where:{tenantId:tenant.id,userId:{in:ids}}});
        await tx.user.updateMany({where:{tenantId:tenant.id,id:{in:ids}},data:{onboardingCompletedAt:null}});
      }
      await tx.auditEvent.create({data:{tenantId:tenant.id,action:ACTION,entityType:'Tenant',entityId:tenant.id,metadataJson:{adminUsers:ids.length,reason:'Forçar novo cadastro da passkey principal do ADMIN após V83.20'}}});
    });
    tenantsReset++;
  }
  return {tenantsReset,credentialsDeleted};
}
