import { Icon, type IconName } from './Icons';
import Brand from './Brand';

const modules: { icon: IconName; title: string; detail: string; id: string }[] = [
  { icon: 'monitor', title: 'Site Principal', detail: 'Institucional e conteúdo dinâmico', id: 'site' },
  { icon: 'users', title: 'Área do Cliente', detail: 'Serviços e autoatendimento', id: 'cliente' },
  { icon: 'user', title: 'Área do Colaborador', detail: 'Gestão interna e processos', id: 'colaborador' },
  { icon: 'card', title: 'Cota', detail: 'Controle de pagamentos e cobranças', id: 'cota' },
  { icon: 'calendar', title: 'Vistoria / Eventos', detail: 'Agendamentos e inscrições', id: 'eventos' },
  { icon: 'receipt', title: 'Apontamento Digital', detail: 'Registros e produtividade', id: 'apontamento' },
  { icon: 'chart', title: 'Financeiro', detail: 'Controle completo', id: 'financeiro' },
  { icon: 'settings', title: 'Control Plane', detail: 'Gestão multiempresa', id: 'control-plane' },
];

export function Modules() {
  return (
    <section className="modules-section shell" id="modulos" aria-labelledby="modules-title">
      <div className="section-copy">
        <p className="eyebrow">Soluções modulares</p>
        <h2 id="modules-title">Nossos módulos</h2>
        <p>Tudo o que sua empresa precisa, em módulos integrados e flexíveis.</p>
        <a className="text-link" href="#todos-modulos">Conhecer todos os módulos <Icon name="arrow" size={15} /></a>
      </div>
      <div className="module-grid" id="todos-modulos">
        {modules.map((item) => (
          <a className="module-card" key={item.id} href="#contato" aria-label={`${item.title}: ${item.detail}`}>
            <Icon className="module-card__icon" name={item.icon} size={30} />
            <span className="module-card__copy"><strong>{item.title}</strong><small>{item.detail}</small></span>
            <Icon className="module-card__arrow" name="arrow" size={16} />
          </a>
        ))}
      </div>
    </section>
  );
}

const platformBenefits: { icon: IconName; text: string }[] = [
  { icon: 'network', text: 'Plataforma-mãe' },
  { icon: 'server', text: 'Infraestrutura' },
  { icon: 'layers', text: 'Módulos' },
  { icon: 'shield', text: 'Segurança' },
  { icon: 'pin', text: 'Gerenciamento central' },
  { icon: 'cloud', text: 'Suporte e atualizações' },
];

const clients = [
  { initials: 'NH', name: 'NH Empresa', domain: 'nh.com.br', pos: 'client-photo--one' },
  { initials: 'SMG', name: 'SMG', domain: 'smg.org.br', pos: 'client-photo--two' },
  { initials: 'AC', name: 'Outras empresas', domain: 'suaempresa.com.br', pos: 'client-photo--three' },
];

export function Architecture() {
  return (
    <section className="feature-panel architecture-section shell" id="solucoes" aria-labelledby="architecture-title">
      <div className="section-copy architecture-copy">
        <p className="eyebrow">Como funciona</p>
        <h2 id="architecture-title">Uma plataforma.<br />Várias empresas.</h2>
        <p>O Gestão Software é a plataforma-mãe. Cada cliente tem sua própria identidade, domínio e configurações, com dados e gestão separados centralmente.</p>
        <a className="button button--primary button--compact" href="#clientes">Entenda a arquitetura <Icon name="arrow" size={16} /></a>
      </div>
      <div className="platform-card">
        <Brand compact />
        <ul>
          {platformBenefits.map((item) => <li key={item.text}><Icon name={item.icon} size={16} /><span>{item.text}</span></li>)}
        </ul>
      </div>
      <div className="client-list" id="clientes" aria-label="Exemplo de empresas independentes">
        {clients.map((client) => (
          <article className="client-row" key={client.initials}>
            <div className={`client-photo ${client.pos}`} aria-hidden="true"><span>{client.initials}</span></div>
            <div className="client-info"><strong>{client.name}</strong><small>{client.domain}</small></div>
            <span className="client-badge">Projeto independente</span>
          </article>
        ))}
      </div>
    </section>
  );
}

