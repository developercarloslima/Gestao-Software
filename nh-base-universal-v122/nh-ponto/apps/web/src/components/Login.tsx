import { FormEvent, useState } from 'react';
import { cacheUser, setToken, SessionUser } from '../lib/api';
import { loginWithPasswordAndPasskey, supportsWebAuthn, type LoginPasskeyMode } from '../lib/webauthn';

type Props = { onLogin: (user: SessionUser) => void };

type LoginResponse={token:string;user:SessionUser};

export default function Login({ onLogin }: Props) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [tenantDocument, setTenantDocument] = useState('');
  const [error, setError] = useState('');
  const [loadingMode, setLoadingMode] = useState<LoginPasskeyMode|null>(null);

  function finishLogin(data:LoginResponse){setToken(data.token);cacheUser(data.user);onLogin(data.user);}

  async function authenticate(passkeyMode:LoginPasskeyMode) {
    setLoadingMode(passkeyMode); setError('');
    try {
      const data = await loginWithPasswordAndPasskey(username,password,tenantDocument||undefined,passkeyMode) as LoginResponse;
      finishLogin(data);
    } catch (e) { setError(e instanceof Error ? e.message : 'Falha no login'); }
    finally { setLoadingMode(null); }
  }

  async function submit(e: FormEvent) {e.preventDefault();await authenticate('DEFAULT');}

  return <>
  <header className="ponto-login-header">
    <div className="ponto-login-header-inner">
      <div className="ponto-login-brand">
        <img src="/ponto/identidade-pendente.svg" alt="Identidade da empresa" />
        <span><strong>Portal da Empresa</strong><small>Apontamento Digital</small></span>
      </div>
      <nav className="ponto-login-nav" aria-label="Acessos rápidos">
        <a href="/">Site principal</a>
        <a href="/colaborador/">Área do Colaborador</a>
      </nav>
    </div>
  </header>
  <main className="login-shell">
    <section className="brand-panel">
      <img className="nh-ponto-logo nh-ponto-logo-login" src="/ponto/identidade-pendente.svg" alt="Identidade da empresa" />
      <h1>Apontamento Digital</h1>
      <p>Controle de jornada com identidade, segurança e integridade da empresa.</p>
      <div className="trust-row"><span>Hash encadeado</span><span>Prova de presença</span><span>Correção auditável</span><span>Jornada Espelho</span></div>
    </section>
    <form className="login-card" onSubmit={submit}>
      <div className="eyebrow">ACESSO CORPORATIVO • APONTAMENTO DIGITAL</div>
      <h2>Acessar plataforma</h2>
      <label>Usuário<input required value={username} onChange={e => setUsername(e.target.value)} autoComplete="username" /></label>
      <label>Senha<input required value={password} onChange={e => setPassword(e.target.value)} type="password" autoComplete="current-password" /></label>
      <label>Empresa / CNPJ <span className="optional">opcional</span><input value={tenantDocument} onChange={e => setTenantDocument(e.target.value)} placeholder="Use apenas se o usuário existir em mais de uma empresa" /></label>
      {error && <div className="error-box">{error}</div>}
      <button className="primary" disabled={loadingMode!==null}>{loadingMode==='DEFAULT' ? 'Aguarde...' : 'Entrar'}</button>
      {supportsWebAuthn()&&<><div className="login-biometric-divider">passkey administrativa</div><button type="button" className="secondary-action phone-passkey-action" disabled={loadingMode!==null} onClick={()=>void authenticate('PHONE')}>{loadingMode==='PHONE'?'Aguardando celular...':'Usar passkey do celular (QR)'}</button><small className="phone-passkey-help">Para ADMIN/RH e contas protegidas: no computador, o navegador abrirá a opção de usar outro dispositivo. Em navegadores compatíveis, escaneie o QR Code com o celular que possui a passkey e confirme a biometria no próprio aparelho.</small></>}
      <small>Colaboradores acessam com usuário e senha. Contas administrativas protegidas confirmam também a passkey.</small>
      {!supportsWebAuthn()&&<small>Se esta for uma conta administrativa protegida, use Chrome, Edge, Safari ou outro navegador compatível com WebAuthn/passkey.</small>}
    </form>
    <div className="nh-ponto-footer">Tecnologia: Gestão Software LTDA</div>
  </main>
  </>;
}
