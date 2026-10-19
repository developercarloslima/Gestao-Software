const TOKEN_KEY = 'nhPortalToken';
const ROLE_KEY = 'nhPortalRole';
const CONSULTANT_KEY = 'nhSelectedConsultant';
const LAST_ACTIVITY_KEY = 'nhPortalLastActivityAt';
const INACTIVITY_LIMIT_MS = 20 * 60 * 1000;
const ACTIVITY_WRITE_THROTTLE_MS = 15 * 1000;
const $ = (id) => document.getElementById(id);
const token = localStorage.getItem(TOKEN_KEY);
const consultant = JSON.parse(localStorage.getItem(CONSULTANT_KEY) || 'null');
let inactivityTimer = null;
let lastActivityWriteAt = 0;

function tokenExpiresAtMs(value = token) {
  if (!value) return null;
  const parts = String(value).split('.');
  if (parts.length !== 5) return null;
  const seconds = Number(parts[2]);
  return Number.isFinite(seconds) ? seconds * 1000 : null;
}

function lastActivityAtMs() {
  const value = Number(localStorage.getItem(LAST_ACTIVITY_KEY));
  return Number.isFinite(value) && value > 0 ? value : null;
}

function inactivityExpired() {
  const lastActivity = lastActivityAtMs();
  return lastActivity !== null && Date.now() - lastActivity >= INACTIVITY_LIMIT_MS;
}

function scheduleInactivityCheck() {
  if (inactivityTimer) clearTimeout(inactivityTimer);
  inactivityTimer = null;
  if (!localStorage.getItem(TOKEN_KEY)) return;
  let lastActivity = lastActivityAtMs();
  if (lastActivity === null) {
    lastActivity = Date.now();
    localStorage.setItem(LAST_ACTIVITY_KEY, String(lastActivity));
  }
  const remaining = Math.max(0, INACTIVITY_LIMIT_MS - (Date.now() - lastActivity));
  inactivityTimer = setTimeout(() => {
    if (!localStorage.getItem(TOKEN_KEY)) return;
    if (inactivityExpired()) redirectToLogin('Sessão encerrada após 20 minutos de inatividade. Entre novamente.');
    else scheduleInactivityCheck();
  }, remaining + 100);
}

function markSessionActivity(force = false) {
  if (!localStorage.getItem(TOKEN_KEY)) return;
  const now = Date.now();
  if (!force && now - lastActivityWriteAt < ACTIVITY_WRITE_THROTTLE_MS) return;
  lastActivityWriteAt = now;
  localStorage.setItem(LAST_ACTIVITY_KEY, String(now));
  scheduleInactivityCheck();
}

function installInactivityTracking() {
  ['pointerdown', 'keydown', 'touchstart', 'scroll', 'mousemove'].forEach(eventName => {
    window.addEventListener(eventName, () => markSessionActivity(), { passive: true });
  });
  window.addEventListener('storage', event => {
    if (event.key === TOKEN_KEY && !event.newValue) {
      redirectToLogin();
      return;
    }
    if (event.key === LAST_ACTIVITY_KEY && localStorage.getItem(TOKEN_KEY)) scheduleInactivityCheck();
  });
}

function redirectToLogin(message = '') {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(ROLE_KEY);
  localStorage.removeItem(CONSULTANT_KEY);
  localStorage.removeItem(LAST_ACTIVITY_KEY);
  if (inactivityTimer) clearTimeout(inactivityTimer);
  if (message) sessionStorage.setItem('nhPortalLoginMessage', message);
  location.replace('/colaborador/');
}

const expiresAt = tokenExpiresAtMs();
if (!token || !consultant?.id || (expiresAt !== null && Date.now() >= expiresAt) || inactivityExpired()) {
  redirectToLogin(inactivityExpired() ? 'Sessão encerrada após 20 minutos de inatividade. Entre novamente.' : 'Sua sessão expirou. Entre novamente.');
}

installInactivityTracking();
if (lastActivityAtMs() === null) markSessionActivity(true);
else scheduleInactivityCheck();

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set('Authorization', `Bearer ${token}`);
  const response = await fetch(window.NH_API?.backend(path) || path, { ...options, headers });
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    if (response.status === 401 || response.status === 403) {
      redirectToLogin();
      const error = new Error('Sua sessão expirou. Entre novamente.');
      error.authExpired = true;
      throw error;
    }
    throw new Error(body?.message || 'Não foi possível concluir a solicitação.');
  }
  if (response.status === 204) return null;
  return response.json();
}