const whiteLabelBenefits: { icon: IconName; text: string }[] = [
  { icon: 'palette', text: 'Logo e cores personalizadas' },
  { icon: 'globe', text: 'Domínio próprio ou subdomínio' },
  { icon: 'layers', text: 'Layout institucional' },
  { icon: 'shield', text: 'Identidade completa da sua empresa' },
];

export function WhiteLabel() {
  return (
    <section className="feature-panel white-label-section shell" id="white-label" aria-labelledby="white-label-title">
      <div className="section-copy">
        <p className="eyebrow">White-label</p>
        <h2 id="white-label-title">A sua marca.<br />Com a nossa tecnologia.</h2>
        <p>Personalize 100% a identidade visual da sua empresa: logo, cores, domínio próprio ou subdomínio, mantendo toda a robustez da plataforma Gestão Software.</p>
        <a className="button button--primary button--compact" href="#clientes">Ver exemplos de personalização <Icon name="arrow" size={16} /></a>
      </div>
      <div className="browser-mockup" aria-label="Prévia de um site institucional white-label">
        <div className="browser-chrome"><span /><span /><span /><i />⌁</div>
        <div className="association-site">
          <div className="association-nav"><strong><span>NH</span> EMPRESA</strong><nav><span>Início</span><span>Serviços</span><span>Eventos</span><span>Contato</span></nav></div>
          <div className="association-hero">
            <img src="/assets/site-association.webp" alt="Paisagem verde e área de convivência de uma empresa" loading="lazy" />
            <div className="association-shade" />
            <h3>Juntos por<br />uma empresa<br />mais forte.</h3>
          </div>
        </div>
        <span className="browser-caption">Experiência própria. Plataforma integrada.</span>
      </div>
      <ul className="benefit-list">
        {whiteLabelBenefits.map((item) => <li key={item.text}><Icon name={item.icon} size={28} /><span>{item.text}</span></li>)}
      </ul>
    </section>
  );
}

const infrastructureItems: { icon: IconName; title: string; detail: string }[] = [
  { icon: 'layers', title: 'VPS dedicado', detail: 'Ambiente isolado e de alta performance' },
  { icon: 'cloud', title: 'Nuvem escalável', detail: 'Recursos sob demanda' },
  { icon: 'backup', title: 'Backups automáticos', detail: 'Seus dados sempre seguros' },
  { icon: 'network', title: 'Controle de ambientes', detail: 'Gerenciamento centralizado' },
];

export function Infrastructure() {
  return (
    <section className="feature-panel infrastructure-section shell" id="infraestrutura" aria-labelledby="infrastructure-title">
      <div className="section-copy">
        <p className="eyebrow">Infraestrutura</p>
        <h2 id="infrastructure-title">Segurança e performance para o seu crescimento.</h2>
        <p>Hospedagem em ambiente de alta performance, com VPS dedicado, infraestrutura em nuvem, backups automáticos e controle total dos seus dados.</p>
        <a className="button button--primary button--compact" href="#planos">Conhecer nossa infraestrutura <Icon name="arrow" size={16} /></a>
      </div>
      <div className="infrastructure-photo"><img src="/assets/site-infrastructure.webp" alt="Fileira de servidores com iluminação azul em um data center" loading="lazy" /><span>INFRAESTRUTURA GERENCIADA</span></div>
      <ul className="infrastructure-list">
        {infrastructureItems.map((item) => <li key={item.title}><Icon name={item.icon} size={22} /><span><strong>{item.title}</strong><small>{item.detail}</small></span></li>)}
      </ul>
      <div className="uptime-card"><Icon name="shield" size={38} /><span>Alta disponibilidade</span><strong>99,9%</strong><small>de uptime garantido</small><div className="uptime-line"><i /></div></div>
    </section>
  );
}
