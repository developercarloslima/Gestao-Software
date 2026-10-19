import Brand from './Brand';
import { Icon, type IconName } from './Icons';

const plans = [
  {
    name: 'Start',
    features: ['Operação inicial e equipes menores', 'Módulos contratados sob medida', 'Hospedagem Cloud compartilhada', 'Identidade visual da empresa', 'Implantação assistida'],
  },
  {
    name: 'Business',
    features: ['Mais usuários e maior volume operacional', 'Gestão multiempresa quando contratada', 'Backups e monitoramento ampliados', 'Domínio ou subdomínio próprio', 'Suporte comercial e operacional'],
  },
  {
    name: 'Pro',
    features: ['Operação de médio porte', 'Mais capacidade de usuários e vendas', 'Recursos reservados de infraestrutura', 'Integrações e automações avançadas', 'Relatórios e controles ampliados'],
  },
  {
    name: 'Scale',
    features: ['Operações em forte crescimento', 'Alta capacidade transacional', 'Escalabilidade de aplicação e banco', 'Monitoramento de infraestrutura', 'Planejamento de capacidade contínuo'],
  },
  {
    name: 'Enterprise',
    features: ['Grandes operações corporativas', 'Arquitetura de alta disponibilidade', 'Banco e recursos com maior isolamento', 'SLA e implantação personalizados', 'Suporte técnico prioritário'],
  },
  {
    name: 'Dedicated Cloud',
    features: ['Infraestrutura dedicada ao cliente', 'Ambiente AWS exclusivo', 'Banco de dados dedicado', 'Escala e segurança personalizadas', 'Capacidade dimensionada por operação'],
  },
  {
    name: 'Hybrid Enterprise',
    features: ['Cloud principal + infraestrutura física opcional', 'Disaster Recovery e contingência', 'Arquitetura para operações críticas', 'Capacidade acima dos planos padrão', 'Projeto e SLA sob dimensionamento'],
  },
];

export function Pricing({ onAction }: { onAction: () => void }) {
  return (
    <section className="pricing-section shell" id="planos" aria-labelledby="pricing-title">
      <div className="section-copy pricing-copy">
        <p className="eyebrow">Planos</p>
        <h2 id="pricing-title">Um plano para cada estágio da sua operação.</h2>
        <p>Escolha a categoria ideal para a sua empresa. O valor é calculado conforme porte, módulos, infraestrutura e necessidades da operação.</p>
      </div>
      <div className="pricing-grid">
        {plans.map((plan) => (
          <article className="price-card" key={plan.name}>
            <h3>{plan.name}</h3>
            <ul>{plan.features.map((feature) => <li key={feature}><Icon name="check" size={16} />{feature}</li>)}</ul>
            <button type="button" className="button button--plan" onClick={onAction}>Fale com especialista</button>
          </article>
        ))}
      </div>
    </section>
  );
}

const footerItems: { icon: IconName; title: string; detail: string }[] = [
  { icon: 'headset', title: 'Atendimento', detail: 'consultivo e sem compromisso' },
  { icon: 'settings', title: 'Implantação', detail: 'guiada e personalizada' },
  { icon: 'chart', title: 'Parceria de longo prazo', detail: 'com o seu crescimento' },
];

export function ContactFooter({ onContact }: { onContact: () => void }) {
  return (
    <footer className="site-footer shell" id="contato">
      <div className="footer-cta">
        <p className="eyebrow">Pronto para transformar</p>
        <h2>a gestão da sua empresa?</h2>
        <p>Conheça uma estrutura feita para impulsionar a sua empresa.</p>
        <button className="text-link footer-contact-link" type="button" onClick={onContact}>Falar com nossa equipe <Icon name="arrow" size={15} /></button>
      </div>
      <div className="footer-services">
        {footerItems.map((item) => <div className="footer-service" key={item.title}><Icon name={item.icon} size={25} /><span>{item.title}<small>{item.detail}</small></span></div>)}
      </div>
      <div className="footer-brand"><Brand footer /><small>Sua empresa. Na palma da sua mão.</small></div>
      <div className="footer-bottom"><span>© 2026 Gestão Software</span><nav aria-label="Navegação do rodapé"><a href="#topo">Início</a><a href="#modulos">Módulos</a><a href="#planos">Planos</a><a href="#infraestrutura">Infraestrutura</a></nav><a href="#topo" className="back-to-top">Voltar ao topo ↑</a></div>
    </footer>
  );
}
