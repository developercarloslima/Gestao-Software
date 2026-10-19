import { Icon, type IconName } from './Icons';

type HeroProps = { onContact: () => void };

const assurances: { icon: IconName; title: string; detail: string }[] = [
  { icon: 'shield', title: 'Implantação', detail: 'especializada' },
  { icon: 'headset', title: 'Suporte contínuo', detail: 'e consultivo' },
  { icon: 'chart', title: 'Plataforma segura', detail: 'e em constante evolução' },
];

function MemberPreview() {
  return (
    <div className="member-preview" aria-label="Prévia visual da área do cliente">
      <div className="member-preview__top">
        <span className="mini-mark">G</span>
        <strong>Olá, Cliente!</strong>
        <Icon name="user" size={16} />
      </div>
      <div className="member-preview__grid">
        <span><Icon name="card" size={17} /><small>Minha Cota<br /><b>Em dia</b></small></span>
        <span><Icon name="calendar" size={17} /><small>Vistorias<br /><b>Agendar</b></small></span>
        <span><Icon name="calendar" size={17} /><small>Meus Eventos<br /><b>Próximos</b></small></span>
        <span><Icon name="file" size={17} /><small>Documentos<br /><b>Acessar</b></small></span>
      </div>
      <div className="member-preview__metric">
        <div><span>Clientes ativos</span><strong>+2.580</strong><small>↗ +12% este ano</small></div>
        <div className="mini-bars" aria-hidden="true"><i /><i /><i /><i /><i /></div>
      </div>
    </div>
  );
}

export default function Hero({ onContact }: HeroProps) {
  return (
    <section className="hero shell" id="topo">
      <div className="hero__background" aria-hidden="true" />
      <div className="hero__glow" aria-hidden="true" />
      <div className="hero__content">
        <p className="eyebrow">Empresas mais fortes com tecnologia</p>
        <h1>Tecnologia completa para <span>modernizar e escalar</span> a gestão da sua <span>empresa.</span></h1>
        <p className="hero__description">O Gestão Software centraliza site institucional, módulos operacionais, apontamento digital, financeiro, white-label, multiempresa, hospedagem e controle administrativo em uma única plataforma.</p>
        <div className="hero__actions">
          <button type="button" className="button button--primary" onClick={onContact}>Falar com especialista <Icon name="arrow" size={17} /></button>
          <a className="button button--outline" href="#modulos">Conhecer módulos</a>
        </div>
        <div className="hero__assurances">
          {assurances.map((item) => (
            <div className="assurance" key={item.title}>
              <span className="assurance__icon"><Icon name={item.icon} size={22} /></span>
              <span>{item.title}<br /><small>{item.detail}</small></span>
            </div>
          ))}
        </div>
      </div>
      <div className="hero__visual" aria-hidden="true">
        <div className="hero-orbit"><span>G</span></div>
        <MemberPreview />
      </div>
      <a className="hero-scroll" href="#resultados"><span />Role para explorar</a>
    </section>
  );
}
