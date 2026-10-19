import { Icon, type IconName } from './Icons';

const metrics: { icon: IconName; value: string; label: string }[] = [
  { icon: 'buildings', value: '+50', label: 'empresas ativas' },
  { icon: 'tag', value: '100%', label: 'personalizável' },
  { icon: 'globe', value: '+80', label: 'domínios gerenciados' },
  { icon: 'layers', value: '8', label: 'módulos integrados' },
];

export default function Metrics() {
  return (
    <section className="metrics-panel shell" id="resultados" aria-labelledby="metrics-title">
      <div className="metrics-intro">
        <p className="eyebrow">Resultados reais</p>
        <h2 id="metrics-title">Estrutura completa para empresas de todos os portes.</h2>
      </div>
      {metrics.map((metric) => (
        <div className="metric" key={metric.label}>
          <span className="metric__icon"><Icon name={metric.icon} size={28} /></span>
          <div><strong>{metric.value}</strong><span>{metric.label}</span></div>
        </div>
      ))}
      <p className="metrics-note">Indicadores ilustrativos da plataforma</p>
    </section>
  );
}
