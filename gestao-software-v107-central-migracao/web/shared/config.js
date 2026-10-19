(() => {
  const base = String(window.GESTAO_API_BASE || '').trim().replace(/\/+$/,'');
  window.NH_API = { backend(path){ const p=String(path||'').startsWith('/')?String(path):`/${path}`; return base?`${base}${p}`:p; } };
})();
