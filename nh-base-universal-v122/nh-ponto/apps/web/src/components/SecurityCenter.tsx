import { useEffect,useState } from 'react';
import { api,getCachedUser } from '../lib/api';
import { enrollPlatformBiometric,supportsWebAuthn } from '../lib/webauthn';

export default function SecurityCenter(){
 const user=getCachedUser();const allowed=['SUPER_ADMIN','ADMIN','HR','TIME_REVIEWER'].includes(user?.role??'');
 const [data,setData]=useState<any>(null);const [msg,setMsg]=useState('');const [busy,setBusy]=useState(false);
 const load=()=>api<any>('/security/status').then(setData);useEffect(()=>{load().catch(e=>setMsg(e.message));},[]);
 async function enroll(){setBusy(true);setMsg('');try{await enrollPlatformBiometric(`Passkey ${user?.role==='SUPER_ADMIN'?'do Super Adm':user?.role==='HR'?'do RH':user?.role==='TIME_REVIEWER'?'do responsável de ponto':'do Administrador'} Apontamento Digital`);setMsg('Passkey cadastrada com sucesso. Ela ficou vinculada a esta conta administrativa.');await load();window.dispatchEvent(new Event('nh-passkey-updated'));}catch(e){setMsg(e instanceof Error?e.message:'Falha no cadastro da passkey');}finally{setBusy(false);}}
 const count=data?.credentials?.length??0;
 if(!allowed)return <section className="card"><div className="eyebrow">SEGURANÇA DA CONTA</div><h2>Usuário e senha</h2><p className="muted">Colaboradores não precisam cadastrar biometria/passkey. Nas marcações de jornada, o sistema solicita somente uma selfie simples para conferência posterior do Administrador/Super Adm.</p></section>;
 return <div className="management-grid">
  <section className="card span-wide"><div className="eyebrow">SEGURANÇA ADMINISTRATIVA</div><h2>{user?.role==='SUPER_ADMIN'?'Super Adm protegido para aprovações sensíveis':user?.role==='HR'?'RH protegido por senha + passkey':user?.role==='TIME_REVIEWER'?'Responsável de ponto protegido por senha + passkey':'Administrador protegido por senha + passkey'}</h2><p className="muted">A passkey é usada pelas contas administrativas para operações sensíveis. Ela protege o acesso administrativo e a aprovação/rejeição das selfies das marcações. Colaboradores não usam passkey para bater ponto.</p></section>
  <section className="card"><div className="eyebrow">PASSKEY ADMINISTRATIVA</div><h2>Autenticador desta conta</h2><div className="fact-row"><span>WebAuthn no navegador</span><b>{supportsWebAuthn()?'Disponível':'Indisponível'}</b></div><div className="fact-row"><span>Passkeys cadastradas</span><b>{count}</b></div>{count===0?<button className="primary" disabled={busy||!supportsWebAuthn()} onClick={enroll}>{busy?'Aguardando confirmação…':'Cadastrar biometria/passkey'}</button>:<div className="success-box">✓ Credencial administrativa registrada. Para trocar, revogue a credencial atual e cadastre outra.</div>}<div className="credential-list">{data?.credentials?.map((c:any)=><div className="request-item" key={c.id}><div><b>{c.label||'Passkey administrativa'}</b><small>{new Date(c.createdAt).toLocaleString('pt-BR')}{c.lastUsedAt?` · último uso ${new Date(c.lastUsedAt).toLocaleString('pt-BR')}`:''}</small></div></div>)}</div></section>
  <section className="card"><div className="eyebrow">JORNADA DOS COLABORADORES</div><h2>Selfie + aprovação</h2><ul className="reason-list"><li>✓ Usuário e senha individuais</li><li>✓ Selfie simples em cada entrada, saída, intervalo e pausa</li><li>✓ Sem reconhecimento facial na selfie da jornada</li><li>✓ Responsável autorizado confere a foto e decide com a própria passkey</li></ul></section>
  {msg&&<section className="card span-wide"><div className="success-box">{msg}</div></section>}
 </div>;
}
