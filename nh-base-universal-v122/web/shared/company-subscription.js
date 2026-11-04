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
  const statuses = { SCHEDULED: 'Prevista (não emitida)', PAID: 'Paga', PAID_IN_CASH: 'Recebida em dinheiro (manual)',
    CONFIRMED: 'Pagamento confirmado (aguardando disponibilização)',
    PENDING: 'Pendente', OVERDUE: 'Em atraso', REFUNDED: 'Estornada', CANCELLED: 'Cancelada' };
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
      '<div><button class="rc-button cs-refresh" type="button">Atualizar mensalidades</button> ' +
      '<button class="rc-button cs-sync" type="button">Sincronizar com Asaas</button></div></div>' +
      '<p class="cs-message" role="status" aria-live="polite"></p>' +
      '<div class="cs-content"><div class="rc-empty">Abra esta aba para consultar suas mensalidades.</div></div>';
    const message = root.querySelector('.cs-message');
    const content = root.querySelector('.cs-content');
    const button = root.querySelector('.cs-refresh');
    const syncButton = root.querySelector('.cs-sync');

    function promptRenewal(data) {
      const renewal = data && data.renewal;
      if (!renewal || !renewal.showModal || document.querySelector('.cs-renewal-overlay')) return;
      const days = Math.max(0, Number(renewal.remainingDays || 0));
      const overlay = document.createElement('div');
      overlay.className = 'cs-renewal-overlay';
      overlay.setAttribute('role', 'dialog');
      overlay.setAttribute('aria-modal', 'true');
      overlay.setAttribute('aria-label', 'Renovação do plano');
      overlay.innerHTML = '<div class="cs-renewal-dialog">' +
        '<h2>Renovação do plano</h2>' +
        '<p>Faltam <strong>' + days + ' dia(s)</strong> para renovar o plano automaticamente.</p>' +
        '<p>Escolha como deseja continuar. Se renovar agora, todas as próximas cobranças serão emitidas com vencimentos futuros.</p>' +
        '<div class="cs-renewal-actions">' +
        '<button type="button" data-renewal-choice="RENEW_NOW">Renovar agora</button>' +
        '<button type="button" data-renewal-choice="AUTOMATIC">Deixar renovar automaticamente</button>' +
        '<button type="button" data-renewal-choice="NON_RENEW">Não renovar</button>' +
        '</div><p class="cs-renewal-feedback" role="status" aria-live="polite"></p></div>';
      document.body.appendChild(overlay);
      const feedback = overlay.querySelector('.cs-renewal-feedback');
      overlay.querySelectorAll('[data-renewal-choice]').forEach(btn => {
        btn.addEventListener('click', async () => {
          overlay.querySelectorAll('button').forEach(button => button.disabled = true);
          feedback.textContent = 'Salvando sua escolha...';
          try {
            const response = await fetch(api(endpoint + '/renewal'), {
              method: 'POST',
              headers: {
                Authorization: 'Bearer ' + (localStorage.getItem('nhPortalToken') || ''),
                'Content-Type': 'application/json'
              },
              body: JSON.stringify({ action: btn.getAttribute('data-renewal-choice') })
            });
            if (!response.ok) throw new Error('Não foi possível registrar a escolha. Tente novamente.');
            overlay.remove();
            loaded = false;
            await load(false);
          } catch (error) {
            feedback.textContent = error.message || 'Falha ao registrar renovação.';
            overlay.querySelectorAll('button').forEach(button => button.disabled = false);
          }
        });
      });
    }

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
      const mismatch = Number(data.monthlyFee || 0) === 0 && invoices.some(invoice => Number(invoice.amount || 0) > 0)
        ? '<p class="cs-note">Atenção: o valor mensal cadastrado no plano está em R$ 0,00, mas existem cobranças emitidas. Confirme o valor contratado no Gestão Software antes da próxima competência.</p>' : '';
      const scheduledCount = invoices.filter(invoice => invoice.status === 'SCHEDULED').length;
      const forecastNote = scheduledCount
        ? '<p class="cs-note">As ' + scheduledCount + ' competência(s) previstas ainda não possuem fatura registrada. ' +
          (Number(data.monthlyFee || 0) > 0 ? 'Solicite a regularização do ciclo financeiro antes da emissão.' : 'Informe uma mensalidade contratada maior que R$ 0,00 no Gestão para gerar boletos.') + '</p>'
        : '';
      const note = mismatch + forecastNote + '<p class="cs-note">Forma cadastrada: ' +
        safe(methods[data.paymentMethod] || data.paymentMethod || 'Não informada') +
        '. Clique em Gerar boleto para emitir uma fatura cadastrada ainda sem link. Segunda via reutiliza a cobrança já emitida.</p>';
      if (!invoices.length) {
        content.innerHTML = summary + note +
          '<div class="rc-empty">Nenhuma mensalidade emitida para esta empresa. Consulte o Gestão Software se esperava uma cobrança.</div>';
        return;
      }
      const rows = invoices.map(invoice => {
        const state = String(invoice.status || 'PENDING').toUpperCase();
        const paid = ['PAID', 'PAID_IN_CASH', 'CONFIRMED'].includes(state);
        const cancelled = ['CANCELLED', 'REFUNDED'].includes(state);
        const scheduled = state === 'SCHEDULED';
        const link = !paid && !cancelled && !scheduled ? url(invoice.invoiceUrl) : null;
        const action = scheduled
          ? '<span class="cs-muted">Competência prevista; boleto ainda não emitido.</span>'
          : link
          ? '<a class="rc-button" target="_blank" rel="noopener noreferrer" href="' + safe(link) +
            '">Abrir cobrança / 2ª via</a>'
          : paid ? '<span class="cs-muted">' + safe(statuses[state]) + '</span>'
          : cancelled ? '<span class="cs-muted">Cobrança cancelada</span>'
          : invoice.id && ['PENDING','OVERDUE'].includes(state)
          ? '<button type="button" class="rc-button cs-issue-invoice" data-issue-invoice="' + safe(invoice.id) + '">Gerar boleto</button>'
          : '<span class="cs-muted">Cobrança indisponível para emissão. Consulte o Gestão Software.</span>';
        return '<tr><td>' + safe(month(invoice.referenceMonth)) + '</td><td>' +
          (scheduled && Number(data.monthlyFee || 0) <= 0 ? 'A definir' : money(invoice.amount)) + '</td><td>' + safe(date(invoice.dueDate)) +
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

    async function issueInvoice(id, element) {
      // A aba deve abrir em resposta direta ao clique, antes da chamada assíncrona.
      const tab=window.open('about:blank','_blank');
      if(tab){tab.opener=null;tab.document.title='Emitindo boleto';tab.document.body.textContent='Aguarde, seu boleto está sendo gerado...';}
      element.disabled=true;
      message.textContent='Solicitando emissão desta mensalidade à Gestão...';
      try {
        const response=await fetch(api(endpoint+'/invoices/'+encodeURIComponent(id)+'/issue-boleto'),{
          method:'POST',headers:{Authorization:'Bearer '+(localStorage.getItem('nhPortalToken')||'')}
        });
        if(!response.ok){
          let detail={};try{detail=await response.json();}catch{}
          throw new Error(detail.message||detail.detail||'Não foi possível emitir o boleto (HTTP '+response.status+').');
        }
        const result=await response.json();
        const link=url(result.bankSlipUrl||result.invoiceUrl);
        if(!link)throw new Error('O Asaas não devolveu um link de pagamento. Procure o Financeiro para conciliar a cobrança.');
        if(tab)tab.location.replace(link);
        else message.textContent='Permita pop-ups para abrir em nova aba. Use o link Abrir cobrança disponível na tabela.';
        await load(false);
      } catch(error) {
        if(tab&&!tab.closed)tab.close();
        message.textContent=error.message||'Falha ao emitir mensalidade.';
      } finally {element.disabled=false;}
    }

    content.addEventListener('click',event=>{
      const button=event.target.closest('[data-issue-invoice]');
      if(button)issueInvoice(button.dataset.issueInvoice,button);
    });

    async function load(synchronize = false) {
      if (busy) return;
      busy = true;
      button.disabled = true;
      syncButton.disabled = true;
      button.textContent = 'Consultando...';
      syncButton.textContent = synchronize ? 'Sincronizando...' : 'Sincronizar com Asaas';
      message.textContent = synchronize ? 'Consultando cada cobrança existente no Asaas...' : 'Consultando as mensalidades no Gestão Software...';
      try {
        const response = await fetch(api(endpoint + (synchronize ? '/sync' : '')), {
          method: synchronize ? 'POST' : 'GET',
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
        const data = await response.json();
        render(data);
        loaded = true;
        promptRenewal(data);
        const failures = Number(data.failed || 0);
        message.textContent = synchronize
          ? failures > 0
            ? 'Sincronização parcial: ' + Number(data.checked || 0) + ' cobrança(s) conferida(s) e ' +
              failures + ' não verificada(s) no Asaas. Confira a integração no Gestão Software.'
            : 'Sincronização concluída: ' + Number(data.checked || 0) + ' verificada(s), ' +
              Number(data.changed || 0) + ' atualizada(s).'
          : 'Consulta atualizada.';
      } catch (error) {
        message.textContent = error.message || 'Erro ao consultar as mensalidades.';
        if (!loaded) content.innerHTML =
          '<div class="rc-empty">Não foi possível carregar as mensalidades. Verifique a integração e tente atualizar.</div>';
      } finally {
        busy = false;
        button.disabled = false;
        syncButton.disabled = false;
        button.textContent = 'Atualizar mensalidades';
        syncButton.textContent = 'Sincronizar com Asaas';
      }
    }

    button.addEventListener('click', () => load(true));
    syncButton.addEventListener('click', () => load(true));
    const tab = root.closest('[id^="tab-"]');
    const tabName = tab ? tab.id.replace(/^tab-/, '') : '';
    document.querySelectorAll('[data-tab="' + tabName + '"]').forEach(element =>
      element.addEventListener('click', () => { if (!loaded) load(true); }));
    // A notificação deve aparecer no login, mesmo sem abrir a aba Financeiro.
    // Espera uma autenticação recém-concluída na mesma aba.
    if (!tab || !tab.hidden) load(true);
    if (tab && tab.hidden && localStorage.getItem('nhPortalToken')) load(false);
    const authWatch = setInterval(() => {
      if (loaded) { clearInterval(authWatch); return; }
      if (localStorage.getItem('nhPortalToken') && !busy) load(false);
    }, 2500);
  });
})();
