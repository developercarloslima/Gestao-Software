/* Tema visual da Gestão (independente do branding white-label de cada empresa). */
(function () {
  'use strict';
  const storageKey = 'gestao-theme';
  const root = document.documentElement;

  function readTheme() {
    try { return localStorage.getItem(storageKey) === 'light' ? 'light' : 'dark'; }
    catch (_) { return 'dark'; }
  }

  function applyTheme(theme, persist) {
    const next = theme === 'light' ? 'light' : 'dark';
    root.dataset.theme = next;
    root.style.colorScheme = next;
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', next === 'dark' ? '#030911' : '#eef4fa');
    if (persist) {
      try { localStorage.setItem(storageKey, next); } catch (_) { /* storage desabilitado */ }
    }
    document.querySelectorAll('[data-gestao-theme-toggle]').forEach((button) => {
      const action = next === 'dark' ? 'Ativar modo claro' : 'Ativar modo escuro';
      button.setAttribute('aria-label', action);
      button.setAttribute('title', action);
      button.setAttribute('aria-pressed', String(next === 'light'));
      const text = button.querySelector('[data-gestao-theme-label]');
      if (text) text.textContent = next === 'dark' ? 'Modo claro' : 'Modo escuro';
    });
  }

  // Inicializa ainda no head para evitar a tela piscar na cor incorreta.
  applyTheme(readTheme(), false);
  document.addEventListener('DOMContentLoaded', () => applyTheme(readTheme(), false));
  document.addEventListener('click', (event) => {
    const target = event.target;
    const button = target instanceof Element ? target.closest('[data-gestao-theme-toggle]') : null;
    if (button) applyTheme(root.dataset.theme === 'dark' ? 'light' : 'dark', true);
  });
  window.addEventListener('storage', (event) => {
    if (event.key === storageKey) applyTheme(readTheme(), false);
  });
})();
