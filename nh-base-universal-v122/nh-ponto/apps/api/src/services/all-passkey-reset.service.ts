import { prisma } from '../lib/prisma.js';

const ACTION='V8323_NH_PONTO_PASSKEY_RESET_ADMIN_HR_ONLY';

export async function resetAllPasskeysOnce(){
  const alreadyApplied=await prisma.auditEvent.findFirst({where:{action:ACTION},select:{id:true}});
  if(alreadyApplied)return {alreadyApplied:true,tenantsReset:0,credentialsDeleted:0,challengesDeleted:0};

  const tenants=await prisma.tenant.findMany({select:{id:true}});
  if(!tenants.length)return {alreadyApplied:false,tenantsReset:0,credentialsDeleted:0,challengesDeleted:0};

  let credentialsDeleted=0,challengesDeleted=0;
  await prisma.$transaction(async tx=>{
    const deletedCredentials=await tx.webAuthnCredential.deleteMany({});
    const deletedChallenges=await tx.webAuthnChallenge.deleteMany({});
    credentialsDeleted=deletedCredentials.count;
    challengesDeleted=deletedChallenges.count;
    for(const tenant of tenants){
      await tx.auditEvent.create({data:{tenantId:tenant.id,action:ACTION,entityType:'Tenant',entityId:tenant.id,metadataJson:{credentialsDeleted:deletedCredentials.count,challengesDeleted:deletedChallenges.count,reason:'V83.23: colaboradores deixam de usar passkey; credenciais antigas são zeradas e somente ADMIN/RH cadastram novamente no próprio painel'}}});
    }
  });
  return {alreadyApplied:false,tenantsReset:tenants.length,credentialsDeleted,challengesDeleted};
}