window.addEventListener('pageshow', () => {
  const exp = tokenExpiresAtMs();
  if (exp !== null && Date.now() >= exp) redirectToLogin('Sua sessão expirou. Entre novamente.');
  else if (inactivityExpired()) redirectToLogin('Sessão encerrada após 20 minutos de inatividade. Entre novamente.');
  else scheduleInactivityCheck();
});

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'visible') return;
  const exp = tokenExpiresAtMs();
  if (exp !== null && Date.now() >= exp) redirectToLogin('Sua sessão expirou. Entre novamente.');
  else if (inactivityExpired()) redirectToLogin('Sessão encerrada após 20 minutos de inatividade. Entre novamente.');
  else scheduleInactivityCheck();
});

function msg(text, type = 'error') {
  const element = $('message');
  element.className = `message ${type}`;
  element.textContent = text;
}

function normalizeCpf(value) {
  const digits = String(value || '').replace(/\D/g, '');
  return digits.length === 11 ? digits : null;
}

function vehicleLabel(data) {
  return data.plate || 'Veículo 0 km — sem placa';
}

const query = new URLSearchParams(location.search);
const quoteId = query.get('quoteId');
const linkedQuoteMode = Boolean(quoteId);
let linkedQuoteValidated = !linkedQuoteMode;

function isNewInspection() {
  return linkedQuoteMode;
}

function isZeroKm() {
  return linkedQuoteMode && $('zeroKm').value === 'true';
}

function setLockedInput(element, locked) {
  if (!element) return;
  element.readOnly = locked;
  element.setAttribute('aria-readonly', String(locked));
}

async function validateLinkedQuote() {
  if (!linkedQuoteMode) return;
  linkedQuoteValidated = false;
  $('submit-inspection').disabled = true;
  try {
    const dashboard = await api(`/api/consultant-dashboard/${encodeURIComponent(consultant.id)}`);
    const quote = (dashboard?.quotes || []).find(item => String(item.id) === String(quoteId));
    if (!quote) throw new Error('Cotação não encontrada no painel deste consultor.');
    if (quote.status !== 'ACCEPTED') {
      throw new Error('Esta cotação ainda não foi aceita. A nova vistoria só pode começar após a aceitação da cotação.');
    }
    if (quote.inspectionId) {
      throw new Error('Esta cotação já possui uma nova vistoria vinculada. Abra a vistoria existente no painel do consultor.');
    }

    $('associateName').value = quote.customerName || $('associateName').value;
    $('plate').value = (quote.plate || $('plate').value || '').toUpperCase();
    $('whatsapp').value = quote.whatsapp || $('whatsapp').value;
    $('zeroKm').value = String(Boolean(quote.zeroKm));
    $('vehicleType').value = (String(quote.categoryCode || '').startsWith('MOTORCYCLE') || String(quote.categoryCode || '') === 'SCOOTER_ELECTRIC')
      ? 'MOTORCYCLE'
      : 'FOUR_WHEELS_OR_MORE';
    syncVehicleFields();
    linkedQuoteValidated = true;
    $('submit-inspection').disabled = false;
    msg('Cotação aceita confirmada. Agora você pode gerar o Retrato NH.', 'success');
  } catch (error) {
    msg(error.message);
    $('submit-inspection').disabled = true;
  }
}

