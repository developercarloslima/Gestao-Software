/* Consulta de boletos compartilhada pelo ADM e Financeiro; nenhuma cobrança é criada no navegador. */
(function () {
  'use strict';
  const money = value => Number(value || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  const date = value => value ? String(value).slice(0, 10).split('-').reverse().join('/') : '—';
  const safe = value => String(value == null ? '' : value).replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  const validUrl = value => {
    try { const u = new URL(value); return u.protocol === 'https:' && !u.username && !u.password ? u.href : null; }
    catch { return null; }
  };
  const labels = { EM_DIA: 'Em dia', ATRASADO: 'Atrasado (1–6 dias)', INADIMPLENTE: 'Inadimplente (7–60 dias)',
    CANCELADO: 'Cancelado / acordo necessário', VENCIDO_60: 'Mais de 60 dias', PENDENTE: 'Pendente',
    PAGO: 'Pago', CONFIRMADO: 'Pagamento confirmado', PREVISTO: 'Previsto, não emitido', ESTORNADO: 'Estornado' };
  const label = value => labels[value] || String(value || '—').replaceAll('_', ' ');
  const badge = code => '<span class="rc-badge rc-' + safe(code) + '">' + safe(label(code)) + '</span>';

  document.querySelectorAll('[data-receivables-root]').forEach(root => {
    const endpoint = root.getAttribute('data-receivables-base');
    const authTokenKey = 'nhPortalToken';
    const url = path => window.NH_API && window.NH_API.backend ? window.NH_API.backend(path) : path;
    let page = 0;
    let totalPages = 0;
    let currentQuery = '';
    let selectedVehicle = null;
    let busy = false;
    const isActive = () => !root.closest('[id^="tab-"]')?.hidden;

    root.innerHTML =
      '<div class="rc-heading"><div><h2>Boletos dos associados</h2>' +
      '<p>Consulte nome, CPF ou placa. Sem busca, aparecem somente veículos com boletos vencidos a partir de 1 dia.</p></div>' +
      '<button class="rc-button rc-reload" type="button">Atualizar lista</button></div>' +
      '<form class="rc-form"><label class="rc-search-label">Pesquisar associado ou placa' +
      '<input class="rc-search" type="search" maxlength="100" autocomplete="off" placeholder="Nome, CPF ou placa"></label>' +
      '<button class="rc-button" type="submit">Pesquisar</button>' +
      '<button class="rc-button rc-clear" type="button">Limpar</button></form>' +
      '<p class="rc-feedback" role="status" aria-live="polite"></p>' +
      '<div class="rc-results"></div>' +
      '<div class="rc-pagination"><button type="button" class="rc-button rc-prev">Anterior</button>' +
      '<span class="rc-counter"></span><button type="button" class="rc-button rc-next">Próximo</button></div>';
    const search = root.querySelector('.rc-search');
    const feedback = root.querySelector('.rc-feedback');
    const results = root.querySelector('.rc-results');
    const counter = root.querySelector('.rc-counter');
    const prev = root.querySelector('.rc-prev');
    const next = root.querySelector('.rc-next');
    const dialog = document.createElement('dialog');
    dialog.className = 'rc-modal';
    dialog.innerHTML = '<div class="rc-dialog-head"><div class="rc-dialog-title"></div>' +
      '<div><button class="rc-button rc-sync-plate" type="button">Sincronizar boletos da placa</button> ' +
      '<button class="rc-button rc-close" type="button" aria-label="Fechar">Fechar</button></div></div>' +
      '<p class="rc-dialog-info"></p><div class="rc-dialog-content"></div>' +
      '<p class="rc-dialog-feedback" role="status" aria-live="polite"></p>';
    root.append(dialog);
    dialog.querySelector('.rc-close').addEventListener('click', () => dialog.close());

    async function request(path, opt) {
      const headers = { Authorization: 'Bearer ' + (localStorage.getItem(authTokenKey) || '') };
      if (opt && opt.body) headers['Content-Type'] = 'application/json';
      const res = await fetch(url(path), { ...(opt || {}), headers });
      if (!res.ok) {
        let error;
        try { error = await res.json(); } catch { /* resposta sem JSON */ }
        throw new Error(error?.message || error?.detail || (res.status === 401 || res.status === 403
          ? 'Sua sessão não permite consultar cobranças. Entre novamente.' : 'Falha na consulta HTTP ' + res.status));
      }
      return res.json();
    }
    const setMessage = (message, element) => { (element || feedback).textContent = message || ''; };

    function renderVehicles(data) {
      totalPages = Number(data.pages || 0);
      prev.disabled = page <= 0;
      next.disabled = page + 1 >= totalPages;
      counter.textContent = data.total ? 'Página ' + (page + 1) + ' de ' + totalPages + ' · ' + data.total + ' veículo(s)' : '';
      const items = data.items || [];
      if (!items.length) {
        results.innerHTML = '<p class="rc-empty">' + (currentQuery
          ? 'Nenhum associado ou placa encontrado para esta pesquisa.'
          : 'Não há associados com boleto vencido registrado nesta empresa.') + '</p>';
        return;
      }
      const groups = new Map();
      items.forEach(vehicle => {
        const key = vehicle.associateId;
        if (!groups.has(key)) groups.set(key, { name: vehicle.associateName, vehicles: [] });
        groups.get(key).vehicles.push(vehicle);
      });
      results.innerHTML = Array.from(groups.values()).map(group =>
        '<article class="rc-person"><h3>' + safe(group.name) + '</h3><div class="rc-plates">' +
        group.vehicles.map(vehicle =>
          '<div class="rc-plate">' +
          '<button type="button" class="rc-vehicle" data-vehicle="' + safe(vehicle.id) + '">' +
          '<strong>' + safe(vehicle.plate || 'Sem placa') + '</strong> · ' + safe(vehicle.model || 'Veículo') +
          '</button>' + badge(vehicle.status) +
          (vehicle.overdueDays > 0 ? '<span class="rc-days">' + vehicle.overdueDays + ' dia(s) vencido(s)</span>' : '') +
          (vehicle.overdueDays > 0 ? '<button class="rc-button rc-whatsapp" type="button" data-message="' +
             safe(vehicle.id) + '">Enviar mensagem de cobrança</button>' : '') +
          '</div>').join('') + '</div></article>').join('');
      root._rcRows = items;
    }

    async function load() {
      if (busy) return;
      busy = true;
      setMessage('Buscando boletos...');
      try {
        const data = await request(endpoint + '?q=' + encodeURIComponent(currentQuery) + '&page=' + page + '&size=30');
        renderVehicles(data);
        setMessage(currentQuery ? 'Resultados da pesquisa. Clique na placa para consultar os boletos.'
          : 'Atrasos identificados pela data de vencimento da cobrança em aberto.');
      } catch (error) {
        results.replaceChildren();
        setMessage(error.message);
      } finally { busy = false; }
    }

    async function showBills(vehicleId) {
      selectedVehicle = vehicleId;
      dialog.querySelector('.rc-dialog-content').innerHTML = '<p>Carregando boletos...</p>';
      setMessage('', dialog.querySelector('.rc-dialog-feedback'));
      if (!dialog.open) dialog.showModal();
      try {
        const data = await request(endpoint + '/vehicles/' + encodeURIComponent(vehicleId));
        if (selectedVehicle !== vehicleId) return;
        renderBills(data);
      } catch (error) {
        dialog.querySelector('.rc-dialog-content').textContent = error.message;
      }
    }

    function renderBills(data) {
      const vehicle = data.vehicle;
      dialog.querySelector('.rc-dialog-title').innerHTML = '<h2>' + safe(vehicle.associateName) + '</h2>' +
        '<p>Placa ' + safe(vehicle.plate || '—') + ' · ' + safe(vehicle.model || '') + ' ' + badge(vehicle.status) + '</p>';
      dialog.querySelector('.rc-dialog-info').textContent = data.information || '';
      const rows = data.bills || [];
      dialog.querySelector('.rc-dialog-content').innerHTML = rows.length ? rows.map(row => {
        const link = validUrl(row.bankSlipUrl);
        let actions = '';
        if (row.projected) {
          actions = '<span class="rc-note">Aguardando emissão do Asaas</span>';
        } else if (row.issued && row.status !== 'PAGO' && row.status !== 'CONFIRMADO' && row.status !== 'CANCELADO' && row.status !== 'ESTORNADO') {
          actions = (row.overdueDays > 0
            ? '<label class="rc-date-label">Novo vencimento <input class="rc-date" type="date" data-date="' +
              safe(row.id) + '" min="' + new Date(Date.now() + 86400000).toISOString().slice(0, 10) + '"></label>' : '') +
              '<button class="rc-button rc-copy" type="button" data-copy="' + safe(row.id) + '">' +
              (row.overdueDays > 0 ? 'Atualizar e gerar 2ª via' : 'Gerar 2ª via') + '</button>' +
              '<button class="rc-button rc-refresh" type="button" data-refresh="' + safe(row.id) + '">Verificar pagamento</button>';
          if (link) actions += '<a class="rc-button" href="' + safe(link) + '" target="_blank" rel="noopener noreferrer">Abrir boleto</a>';
          if (row.identificationField) actions += '<button class="rc-button rc-code" data-code="' +
              safe(row.identificationField) + '" type="button">Copiar linha digitável</button>';
        } else if (!row.issued) {
          actions = '<span class="rc-note">Cobrança ainda não emitida</span>';
        } else {
          actions = '<span class="rc-note">Boleto não disponível para reemissão</span>';
        }
        return '<div class="rc-bill"><div><strong>' + date(row.dueDate) + '</strong> · ' +
          money(row.amount) + ' ' + badge(row.status) +
          (row.overdueDays ? ' · ' + row.overdueDays + ' dia(s) em atraso' : '') +
          '</div><div class="rc-bill-actions">' + actions + '</div>' +
          (row.note ? '<small>' + safe(row.note) + '</small>' : '') + '</div>';
      }).join('') : '<p>Nenhum boleto localizado para este veículo.</p>';
      if (vehicle.agreementRequired) {
        dialog.querySelector('.rc-dialog-info').textContent +=
          ' Situação com mais de 60 dias ou plano cancelado: acordo e pagamento precisam ser confirmados pelo Financeiro antes da reativação.';
      }
    }

    async function renew(chargeId) {
      const field = Array.from(dialog.querySelectorAll('[data-date]')).find(x => x.dataset.date === chargeId);
      const dateValue = field ? field.value : null;
      if (field && (!dateValue || dateValue <= new Date().toISOString().slice(0, 10))) {
        setMessage('Selecione vencimento posterior à data de hoje.', dialog.querySelector('.rc-dialog-feedback'));
        return;
      }
      const action = Array.from(dialog.querySelectorAll('[data-copy]')).find(x => x.dataset.copy === chargeId);
      if (!action) return;
      action.disabled = true;
      setMessage('Consultando o Asaas...', dialog.querySelector('.rc-dialog-feedback'));
      try {
        if (field && !window.confirm('Alterar a data de vencimento no boleto original? Isso pode disparar notificação ao associado.')) return;
        const result = await request(endpoint + '/charges/' + encodeURIComponent(chargeId) + '/second-copy', {
          method: 'POST', body: JSON.stringify({ newDueDate: dateValue })
        });
        await showBills(selectedVehicle);
        setMessage(result.message || 'Cobrança atualizada.', dialog.querySelector('.rc-dialog-feedback'));
        const link = validUrl(result.bankSlipUrl);
        if (link) setMessage('Segunda via disponível. Clique em “Abrir boleto” acima para visualizar.', dialog.querySelector('.rc-dialog-feedback'));
        else if (result.identificationField) setMessage('Linha digitável: ' + result.identificationField,
          dialog.querySelector('.rc-dialog-feedback'));
        else setMessage('Cobrança atualizada, mas sem boleto disponível. Consulte o Asaas.',
          dialog.querySelector('.rc-dialog-feedback'));
        await load();
      } catch (error) { setMessage(error.message, dialog.querySelector('.rc-dialog-feedback')); }
      finally { action.disabled = false; }
    }

    root.querySelector('.rc-form').addEventListener('submit', event => {
      event.preventDefault(); currentQuery = search.value.trim(); page = 0; load();
    });
    root.querySelector('.rc-clear').addEventListener('click', () => {
      search.value = ''; currentQuery = ''; page = 0; load();
    });
    root.querySelector('.rc-reload').addEventListener('click', load);
    prev.addEventListener('click', () => { if (page > 0) { page--; load(); } });
    next.addEventListener('click', () => { if (page + 1 < totalPages) { page++; load(); } });
    results.addEventListener('click', event => {
      const element = event.target.closest('button');
      if (!element) return;
      if (element.dataset.vehicle) return showBills(element.dataset.vehicle);
      if (element.dataset.message) {
        const v = (root._rcRows || []).find(row => row.id === element.dataset.message);
        if (!v) return;
        const digits = String(v.whatsapp || '').replace(/\D/g, '');
        if (!digits) return setMessage('O associado não possui WhatsApp cadastrado.');
        const number = digits.startsWith('55') ? digits : '55' + digits;
        const text = 'Olá, ' + v.associateName + '. Identificamos uma mensalidade vencida do veículo ' +
          (v.plate || 'cadastrado') + '. Entre em contato com o financeiro da empresa para regularizar.';
        window.open('https://wa.me/' + number + '?text=' + encodeURIComponent(text),
          '_blank', 'noopener,noreferrer');
      }
    });
    dialog.addEventListener('click', async event => {
      const element = event.target.closest('button');
      if (!element) return;
      if (element.classList.contains('rc-sync-plate')) {
        if (!selectedVehicle) return;
        const vehicleId = selectedVehicle;
        element.disabled = true;
        setMessage('Sincronizando cobranças emitidas no Asaas...', dialog.querySelector('.rc-dialog-feedback'));
        try {
          const result = await request(endpoint + '/vehicles/' + encodeURIComponent(vehicleId) + '/sync',
            { method: 'POST' });
          if (vehicleId !== selectedVehicle) return;
          renderBills(result.bills);
          setMessage('Boletos verificados: ' + Number(result.checked || 0) +
            '. Falhas: ' + Number(result.failed || 0) + '. Nenhuma nova cobrança foi emitida.',
            dialog.querySelector('.rc-dialog-feedback'));
          await load();
        } catch (error) {
          setMessage(error.message, dialog.querySelector('.rc-dialog-feedback'));
        } finally { element.disabled = false; }
        return;
      }
      if (element.dataset.copy) return renew(element.dataset.copy);
      if (element.dataset.code) {
        try {
          await navigator.clipboard.writeText(element.dataset.code);
          setMessage('Linha digitável copiada.', dialog.querySelector('.rc-dialog-feedback'));
        } catch { setMessage('Copie manualmente: ' + element.dataset.code, dialog.querySelector('.rc-dialog-feedback')); }
      }
      if (element.dataset.refresh) {
        element.disabled = true;
        try {
          await request(endpoint + '/charges/' + encodeURIComponent(element.dataset.refresh) + '/refresh',
            { method: 'POST' });
          await showBills(selectedVehicle); await load();
          setMessage('Status sincronizado com o Asaas.', dialog.querySelector('.rc-dialog-feedback'));
        } catch (error) { setMessage(error.message, dialog.querySelector('.rc-dialog-feedback')); }
        finally { element.disabled = false; }
      }
    });

    const tab = root.closest('[id^="tab-"]')?.id.replace(/^tab-/, '');
    document.querySelectorAll('[data-tab="' + tab + '"]').forEach(button => button.addEventListener('click', load));
    if (isActive()) load();
  });
})();
