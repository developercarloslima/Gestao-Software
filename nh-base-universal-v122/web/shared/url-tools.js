(() => {
  const normalizeBaseUrl = (value) => String(value || '').trim().replace(/\/+$/, '');
  const siteOrigin = normalizeBaseUrl(window.location.origin);

  function currentSiteUrl(value = '/') {
    try {
      const parsed = new URL(String(value || '/'), `${siteOrigin}/`);
      return `${siteOrigin}${parsed.pathname}${parsed.search}${parsed.hash}`;
    } catch (_) {
      const path = String(value || '/').startsWith('/') ? String(value || '/') : `/${value}`;
      return `${siteOrigin}${path}`;
    }
  }

  function retratoUrl(tokenOrUrl) {
    const value = String(tokenOrUrl || '').trim();
    let token = value;

    try {
      const parsed = new URL(value, `${siteOrigin}/`);
      token = parsed.searchParams.get('token') || value;
    } catch (_) {
      // Mantém o valor recebido como token.
    }

    const url = new URL('/retrato/', `${siteOrigin}/`);
    if (token) url.searchParams.set('token', token);
    return url.toString();
  }

  function replaceLinkInCommunicationUrl(communicationUrl, oldLink, newLink) {
    if (!communicationUrl || !newLink) return communicationUrl || '';

    try {
      const parsed = new URL(communicationUrl);
      ['text', 'body'].forEach((param) => {
        const message = parsed.searchParams.get(param);
        if (!message) return;

        let updated = message;
        if (oldLink) updated = updated.split(oldLink).join(newLink);

        updated = updated.replace(
          /https?:\/\/[^\s]+\/retrato\/?\?token=[A-Za-z0-9_-]+/gi,
          newLink
        );
        parsed.searchParams.set(param, updated);
      });
      return parsed.toString();
    } catch (_) {
      return communicationUrl;
    }
  }

  window.NH_URLS = {
    siteOrigin,
    currentSiteUrl,
    retratoUrl,
    replaceLinkInCommunicationUrl
  };
})();
