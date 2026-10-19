type BrandProps = { compact?: boolean; footer?: boolean };

const logoPath = '/assets/logo-gestao-software.png';

export default function Brand({ compact = false, footer = false }: BrandProps) {
  return (
    <a
      className={`brand${compact ? ' brand--compact' : ''}${footer ? ' brand--footer' : ''}`}
      href="#topo"
      aria-label="Gestão Software 2026 — Gestão completa. Operação conectada. Início"
    >
      <img className="brand__logo" src={logoPath} alt="" />
    </a>
  );
}
