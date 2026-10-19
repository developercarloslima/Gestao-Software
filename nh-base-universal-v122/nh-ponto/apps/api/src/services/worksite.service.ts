import { prisma } from '../lib/prisma.js';

export const NH_OFFICES=[
  {sourceKey:'nh-taquarana',name:'Taquarana/AL',address:'Rua Pedro Rosa da Silva, 136 - Centro, Taquarana - AL, CEP 57640-000',city:'Taquarana',stateCode:'AL',postalCode:'57640-000',reference:'Ao lado do Posto Mirante'},
  {sourceKey:'nh-maceio',name:'Maceió/AL',address:'Avenida Comendador Francisco de Amorim Leão, 364, Sala 04 - Pinheiro, Maceió - AL, CEP 57057-780',city:'Maceió',stateCode:'AL',postalCode:'57057-780',reference:null},
  {sourceKey:'nh-departamento-comercial',name:'Departamento Comercial - Maceió/AL',address:'Rua Santos Pacheco, 94 - Centro, Maceió - AL, CEP 57020-290',city:'Maceió',stateCode:'AL',postalCode:'57020-290',reference:'Departamento Comercial'},
  {sourceKey:'nh-arapiraca',name:'Arapiraca/AL',address:'Rua Estudante José de Oliveira Leite, 1289 - Ouro Preto, Arapiraca - AL, CEP 57301-060',city:'Arapiraca',stateCode:'AL',postalCode:'57301-060',reference:'Esquina com Ladeira do Claudionor, dentro do Posto Shell'},
  {sourceKey:'nh-girau',name:'Girau do Ponciano/AL',address:'Avenida Tiradentes, s/nº - Centro, Girau do Ponciano - AL, CEP 57360-000',city:'Girau do Ponciano',stateCode:'AL',postalCode:'57360-000',reference:'Antes dos Correios, próximo à Assembleia de Deus'},
  {sourceKey:'nh-campo-alegre',name:'Campo Alegre/AL',address:'Avenida João Fernandes Vieira, 801 - Centro, Campo Alegre - AL, CEP 57250-000',city:'Campo Alegre',stateCode:'AL',postalCode:'57250-000',reference:null},
  {sourceKey:'nh-olivenca',name:'Olivença/AL',address:'Rua Padre Cícero, 57 - Centro, Olivença - AL, CEP 57550-000',city:'Olivença',stateCode:'AL',postalCode:'57550-000',reference:null},
  {sourceKey:'nh-teotonio-vilela',name:'Teotônio Vilela/AL',address:'Rua Pedro Cavalcante, s/nº - Centro, Teotônio Vilela - AL, CEP 57265-000',city:'Teotônio Vilela',stateCode:'AL',postalCode:'57265-000',reference:'Em frente ao INSS, próximo ao túnel'},
  {sourceKey:'nh-coruripe',name:'Coruripe/AL',address:'Avenida Floriano Peixoto, 241 - Centro, Coruripe - AL, CEP 57230-000',city:'Coruripe',stateCode:'AL',postalCode:'57230-000',reference:null},
  {sourceKey:'nh-sao-sebastiao',name:'São Sebastião/AL',address:'Avenida Antônio Custódio Pôrto, 67 - Centro, São Sebastião - AL, CEP 57275-000',city:'São Sebastião',stateCode:'AL',postalCode:'57275-000',reference:'Ao lado do Mercado do Jojo e em frente à Bela Uniformes'},
  {sourceKey:'nh-murici',name:'Murici/AL',address:'Avenida Doutor José Marques Luz, s/nº (Pista Nova), Murici - AL, CEP 57820-000',city:'Murici',stateCode:'AL',postalCode:'57820-000',reference:'Escritório ao lado da Farmácia Avenida'}
] as const;

export async function syncNovoHorizonteWorksites(tenantId:string){
  const tenant=await prisma.tenant.findUnique({where:{id:tenantId},select:{id:true,name:true}});
  if(!tenant||!/novo\s*horizonte/i.test(tenant.name))return {skipped:true,reason:'tenant_not_novo_horizonte',created:0,updated:0};
  let created=0,updated=0;
  for(const office of NH_OFFICES){
    let existing=await prisma.worksite.findFirst({where:{tenantId,OR:[{sourceKey:office.sourceKey},{name:office.name}]}});
    if(existing?.archivedAt)continue;
    if(!existing&&office.sourceKey==='nh-maceio'){
      const legacy=await prisma.worksite.findFirst({where:{tenantId,name:'Unidade Maceió',archivedAt:null}});
      if(legacy)existing=legacy;
    }
    const data={name:office.name,address:office.address,city:office.city,stateCode:office.stateCode,sourceKey:office.sourceKey,active:true,archivedAt:null};
    if(existing){
      const patch:any={sourceKey:existing.sourceKey??office.sourceKey};
      if(!existing.address)patch.address=office.address;
      if(!existing.city)patch.city=office.city;
      if(!existing.stateCode)patch.stateCode=office.stateCode;
      await prisma.worksite.update({where:{id:existing.id},data:patch});updated++;
    }else{
      await prisma.worksite.create({data:{tenantId,...data}});created++;
    }
  }

  const officialMaceio=await prisma.worksite.findFirst({where:{tenantId,sourceKey:'nh-maceio'}});
  if(officialMaceio){
    const legacyMaceio=await prisma.worksite.findFirst({where:{tenantId,name:'Unidade Maceió',id:{not:officialMaceio.id}}});
    if(legacyMaceio){
      await prisma.employee.updateMany({where:{tenantId,worksiteId:legacyMaceio.id},data:{worksiteId:officialMaceio.id}});
      await prisma.worksite.update({where:{id:legacyMaceio.id},data:{active:false}});
    }
  }
  const officialCommercial=await prisma.worksite.findFirst({where:{tenantId,sourceKey:'nh-departamento-comercial',archivedAt:null}});
  if(officialCommercial){
    const legacyCommercials=await prisma.worksite.findMany({where:{tenantId,archivedAt:null,id:{not:officialCommercial.id},OR:[
      {name:{contains:'DEP. COMERCIAL MCZ',mode:'insensitive'}},
      {name:{contains:'DEPARTAMENTO COMERCIAL MCZ',mode:'insensitive'}}
    ]}});
    for(const legacy of legacyCommercials){
      await prisma.employee.updateMany({where:{tenantId,worksiteId:legacy.id},data:{worksiteId:officialCommercial.id}});
      await prisma.worksite.update({where:{id:legacy.id},data:{active:false,archivedAt:new Date()}});
    }
  }

  await prisma.attendanceSecuritySettings.upsert({
    where:{tenantId},
    update:{requireGeofence:false,requireAccurateGps:false},
    create:{tenantId,requireSelfie:true,requireFaceMatch:false,requireLiveness:false,requireAntiSpoof:false,requirePlatformBiometric:false,blockWithoutEnrollment:false,blockOnFaceFailure:false,requireGeofence:false,requireAccurateGps:false,allowOffline:false,offlineRequiresReview:true}
  });
  return {skipped:false,created,updated};
}
