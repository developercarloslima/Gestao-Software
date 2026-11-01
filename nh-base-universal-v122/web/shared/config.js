(() => {
  const current = window.NH_CONFIG || {};
  const normalizeBaseUrl = (value) => String(value || '').trim().replace(/\/+$/, '');
  const backendBaseUrl = normalizeBaseUrl(current.backendBaseUrl);

  window.NH_CONFIG = {
    sgaUrl: current.sgaUrl || '#',
    teamWhatsapp: current.teamWhatsapp || '',
    backendBaseUrl
  };

  window.NH_API = {
    backend(path) {
      const normalizedPath = String(path || '').startsWith('/') ? String(path) : `/${path}`;
      return backendBaseUrl ? `${backendBaseUrl}${normalizedPath}` : normalizedPath;
    }
  };
})();

// Roteamento unificado dos portais internos NH.
(() => {
  const ROLE_PATHS = Object.freeze({
    CONSULTANT: '/colaborador/',
    ANALYST: '/analise/',
    TOW_DRIVER: '/guincho/',
    WORKSHOP_MANAGER: '/oficina/',
    EVENT_OPERATOR: '/checklist/',
    BUYER: '/financeiro/gestao.html',
    SUPERVISION_ANALYSIS: '/supervisao/',
    ADMIN: '/admin/'
  });
  window.NH_ROUTING = {
    rolePath(role) { return ROLE_PATHS[String(role || '').toUpperCase()] || '/'; },
    redirectForRole(role, replace = true) {
      const target = ROLE_PATHS[String(role || '').toUpperCase()] || '/';
      if (replace) window.location.replace(target); else window.location.href = target;
    },
    rolePaths: ROLE_PATHS
  };
})();