function configureFlow() {
  if (linkedQuoteMode && !$('requestType').querySelector('option[value="NEW_INSPECTION"]')) {
    const option = document.createElement('option');
    option.value = 'NEW_INSPECTION';
    option.textContent = 'Nova vistoria — vinculada à cotação aceita';
    $('requestType').append(option);
  }
  $('requestType').value = linkedQuoteMode ? 'NEW_INSPECTION' : 'BILL_UPDATE';
  $('requestType').disabled = true;

  if (linkedQuoteMode) {
    $('page-title').textContent = 'Nova vistoria vinculada à cotação';
    $('page-description').textContent = 'Esta vistoria será criada a partir da cotação aceita. Os dados comerciais permanecem vinculados à cotação.';
    $('flow-notice').textContent = 'Fluxo autorizado: Cotação aceita → Retrato NH → Nova vistoria → Equipe de análise.';
    $('contracted-plan-field').hidden = true;
    $('contractedPlan').required = false;
    $('contractedPlan').disabled = true;
    $('submit-inspection').textContent = 'Gerar link da nova vistoria';

    setLockedInput($('associateName'), true);
    setLockedInput($('whatsapp'), true);
    $('vehicleType').disabled = true;
    $('zeroKm').disabled = true;

    $('cpf').required = false;
    $('cpf').placeholder = 'Preencha somente se a cotação ainda não possuir CPF';
    $('cpf').closest('label')?.insertAdjacentHTML(
      'beforeend',
      '<small class="field-helper">Se o CPF já estiver salvo na cotação, este campo pode ficar em branco.</small>'
    );
  } else {
    $('page-title').textContent = 'Atualização de boleto';
    $('page-description').textContent = 'Cadastre os dados atuais do associado e informe o plano que ele já possui.';
    $('flow-notice').textContent = 'Fluxo: Retrato NH → Atualização de boleto → Equipe de análise.';
    $('contracted-plan-field').hidden = false;
    $('contractedPlan').disabled = false;
    $('contractedPlan').required = true;
    $('submit-inspection').textContent = 'Gerar link para atualização de boleto';

    setLockedInput($('associateName'), false);
    setLockedInput($('whatsapp'), false);
    $('vehicleType').disabled = false;
    $('zeroKm').value = 'false';
    $('zeroKm').disabled = true;
    $('cpf').required = true;
  }
}

function syncVehicleFields() {
  const zeroKm = isZeroKm();

  $('zero-km-field').hidden = !linkedQuoteMode;
  if (!linkedQuoteMode) $('zeroKm').value = 'false';

  $('plate').disabled = linkedQuoteMode ? true : zeroKm;
  $('plate').readOnly = linkedQuoteMode && !zeroKm;
  $('plate').required = !linkedQuoteMode && !zeroKm;
  $('plate').toggleAttribute('required', !linkedQuoteMode && !zeroKm);
  $('plate').setAttribute('aria-required', String(!linkedQuoteMode && !zeroKm));
  $('plate').setAttribute('aria-disabled', String(linkedQuoteMode || zeroKm));

  if (linkedQuoteMode) {
    $('plate').placeholder = zeroKm ? 'Veículo 0 km — sem placa' : 'Placa vinculada à cotação';
    $('manual-plate-required').hidden = true;
    $('manual-plate-help').textContent = zeroKm
      ? 'Veículo 0 km vinculado à cotação aceita.'
      : 'A placa vem da cotação aceita e não pode ser alterada aqui.';
  } else {
    $('plate').placeholder = 'ABC1D23';
    $('manual-plate-required').hidden = false;
    $('manual-plate-help').textContent = 'Obrigatória para identificar o veículo na atualização de boleto.';
  }
}

$('consultant-info').textContent = `Consultor responsável: ${consultant.name}`;

$('associateName').value = query.get('name') || '';
$('plate').value = (query.get('plate') || '').toUpperCase();
$('whatsapp').value = query.get('whatsapp') || '';
if (query.get('zeroKm') === 'true') $('zeroKm').value = 'true';
if (query.get('vehicleType')) $('vehicleType').value = query.get('vehicleType');

configureFlow();
syncVehicleFields();
validateLinkedQuote();

$('cpf').addEventListener('input', (event) => {
  const digits = event.target.value.replace(/\D/g, '').slice(0, 11);
  event.target.value = digits
    .replace(/(\d{3})(\d)/, '$1.$2')
    .replace(/(\d{3})(\d)/, '$1.$2')
    .replace(/(\d{3})(\d{1,2})$/, '$1-$2');
});

$('plate').addEventListener('input', (event) => {
  event.target.value = event.target.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 10);
});

function publicUrlFrom(data) {
  if (data?.publicToken) {
    return window.NH_URLS?.retratoUrl(data.publicToken) || data.publicUrl;
  }
  if (data?.publicUrl) {
    return window.NH_URLS?.retratoUrl(data.publicUrl) || data.publicUrl;
  }
  return '';
}

