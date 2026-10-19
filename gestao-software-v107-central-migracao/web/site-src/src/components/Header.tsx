import { useState } from 'react';
import Brand from './Brand';
import { Icon } from './Icons';

type Theme = 'dark' | 'light';

const links = [
  { label: 'Soluções', href: '#solucoes' },
  { label: 'Módulos', href: '#modulos' },
  { label: 'Planos', href: '#planos' },
  { label: 'Infraestrutura', href: '#infraestrutura' },
  { label: 'Clientes', href: '#clientes' },
  { label: 'Contato', href: '#contato' },
];

export default function Header({
  theme,
  onToggleTheme,
}: {
  theme: Theme;
  onToggleTheme: () => void;
}) {
  const [menuOpen, setMenuOpen] = useState(false);
  const themeAction = theme === 'dark' ? 'Ativar modo claro' : 'Ativar modo escuro';

  return (
    <header className="site-header">
      <div className="header-inner">
        <Brand />
        <nav className={`primary-nav${menuOpen ? ' primary-nav--open' : ''}`} aria-label="Navegação principal">
          {links.map((link) => (
            <a key={link.href} href={link.href} onClick={() => setMenuOpen(false)}>{link.label}</a>
          ))}
        </nav>
        <div className="header-actions">
          <a className="icon-button header-search" href="#modulos" aria-label="Ver módulos"><Icon name="search" size={18} /></a>
          <button
            className="icon-button theme-toggle"
            type="button"
            aria-label={themeAction}
            aria-pressed={theme === 'light'}
            title={themeAction}
            onClick={onToggleTheme}
          >
            <Icon name={theme === 'dark' ? 'sun' : 'moon'} size={19} />
          </button>
          <a className="button button--small button--primary admin-button" href="/admin/login.html" aria-label="Abrir área administrativa">
            <span className="admin-button__full">Área administrativa</span>
            <span className="admin-button__compact" aria-hidden="true">Área</span>
            <Icon name="arrow" size={15} />
          </a>
          <button
            className="icon-button menu-toggle"
            type="button"
            aria-label={menuOpen ? 'Fechar menu' : 'Abrir menu'}
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((open) => !open)}
          >
            <Icon name={menuOpen ? 'close' : 'menu'} size={22} />
          </button>
        </div>
      </div>
    </header>
  );
}
