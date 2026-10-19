import { useEffect, useState } from 'react';
import Header from './components/Header';
import Hero from './components/Hero';
import Metrics from './components/Metrics';
import { Modules, Architecture, WhiteLabel, Infrastructure } from './components/ProductSections';
import { Pricing, ContactFooter } from './components/PricingFooter';

type Theme = 'dark' | 'light';
const themeStorageKey = 'gestao-theme';

function getInitialTheme(): Theme {
  if (typeof window === 'undefined') return 'dark';
  try {
    return window.localStorage.getItem(themeStorageKey) === 'light' ? 'light' : 'dark';
  } catch {
    return 'dark';
  }
}

export default function App() {
  const [notice, setNotice] = useState('');
  const [theme, setTheme] = useState<Theme>(getInitialTheme);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'dark' ? '#030911' : '#eef4fa');
    try {
      window.localStorage.setItem(themeStorageKey, theme);
    } catch {
      // A página continua funcional mesmo se o navegador bloquear o armazenamento local.
    }
  }, [theme]);

  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(''), 4800);
    return () => window.clearTimeout(timer);
  }, [notice]);

  const showDemoNotice = (message: string) => setNotice(message);
  const openContact = () => document.getElementById('contato')?.scrollIntoView({ behavior: 'smooth', block: 'center' });

  return (
    <>
      <a className="skip-link" href="#conteudo">Pular para o conteúdo</a>
      <Header
        theme={theme}
        onToggleTheme={() => setTheme((current) => (current === 'dark' ? 'light' : 'dark'))}
      />
      <main id="conteudo">
        <Hero onContact={openContact} />
        <Metrics />
        <Modules />
        <Architecture />
        <WhiteLabel />
        <Infrastructure />
        <Pricing onAction={() => showDemoNotice('Esta prévia está pronta para conectar seu pedido de contato sobre os planos ao canal comercial.')} />
      </main>
      <ContactFooter onContact={() => showDemoNotice('Conecte este CTA ao canal de atendimento da sua equipe para receber solicitações.')} />
      <div className={`live-notice${notice ? ' live-notice--visible' : ''}`} role="status" aria-live="polite">{notice}</div>
    </>
  );
}