function communicationUrlFrom(data) {
  return data?.associateInspectionWhatsappUrl || data?.whatsappUrl || data?.teamWhatsappUrl || null;
}

$('inspection-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  syncVehicleFields();

  if (linkedQuoteMode && !linkedQuoteValidated) {
    msg('Aguarde a validação da cotação aceita antes de gerar a nova vistoria.');
    return;
  }

  if (!linkedQuoteMode && !$('plate').value.trim()) {
    msg('Informe a placa do veículo.');
    $('plate').focus();
    return;
  }

  if (!linkedQuoteMode && !$('contractedPlan').value.trim()) {
    msg('Informe o plano já contratado pelo associado.');
    $('contractedPlan').focus();
    return;
  }

  const typedCpf = normalizeCpf($('cpf').value);
  if (!linkedQuoteMode && !typedCpf) {
    msg('Informe um CPF válido.');
    $('cpf').focus();
    return;
  }

  try {
    let data;

    if (linkedQuoteMode) {
      const body = typedCpf ? { cpf: typedCpf } : {};
      data = await api(
        `/api/consultant-dashboard/${encodeURIComponent(consultant.id)}/quotes/${encodeURIComponent(quoteId)}/inspection`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        }
      );
    } else {
      data = await api('/api/inspections', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          consultantId: consultant.id,
          requestType: 'BILL_UPDATE',
          vehicleType: $('vehicleType').value,
          associateName: $('associateName').value.trim(),
          cpf: typedCpf,
          plate: $('plate').value.trim(),
          zeroKm: false,
          whatsapp: $('whatsapp').value,
          contractedPlan: $('contractedPlan').value.trim()
        })
      });
    }

    const currentPublicUrl = publicUrlFrom(data);

    $('result').hidden = false;
    $('public-link').value = currentPublicUrl;
    $('open-link').href = currentPublicUrl;

    const sendWhatsapp = $('send-whatsapp');
    const communicationUrl = communicationUrlFrom(data);
    if (communicationUrl) {
      sendWhatsapp.href = window.NH_URLS?.replaceLinkInCommunicationUrl(
        communicationUrl,
        data.publicUrl,
        currentPublicUrl
      ) || communicationUrl;
      sendWhatsapp.removeAttribute('aria-disabled');
      sendWhatsapp.classList.remove('disabled');
      sendWhatsapp.textContent = linkedQuoteMode ? 'Enviar link ao associado' : 'Enviar pelo WhatsApp';
      sendWhatsapp.title = linkedQuoteMode
        ? 'Enviar o link da nova vistoria ao associado'
        : 'Abrir a comunicação configurada para esta solicitação';
    } else {
      sendWhatsapp.removeAttribute('href');
      sendWhatsapp.setAttribute('aria-disabled', 'true');
      sendWhatsapp.classList.add('disabled');
      sendWhatsapp.title = 'Não há WhatsApp válido configurado para esta solicitação';
    }

    const planText = !linkedQuoteMode && data.contractedPlan ? ` · Plano: ${data.contractedPlan}` : '';
    $('result-summary').textContent =
      `${data.associateName} · ${vehicleLabel(data)}${planText} · link válido até ${new Date(data.expiresAt).toLocaleDateString('pt-BR')}`;

    $('result').scrollIntoView({ behavior: 'smooth' });
    msg(
      linkedQuoteMode
        ? 'Nova vistoria criada a partir da cotação aceita.'
        : 'Atualização de boleto registrada e encaminhada para o fluxo de análise.',
      'success'
    );
  } catch (error) {
    msg(error.message);
  }
});

$('send-whatsapp').addEventListener('click', (event) => {
  if (!$('send-whatsapp').hasAttribute('href')) {
    event.preventDefault();
    msg('Não há WhatsApp válido configurado para esta solicitação.');
  }
});

$('copy-link').addEventListener('click', async () => {
  await navigator.clipboard.writeText($('public-link').value);
  msg('Link copiado.', 'success');
});

$('new-request').addEventListener('click', () => {
  location.href = linkedQuoteMode ? '/cota/' : '/colaborador/retrato.html';
});
