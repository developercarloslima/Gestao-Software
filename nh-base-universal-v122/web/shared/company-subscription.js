/* Mensalidades SaaS devidas pela empresa à Gestão Software; não emite novas cobranças. */
(function () {
  'use strict';
  const safe = value => String(value == null ? '' : value).replace(/[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  const money = value => Number(value || 0).toLocaleString('pt-BR',
    { style: 'currency', currency: 'BRL' });
  const date = value => value ? String(value).slice(0, 10).split('-').reverse().join('/') : '—';
  const month = value => {
    if (!value) return '—';
    const parts = String(value).slice(0, 10).split('-');
    return parts.length === 3 ? parts[1] + '/' + parts[0] : safe(value);
  };
  const statuses = { PAID: 'Paga', PENDING: 'Pendente', OVERDUE: 'Em atraso',
    CANCELLED: 'Cancelada' };
  const methods = { BOLETO: 'Boleto / Pix', PIX_AUTOMATICO: 'Pix automático',
    DEBITO_AUTOMATICO: 'Débito automático' };
  const url = value => {
    try {
      const parsed = new URL(value);
      return parsed.protocol === 'https:' && !parsed.username && !parsed.password
        ? parsed.href : null;
    } catch { return null; }
  };
  const api = path => window.NH_API && window.NH_API.backend
    ? window.NH_API.backend(path) : path;

  document.querySelectorAll('[data-company-subscription-root]').forEach(root => {
    const endpoint = root.getAttribute('data-endpoint');
    const feedback = document.createElement('p');
    let busy = false;
    let loaded = false;
    root.innerHTML =
      '<div class="cs-heading"><div><h2>Mensalidades da empresa</h2>' +
      '<p>Faturas do plano contratado com o Gestão Software. Não são boletos dos associados.</p></div>' +
      '<button class="rc-button cs-refresh" type="button">Atualizar mensalidades</button></div>' +
      '<p class="cs-message" role="status" aria-live="polite"></p>' +
      '<div class="cs-content"><div class="rc-empty">Abra esta aba para consultar suas mensalidades.</div></div>';
    const message = root.querySelector('.cs-message');
    const content = root.querySelector('.cs-content');
    const button = root.querySelector('.cs-refresh');

    function render(data) {
      const invoices = Array.isArray(data.invoices) ? data.invoices : [];
      const summary = '<div class="cs-summary">' +
        '<div><span>Empresa</span><strong>' + safe(data.companyName || 'Empresa') + '</strong></div>' +
        '<div><span>Plano</span><strong>' + safe(data.planName || 'Personalizado') + '</strong></div>' +
        '<div><span>Mensalidade contratada</span><strong>' + money(data.monthlyFee) + '</strong></div>' +
        '<div><span>Dia de vencimento</span><strong>' + safe(data.billingDueDay || '—') + '</strong></div>' +
        '<div><span>Em atraso</span><strong class="cs-overdue">' + money(data.overdueAmount) +
        ' (' + Number(data.overdueCount || 0) + ')</strong></div>' +
        '</div>';
      const note = '<p class="cs-note">Forma cadastrada: ' +
        safe(methods[data.paymentMethod] || data.paymentMethod || 'Não informada') +
        '. A segunda via utiliza a cobrança já emitida pela Gestão/Asaas; não é gerado um novo boleto.</p>';
      if (!invoices.length) {
        content.innerHTML = summary + note +
          '<div class="rc-empty">Nenhuma mensalidade emitida para esta empresa. Consulte o Gestão Software se esperava uma cobrança.</div>';
        return;
      }
      const rows = invoices.map(invoice => {
        const state = String(invoice.status || 'PENDING').toUpperCase();
        const paid = state === 'PAID';
        const cancelled = state === 'CANCELLED';
        const link = !paid && !cancelled ? url(invoice.invoiceUrl) : null;
        const action = link
          ? '<a class="rc-button" target="_blank" rel="noopener noreferrer" href="' + safe(link) +
            '">Abrir cobrança / 2ª via</a>'
          : paid ? '<span class="cs-muted">Pagamento confirmado</span>'
          : cancelled ? '<span class="cs-muted">Cobrança cancelada</span>'
          : '<span class="cs-muted">Cobrança ainda não emitida no Asaas. Solicite ao Gestão Software.</span>';
        return '<tr><td>' + safe(month(invoice.referenceMonth)) + '</td><td>' +
          money(invoice.amount) + '</td><td>' + safe(date(invoice.dueDate)) +
          '</td><td><span class="cs-status cs-' + safe(state) + '">' +
          safe(statuses[state] || state) + '</span></td><td>' +
          safe(invoice.paidAt ? date(invoice.paidAt) : '—') + '</td><td>' + action +
          '</td></tr>';
      }).join('');
      content.innerHTML = summary + note +
        '<div class="table-wrap cs-table"><table><thead><tr><th>Competência</th>' +
        '<th>Valor</th><th>Vencimento</th><th>Situação</th><th>Pago em</th><th>Segunda via / cobrança</th>' +
        '</tr></thead><tbody>' + rows + '</tbody></table></div>';
    }

    async function load() {
      if (busy) return;
      busy = true;
      button.disabled = true;
      button.textContent = 'Consultando...';
      message.textContent = 'Consultando as mensalidades no Gestão Software...';
      try {
        const response = await fetch(api(endpoint), {
          headers: { Authorization: 'Bearer ' + (localStorage.getItem('nhPortalToken') || '') },
          cache: 'no-store'
        });
        if (!response.ok) {
          let detail = {};
          try { detail = await response.json(); } catch { /* falha sem JSON */ }
          throw new Error(detail.message || detail.detail || (
            response.status === 401 || response.status === 403
              ? 'Sua sessão expirou ou não permite consultar as mensalidades.'
              : 'Não foi possível consultar as mensalidades (HTTP ' + response.status + ').'));
        }
        render(await response.json());
        loaded = true;
        message.textContent = 'Consulta atualizada.';
      } catch (error) {
        message.textContent = error.message || 'Erro ao consultar as mensalidades.';
        if (!loaded) content.innerHTML =
          '<div class="rc-empty">Não foi possível carregar as mensalidades. Verifique a integração e tente atualizar.</div>';
      } finally {
        busy = false;
        button.disabled = false;
        button.textContent = 'Atualizar mensalidades';
      }
    }

    button.addEventListener('click', load);
    const tab = root.closest('[id^="tab-"]');
    const tabName = tab ? tab.id.replace(/^tab-/, '') : '';
    document.querySelectorAll('[data-tab="' + tabName + '"]').forEach(element =>
      element.addEventListener('click', () => { if (!loaded) load(); }));
    if (!tab || !tab.hidden) load();
  });
})();
