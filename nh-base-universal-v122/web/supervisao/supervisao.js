const TOKEN_KEY = 'nhPortalToken';
const ROLE_KEY = 'nhPortalRole';
const CONSULTANT_KEY = 'nhSelectedConsultant';
const LAST_ACTIVITY_KEY = 'nhPortalLastActivityAt';
const INACTIVITY_LIMIT_MS = 20 * 60 * 1000;
const ACTIVITY_WRITE_THROTTLE_MS = 15 * 1000;
const $ = id => document.getElementById(id);
const brl = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

let token = localStorage.getItem(TOKEN_KEY);
let inactivityTimer = null;
let lastActivityWriteAt = 0;
let inspections = [];
let analysts = [];
let currentUser = null;
let activeDecisionCommunication = null;
let activeAnalysisQueue = 'review';
const mediaObjectUrls = new Set();
const mediaBlobCache = new Map();
const MEDIA_BLOB_CACHE_MAX = 18;

const STATUS = {
  WAITING_FILES: ['Aguardando arquivos', 'warn'],
  UPLOADING_FILES: ['Envio em andamento', 'warn'],
  CREATED: ['Pendente', 'warn'],
  UNDER_REVIEW: ['Em análise', 'warn'],
  COMPLETED: ['Aguardando análise', 'warn'],
  APPROVED: ['Aprovada', 'ok'],
  REJECTED: ['Rejeitada', 'off'],
  CANCELLED: ['Cancelada', 'off'],
  EXPIRED: ['Expirada', 'off']
};

const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;'
}[char]));
const date = value => value ? new Date(value).toLocaleString('pt-BR') : '—';
const hasFiles = item => Number(item?.assetCount || 0) > 0;

function renderCommercialDiscountOptions(selectId, item, selectedValue = 0) {
  const select = $(selectId);
  if (!select) return;
  const rules = Array.isArray(item?.commercialDiscounts) ? item.commercialDiscounts : [];
  const selected = Number(selectedValue || 0);
  select.dataset.discountRules = JSON.stringify(rules);
  const options = ['<option value="0">Sem desconto</option>'];
  rules.forEach(rule => {
    const unavailable = rule.available === false;
    const suffix = unavailable ? ` — indisponível${rule.unavailableReason ? `: ${rule.unavailableReason}` : ''}` : (rule.conditionText ? ` — ${rule.conditionText}` : '');
    options.push(`<option value="${Number(rule.percent)}" ${unavailable && Number(rule.percent) !== selected ? 'disabled' : ''}>${Number(rule.percent)}%${esc(suffix)}</option>`);
  });
  if (selected > 0 && !rules.some(rule => Number(rule.percent) === selected)) {
    options.push(`<option value="${selected}" selected>${selected}% — regra antiga; escolha uma regra atual para salvar alterações</option>`);
  }
  select.innerHTML = options.join('');
  select.value = String(selected);
}

function currentCommercialDiscount(config) {
  const select = $(config.discountId);
  if (!select) return null;
  let rules = [];
  try { rules = JSON.parse(select.dataset.discountRules || '[]'); } catch (_) { rules = []; }
  const percent = Number(select.value || 0);
  return rules.find(rule => Number(rule.percent) === percent) || null;
}

function commercialBenefitEffect(rule, code) {
  if (!rule || !Array.isArray(rule.benefitEffects)) return null;
  const normalized = String(code || '').trim().toUpperCase();
  return rule.benefitEffects.find(effect => String(effect.coverageCode || '').trim().toUpperCase() === normalized) || null;
}

function renderCommercialBenefits(containerId, item, locked = false) {
  const container = $(containerId);
  if (!container) return;
  container.innerHTML = '';
  const benefits = Array.isArray(item?.commercialBenefits) ? item.commercialBenefits : [];
  const currentPercent = Number(item?.pendingDiscountPercent ?? item?.discountPercent ?? 0);
  const currentRule = (Array.isArray(item?.commercialDiscounts) ? item.commercialDiscounts : [])
    .find(rule => Number(rule.percent) === currentPercent) || null;
  if (!benefits.length) {
    const empty = document.createElement('span');
    empty.className = 'commercial-benefits-empty';
    empty.textContent = 'Nenhum benefício cadastrado para este plano.';
    container.appendChild(empty);
    return;
  }
  benefits.forEach(benefit => {
    const code = String(benefit.code || '').toUpperCase();
    const currentEffect = commercialBenefitEffect(currentRule, code);
    const row = document.createElement('label');
    row.className = 'commercial-benefit-row';
    const input = document.createElement('input');
    input.type = 'checkbox';
    input.className = 'commercial-benefit-input';
    input.checked = benefit.selected === true;
    input.dataset.benefitCode = benefit.code || '';
    input.dataset.catalogStatus = benefit.catalogStatus || '';
    input.dataset.monthlyPrice = String(Number(benefit.monthlyPrice || 0));
    // Lock gerado pela regra do desconto atual não é estrutural: ao trocar o desconto
    // a tela precisa liberar/aplicar a nova regra. Locks de FIPE/plano permanecem.
    input.dataset.alwaysLocked = (benefit.locked && !currentEffect) ? 'true' : 'false';
    const defaultSelected = currentEffect
      ? String(benefit.catalogStatus || '').toUpperCase() === 'INCLUDED'
      : benefit.selected === true;
    input.dataset.baseSelected = defaultSelected ? 'true' : 'false';
    // Mantém a intenção manual do usuário separada das travas automáticas.
    // Antes, o evento change chamava refreshCommercialRules() e o checkbox
    // voltava imediatamente ao valor salvo, impedindo marcar/desmarcar.
    input.dataset.userSelected = defaultSelected ? 'true' : 'false';
    input.disabled = locked || input.dataset.alwaysLocked === 'true';
    const text = document.createElement('span');
    text.className = 'commercial-benefit-copy';
    const title = document.createElement('strong');
    title.textContent = benefit.name || benefit.code || 'Benefício';
    text.appendChild(title);
    const detail = document.createElement('small');
    detail.className = 'commercial-benefit-detail';
    const price = Number(benefit.monthlyPrice || 0);
    const baseDetail = [benefit.detail, price > 0 ? `Adicional: ${brl.format(price)}/mês` : null].filter(Boolean).join(' · ');
    detail.textContent = [baseDetail, benefit.lockReason].filter(Boolean).join(' · ');
    detail.dataset.baseDetail = baseDetail;
    detail.dataset.benefitDetail = 'true';
    text.appendChild(detail);
    row.append(input, text);
    container.appendChild(row);
  });
}

function selectedCommercialBenefits(containerId) {
  const container = $(containerId);
  if (!container) return [];
  return [...container.querySelectorAll('input[data-benefit-code]:checked')].map(input => input.dataset.benefitCode);
}

function refreshCommercialRules(config, locked = false) {
  const discountEl = $(config.discountId);
  const brandingEl = $(config.brandingId);
  const fipeEl = $(config.fipeId);
  const benefitsEl = $(config.benefitsId);
  if (!discountEl || !brandingEl || !benefitsEl) return;
  const discount = Number(discountEl.value || 0);
  const rule = currentCommercialDiscount(config);

  if (discount > 0 && rule?.requiresConfirmation) {
    brandingEl.value = rule.rearWindowBranding || 'ANY_PERFORATED';
  } else {
    brandingEl.value = 'NOT_APPLICABLE';
  }
  // A condição do vigia é definida pela própria regra administrativa. Não há mais
  // seleção fixa “duas logos / somente NH” para os descontos novos.
  brandingEl.disabled = true;

  const parse = window.NHMoney?.parse;
  const fipe = typeof parse === 'function' ? parse(fipeEl?.value || '') : Number(String(fipeEl?.value || '').replace(/[^0-9,]/g, '').replace(',', '.'));
  benefitsEl.querySelectorAll('input[data-benefit-code]').forEach(input => {
    const code = String(input.dataset.benefitCode || '').toUpperCase();
    const alwaysLocked = input.dataset.alwaysLocked === 'true';
    input.checked = (input.dataset.userSelected || input.dataset.baseSelected) === 'true';
    input.disabled = locked || alwaysLocked;
    const detail = input.parentElement?.querySelector('[data-benefit-detail]');
    if (detail) detail.textContent = detail.dataset.baseDetail || '';

    // Regra estrutural para limite de terceiros por FIPE/desconto. Ela é
    // prioritária e não pode ser substituída por regra administrativa.
    if (code === 'THIRD_PARTY_BASE') {
      input.checked = true;
      input.disabled = true;
      if (detail && Number.isFinite(fipe)) {
        detail.textContent = `Cobertura de até ${fipe >= 51000 && discount === 0 ? 'R$ 100 mil' : 'R$ 50 mil'} · Regra automática por FIPE.`;
      }
    }

    const effect = code === 'THIRD_PARTY_BASE' ? null : commercialBenefitEffect(rule, code);
    if (discount > 0 && effect) {
      if (effect.action === 'REMOVE') input.checked = false;
      if (effect.action === 'FORCE_INCLUDE') input.checked = true;
      if (effect.action === 'REMOVE' || effect.action === 'FORCE_INCLUDE') input.disabled = true;
      if (detail) {
        const effectParts = [];
        if (effect.detailOverride) effectParts.push(effect.detailOverride);
        else if (detail.textContent) effectParts.push(detail.textContent);
        if (effect.amountOverride != null) effectParts.push(`Limite/valor: ${brl.format(Number(effect.amountOverride))}`);
        if (effect.action === 'REMOVE') effectParts.push(`Removido pela regra administrativa do desconto de ${discount}%.`);
        if (effect.action === 'FORCE_INCLUDE') effectParts.push(`Incluído obrigatoriamente pela regra administrativa do desconto de ${discount}%.`);
        if (effect.action === 'KEEP') effectParts.push(`Mantido com regra específica do desconto de ${discount}%.`);
        detail.textContent = effectParts.filter(Boolean).join(' · ');
      }
    }

    const row = input.closest('.commercial-benefit-row');
    row?.classList.toggle('is-selected', input.checked);
    row?.classList.toggle('is-locked', input.disabled);
  });
}

function setCommercialPreviewValue(id, value) {
  const element = $(id);
  if (element) element.textContent = brl.format(Number.isFinite(Number(value)) ? Number(value) : 0);
}

const commercialPricingTimers = new WeakMap();

function parseCommercialMoneyInput(element) {
  const parse = window.NHMoney?.parse;
  if (!element) return NaN;
  return typeof parse === 'function'
    ? parse(element.value || '')
    : Number(String(element.value || '').replace(/[^0-9,]/g, '').replace(',', '.'));
}

function markCommercialMonthlyAutomatic(config) {
  const monthlyEl = $(config.monthlyId);
  if (monthlyEl) monthlyEl.dataset.manualOverride = 'false';
}

async function requestCommercialPricingPreview(config, applyCalculatedValue = true) {
  const inspectionId = $(config.inspectionIdId)?.value;
  const benefitsEl = $(config.benefitsId);
  const discountEl = $(config.discountId);
  const fipeEl = $(config.fipeId);
  const monthlyEl = $(config.monthlyId);
  const finalNote = $(config.previewFinalNoteId);
  if (!inspectionId || !benefitsEl || !discountEl || !fipeEl) return;

  const fipeValue = parseCommercialMoneyInput(fipeEl);
  if (!Number.isFinite(fipeValue) || fipeValue <= 0) {
    if (finalNote) finalNote.textContent = 'Informe uma FIPE válida para recalcular a mensalidade.';
    return;
  }

  const discountPercent = Number(discountEl.value || 0) || 0;
  const benefitCodes = selectedCommercialBenefits(config.benefitsId);
  const sequence = (Number(benefitsEl.dataset.pricingRequestSequence || 0) || 0) + 1;
  benefitsEl.dataset.pricingRequestSequence = String(sequence);
  if (finalNote) finalNote.textContent = 'Recalculando pela tabela atual do plano...';

  try {
    const pricing = await api(`${config.previewApiBase}/${encodeURIComponent(inspectionId)}/contract-pricing-preview`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fipeValue, discountPercent, benefitCodes })
    });
    if (Number(benefitsEl.dataset.pricingRequestSequence || 0) !== sequence) return;

    const base = Number(pricing.planBaseMonthlyValue || 0);
    const mandatory = Number(pricing.mandatoryMonthlyFee || 0);
    const optionals = Number(pricing.optionalsMonthlyValue || 0);
    const subtotal = Number(pricing.subtotalBeforeDiscount || 0);
    const discountValue = Number(pricing.discountValue || 0);
    const finalValue = Number(pricing.finalMonthlyValue || 0);
    const oneTime = Number(pricing.oneTimeFee || 0);

    setCommercialPreviewValue(config.previewBaseId, base);
    setCommercialPreviewValue(config.previewMandatoryId, mandatory);
    setCommercialPreviewValue(config.previewOptionalsId, optionals);
    setCommercialPreviewValue(config.previewSubtotalId, subtotal);
    setCommercialPreviewValue(config.previewOneTimeId, oneTime);
    setCommercialPreviewValue(config.previewDiscountId, discountValue);
    setCommercialPreviewValue(config.previewFinalId, finalValue);
    benefitsEl.dataset.calculatedFinal = String(finalValue);

    const mandatoryLabel = $(config.previewMandatoryLabelId);
    if (mandatoryLabel) {
      mandatoryLabel.textContent = mandatory > 0 ? 'Rastreador / taxa mensal aplicável' : 'Rastreador / taxa mensal (não aplicável)';
      mandatoryLabel.title = pricing.mandatoryFeeDescription || '';
    }

    const discountLabel = $(config.previewDiscountLabelId);
    if (discountLabel) discountLabel.textContent = discountPercent > 0 ? `Desconto (${discountPercent}%)` : 'Desconto';

    const sourceText = pricing.catalogBased
      ? 'Preço calculado agora pelo catálogo atual: base do plano por FIPE/tipo do veículo + rastreador/taxas aplicáveis + serviços adicionais selecionados - desconto. Valores antigos não são usados como base.'
      : 'Preço reconstruído pelos componentes disponíveis da cotação.';
    const feeText = mandatory > 0 && pricing.mandatoryFeeDescription
      ? ` ${pricing.mandatoryFeeDescription}`
      : (mandatory === 0 ? ' Nenhuma mensalidade de rastreador/taxa obrigatória foi adicionada.' : '');
    if (finalNote) finalNote.textContent = sourceText + feeText;

    if (applyCalculatedValue && monthlyEl && !monthlyEl.disabled) {
      monthlyEl.value = window.NHMoney?.format(finalValue) || finalValue.toFixed(2).replace('.', ',');
      monthlyEl.dataset.manualOverride = 'false';
      window.NHMoney?.refresh(monthlyEl);
    }
    return pricing;
  } catch (error) {
    if (Number(benefitsEl.dataset.pricingRequestSequence || 0) !== sequence) return;
    if (finalNote) finalNote.textContent = `Não foi possível recalcular pela tabela: ${error.message || 'erro inesperado'}`;
    return null;
  }
}

function scheduleCommercialPricingPreview(config, applyCalculatedValue = true, delay = 250) {
  const benefitsEl = $(config.benefitsId);
  if (!benefitsEl) return;
  const previous = commercialPricingTimers.get(benefitsEl);
  if (previous) window.clearTimeout(previous);
  const timer = window.setTimeout(() => {
    commercialPricingTimers.delete(benefitsEl);
    requestCommercialPricingPreview(config, applyCalculatedValue);
  }, delay);
  commercialPricingTimers.set(benefitsEl, timer);
}

function initializeCommercialPricingPreview(config) {
  markCommercialMonthlyAutomatic(config);
  scheduleCommercialPricingPreview(config, true, 0);
}

function bindCommercialPricingPreview(config) {
  const discountEl = $(config.discountId);
  const fipeEl = $(config.fipeId);
  const benefitsEl = $(config.benefitsId);
  const monthlyEl = $(config.monthlyId);

  discountEl?.addEventListener('change', () => {
    refreshCommercialRules(config, discountEl.disabled);
    markCommercialMonthlyAutomatic(config);
    scheduleCommercialPricingPreview(config, true, 0);
  });
  fipeEl?.addEventListener('input', () => {
    refreshCommercialRules(config, fipeEl.disabled);
    markCommercialMonthlyAutomatic(config);
    scheduleCommercialPricingPreview(config, true, 300);
  });
  benefitsEl?.addEventListener('change', event => {
    if (!event.target?.matches?.('input[data-benefit-code]')) return;
    const input = event.target;
    // Não reaplica o snapshot salvo depois de um clique manual. A escolha fica
    // viva até o usuário salvar; regras obrigatórias continuam protegidas por
    // disabled e são reavaliadas quando FIPE/desconto mudam.
    input.dataset.userSelected = input.checked ? 'true' : 'false';
    const row = input.closest('.commercial-benefit-row');
    row?.classList.toggle('is-selected', input.checked);
    markCommercialMonthlyAutomatic(config);
    scheduleCommercialPricingPreview(config, true, 0);
  });
}

const SUPERVISION_COMMERCIAL_CONFIG = Object.freeze({
  discountId: 'supervision-contract-discount',
  brandingId: 'supervision-contract-branding',
  fipeId: 'supervision-contract-fipe',
  benefitsId: 'supervision-contract-benefits',
  monthlyId: 'supervision-contract-monthly',
  inspectionIdId: 'inspection-id',
  previewApiBase: '/api/supervision/inspections',
  previewBaseId: 'supervision-preview-base',
  previewMandatoryId: 'supervision-preview-mandatory',
  previewMandatoryLabelId: 'supervision-preview-mandatory-label',
  previewOptionalsId: 'supervision-preview-optionals',
  previewSubtotalId: 'supervision-preview-subtotal',
  previewOneTimeId: 'supervision-preview-one-time',
  previewDiscountId: 'supervision-preview-discount',
  previewFinalId: 'supervision-preview-final',
  previewFinalNoteId: 'supervision-preview-final-note',
  previewDiscountLabelId: 'supervision-preview-discount-label'
});




function inspectionAssetAvailable(item, type, sortOrder) {
  return Array.isArray(item?.assets) && item.assets.some(asset =>
    asset?.available === true && asset?.type === type && Number(asset?.sortOrder) === Number(sortOrder)
  );
}

function inspectionPendingCount(item) {
  if (!item) return 0;
  if (item.requestType !== 'NEW_INSPECTION') {
    return inspectionAssetAvailable(item, 'VIDEO', 1) ? 0 : 1;
  }

  const photoCount = item.vehicleType === 'MOTORCYCLE' ? 7 : 15;
  let pending = 0;
  for (let order = 1; order <= photoCount; order += 1) {
    if (!inspectionAssetAvailable(item, 'PHOTO', order)) pending += 1;
  }
  if (!inspectionAssetAvailable(item, 'VIDEO', photoCount + 1)) pending += 1;
  if (!inspectionAssetAvailable(item, 'SIGNATURE', photoCount + 2)) pending += 1;
  if (!inspectionAssetAvailable(item, 'VEHICLE_DOCUMENT', photoCount + 3)) pending += 1;
  if (!inspectionAssetAvailable(item, 'IDENTITY_DOCUMENT', photoCount + 4)) pending += 1;
  if (!inspectionAssetAvailable(item, 'IDENTITY_DOCUMENT', photoCount + 5)) pending += 1;
  if (!item.residenceAddress) pending += 1;
  return pending;
}

function inspectionNeedsFiles(item) {
  return inspectionPendingCount(item) > 0;
}

const ANALYSIS_QUEUE_INFO = {
  analyst_pending: { label: 'Vistorias pendentes dos analistas', description: 'Todas as vistorias que ainda estão com os analistas: sem documentos, com documentos pendentes, aguardando análise ou ainda sem Cadastro feito.' },
  review: { label: 'Aguardando supervisão', description: 'Vistorias marcadas como Cadastro feito pelos analistas e que aguardam sua decisão final.' },
  acceptance: { label: 'Aguardando aceite', description: 'Vistorias aprovadas e aguardando os três aceites. Prazo de 7 dias; reaprovação necessária quando vencido.' },
  accepted: { label: 'Aceites realizados', description: 'Assinaturas obrigatórias concluídas automaticamente ou movimentação manual da Supervisão. A movimentação manual não equivale a assinatura.' },
  rejected: { label: 'Rejeitadas', description: 'Vistorias já rejeitadas pela Supervisão de Análise.' }
};

function inspectionQueueKey(item) {
  if (item?.status === 'APPROVED') return (item.allThreeAcceptancesCompleted || item.acceptanceQueueManualAt) ? 'accepted' : 'acceptance';
  if (item?.status === 'REJECTED') return 'rejected';
  if (item?.analysisStage === 'ANALYST_QUEUE' || item?.analysisStage === 'ANALYST_PENDING') return 'analyst_pending';
  return 'review';
}

function analystPendingLabel(item) {
  if (inspectionNeedsFiles(item)) return 'Aguardando documentos';
  if (item?.reviewedByRole === 'ANALYST' && item?.status === 'UNDER_REVIEW' && !item?.registrationCompletedAt) {
    return 'Cadastro não feito';
  }
  return 'Aguardando análise do analista';
}

function badge(status, item = null) {
  if (item?.analysisStage === 'SUPERVISION_QUEUE') {
    return `<span class="badge ok">Cadastro realizado</span>`;
  }
  if (item?.analysisStage === 'ANALYST_PENDING') {
    return `<span class="badge warn">${esc(analystPendingLabel(item))}</span>`;
  }
  if (item?.analysisStage === 'ANALYST_QUEUE') {
    return `<span class="badge warn">${esc(analystPendingLabel(item))}</span>`;
  }
  const [label, kind] = STATUS[status] || [status, ''];
  return `<span class="badge ${kind}">${esc(label)}</span>`;
}

function formatPhone(value) {
  const digits = String(value || '').replace(/\D/g, '');
  if (!digits) return '';
  const local = digits.startsWith('55') ? digits.slice(2) : digits;
  return local.length === 11
    ? `(${local.slice(0, 2)}) ${local.slice(2, 7)}-${local.slice(7)}`
    : digits;
}

function digitalAcceptanceGps(item) {
  const lat = Number(item?.digitalAcceptanceLatitude);
  const lon = Number(item?.digitalAcceptanceLongitude);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return 'Não disponível';
  const accuracy = Number(item?.digitalAcceptanceAccuracyMeters);
  return `${lat.toFixed(6)}, ${lon.toFixed(6)}${Number.isFinite(accuracy) ? ` · precisão aprox. ${Math.round(accuracy)} m` : ''}`;
}


function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(ROLE_KEY);
  localStorage.removeItem(CONSULTANT_KEY);
  localStorage.removeItem(LAST_ACTIVITY_KEY);
  if (inactivityTimer) clearTimeout(inactivityTimer);
  inactivityTimer = null;
  token = null;
  currentUser = null;
  analysts = [];
}

function tokenExpiresAtMs(value = token) {
  if (!value) return null;
  const parts = String(value).split('.');
  if (parts.length !== 5) return null;
  const seconds = Number(parts[2]);
  return Number.isFinite(seconds) ? seconds * 1000 : null;
}

function sessionExpired() {
  const expiresAt = tokenExpiresAtMs();
  return expiresAt !== null && Date.now() >= expiresAt;
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
  if (!token) return;
  let lastActivity = lastActivityAtMs();
  if (lastActivity === null) {
    lastActivity = Date.now();
    localStorage.setItem(LAST_ACTIVITY_KEY, String(lastActivity));
  }
  const remaining = Math.max(0, INACTIVITY_LIMIT_MS - (Date.now() - lastActivity));
  inactivityTimer = setTimeout(() => {
    if (!token) return;
    if (inactivityExpired()) {
      showLogin('Sessão encerrada após 20 minutos de inatividade. Entre novamente.');
      return;
    }
    scheduleInactivityCheck();
  }, remaining + 100);
}

function markSessionActivity(force = false) {
  if (!token) return;
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
      showLogin('Sua sessão foi encerrada. Entre novamente.');
      return;
    }
    if (event.key === LAST_ACTIVITY_KEY && token) scheduleInactivityCheck();
  });
}

function showLogin(text = '') {
  clearSession();
  $('analysis-login').hidden = false;
  $('analysis-view').hidden = true;
  $('logout').hidden = true;
  const box = $('login-message');
  box.className = text ? 'message error' : '';
  box.textContent = text;
}

function collaboratorDisplayName(value) {
  const text = String(value || '').trim();
  if (!text || text !== text.toUpperCase()) return text;
  return text.toLocaleLowerCase('pt-BR').replace(/(^|\s)([a-záàâãéêíóôõúç])/g, (_, space, letter) => space + letter.toLocaleUpperCase('pt-BR'));
}

function showView() {
  $('analysis-login').hidden = true;
  $('analysis-view').hidden = false;
  $('logout').hidden = false;
  const rawName = currentUser?.consultantName || currentUser?.displayName || currentUser?.username || 'Analista';
  const name = collaboratorDisplayName(rawName);
  if ($('analysis-welcome-title')) $('analysis-welcome-title').textContent = `Olá, ${name}`;
  if ($('analysis-welcome-text')) $('analysis-welcome-text').textContent = 'Bem-vindo, seu painel de Supervisão de Análise está pronto e atualizado.';
}

function activeAnalysisDialog() {
  const opened = [...document.querySelectorAll('dialog.admin-dialog[open]')];
  return opened.length ? opened[opened.length - 1] : null;
}

function analysisDialogMessageElement(dialog) {
  if (!dialog) return null;
  let element = dialog.querySelector('[data-dialog-message]');
  if (element) return element;

  const card = dialog.querySelector('.dialog-card');
  if (!card) return null;

  element = document.createElement('div');
  element.dataset.dialogMessage = 'true';
  element.setAttribute('role', 'alert');
  element.setAttribute('aria-live', 'polite');
  const head = card.querySelector('.dialog-head');
  if (head) head.insertAdjacentElement('afterend', element);
  else card.prepend(element);
  return element;
}

function clearAnalysisDialogMessage(dialog) {
  const element = dialog?.querySelector('[data-dialog-message]');
  if (!element) return;
  element.className = '';
  element.textContent = '';
}

function message(text, type = 'error') {
  const dialog = activeAnalysisDialog();
  if (dialog) {
    const element = analysisDialogMessageElement(dialog);
    if (element) {
      element.className = `message ${type} dialog-message`;
      element.textContent = text;
      element.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      return;
    }
  }

  const element = $('message');
  element.className = `message ${type}`;
  element.textContent = text;
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (token) headers.set('Authorization', `Bearer ${token}`);
  const response = await fetch(window.NH_API?.backend(path) || path, { ...options, headers });
  if (token && response.status === 401) {
    showLogin('Sua sessão expirou. Entre novamente.');
    const error = new Error('Sessão inválida.');
    error.authExpired = true;
    throw error;
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new Error(body?.message || 'Não foi possível concluir a operação.');
  }
  return response.status === 204 ? null : response.json();
}


async function analysisSessionStillValid() {
  if (!token) return false;
  const headers = new Headers({ Authorization: `Bearer ${token}` });
  try {
    const response = await fetch(window.NH_API?.backend('/api/auth/me') || '/api/auth/me', { headers, cache: 'no-store' });
    return response.ok;
  } catch (_) {
    return true;
  }
}

function normalizedCpf(value) {
  return String(value || '').replace(/\D/g, '');
}

async function apiBlob(path) {
  const headers = new Headers();
  if (token) headers.set('Authorization', `Bearer ${token}`);

  let response = null;
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const requestPath = attempt === 0
      ? path
      : `${path}${path.includes('?') ? '&' : '?'}_nhRetry=${Date.now()}`;
    try {
      response = await fetch(window.NH_API?.backend(requestPath) || requestPath, { headers, cache: 'no-store' });
      break;
    } catch (networkError) {
      if (attempt === 1) {
        throw new Error('A conexão com o servidor foi interrompida durante o download. Tente novamente; o sistema preservou os arquivos da vistoria.');
      }
      await new Promise(resolve => window.setTimeout(resolve, 700));
    }
  }

  if (!response) throw new Error('Não foi possível iniciar o download.');
  if (token && response.status === 401) {
    showLogin('Sua sessão expirou. Entre novamente.');
    const error = new Error('Sessão inválida.');
    error.authExpired = true;
    throw error;
  }
  if (token && response.status === 403) {
    const sessionValid = await analysisSessionStillValid();
    if (!sessionValid) {
      showLogin('Sua sessão expirou. Entre novamente.');
      const error = new Error('Sessão inválida.');
      error.authExpired = true;
      throw error;
    }
    const body = await response.json().catch(() => null);
    throw new Error(body?.message || 'Não foi possível baixar este arquivo. Sua sessão continua ativa.');
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new Error(body?.message || 'Não foi possível carregar o arquivo.');
  }
  return response.blob();
}

const ASSET_LABELS = {
  PHOTO: 'Foto da vistoria',
  VIDEO: 'Vídeo da vistoria',
  SIGNATURE: 'Assinatura',
  VEHICLE_DOCUMENT: 'CRLV do veículo',
  IDENTITY_DOCUMENT: 'RG ou CNH',
  OTHER_DOCUMENT: 'Documento adicional',
  REPORT: 'Relatório da vistoria'
};

function formatBytes(value) {
  const bytes = Number(value || 0);
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
}

function releaseMediaUrls() {
  mediaObjectUrls.forEach(url => URL.revokeObjectURL(url));
  mediaObjectUrls.clear();
}

function rememberPreviewBlob(key, blob) {
  if (!blob || Number(blob.size || 0) > 5 * 1024 * 1024) return;
  if (mediaBlobCache.has(key)) mediaBlobCache.delete(key);
  mediaBlobCache.set(key, blob);
  while (mediaBlobCache.size > MEDIA_BLOB_CACHE_MAX) {
    mediaBlobCache.delete(mediaBlobCache.keys().next().value);
  }
}

async function previewBlob(inspectionId, assetId) {
  const key = `${inspectionId}:${assetId}`;
  if (mediaBlobCache.has(key)) return mediaBlobCache.get(key);
  let lastError = null;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      const blob = await apiBlob(`/api/supervision/inspections/${inspectionId}/assets/${assetId}`);
      rememberPreviewBlob(key, blob);
      return blob;
    } catch (error) {
      if (error?.authExpired) throw error;
      lastError = error;
      if (attempt < 2) await new Promise(resolve => window.setTimeout(resolve, 450 * (attempt + 1)));
    }
  }
  throw lastError || new Error('Prévia indisponível.');
}

function showPreviewFallback(image, loading) {
  if (image) image.hidden = true;
  if (!loading) return;
  loading.classList.add('is-fallback');
  loading.innerHTML = 'Prévia indisponível<small>O arquivo continua disponível para download.</small>';
}

function scheduleImagePreviews(inspectionId, assets) {
  const queue = [...assets];
  const run = async () => {
    let cursor = 0;
    const worker = async () => {
      while (cursor < queue.length) {
        const asset = queue[cursor++];
        await loadImagePreview(inspectionId, asset).catch(() => {});
      }
    };
    await Promise.allSettled(Array.from({ length: Math.min(3, queue.length) }, worker));
  };
  if ('requestIdleCallback' in window) window.requestIdleCallback(() => run(), { timeout: 350 });
  else window.setTimeout(run, 0);
}

function confirmAnalysisAction(title, text, confirmLabel = 'Confirmar') {
  return new Promise(resolve => {
    const dialog = $('analysis-confirm-dialog');
    const action = $('analysis-confirm-action');
    $('analysis-confirm-title').textContent = title;
    $('analysis-confirm-text').textContent = text;
    action.textContent = confirmLabel;
    const onClose = () => {
      dialog.removeEventListener('close', onClose);
      resolve(dialog.returnValue === 'default');
    };
    dialog.addEventListener('close', onClose);
    dialog.showModal();
  });
}

async function boot() {
  if (!token) {
    showLogin();
    return;
  }
  if (sessionExpired()) {
    showLogin('Sua sessão expirou. Entre novamente.');
    return;
  }
  try {
    const me = await api('/api/auth/me');
    currentUser = me;
    localStorage.setItem(ROLE_KEY, me.role);
    if (me.role === 'ADMIN') { location.replace('/admin/'); return; }
    if (me.role !== 'SUPERVISION_ANALYSIS') {
      if (me.role === 'ANALYST') location.replace('/analise/');
      else if (me.role === 'TOW_DRIVER') location.replace('/guincho/');
      else if (me.role === 'WORKSHOP_MANAGER') location.replace('/oficina/');
      else if (me.role === 'EVENT_OPERATOR') location.replace('/checklist/');
      else if (me.role === 'BUYER') location.replace('/financeiro/');
      else location.replace('/colaborador/');
      return;
    }
    showView();
    if (me.passwordChangeRequired) { openFirstPasswordDialog(); return; }
    await load();
  } catch (error) {
    if (!error?.authExpired && !$('analysis-view').hidden) message(error.message);
  }
}

async function load() {
  const button = $('refresh');
  button.disabled = true;
  try {
    [inspections, analysts] = await Promise.all([
      api('/api/supervision/inspections'),
      api('/api/supervision/inspections/analysts')
    ]);
    render();
  } finally {
    button.disabled = false;
  }
}

function actionLink(url, label, style = 'outline') {
  if (!url) return '';
  return `<a class="button ${style} small-button" href="${esc(url)}" target="_blank" rel="noopener">${esc(label)}</a>`;
}

function inspectionMatchesFilter(item, filter) {
  if (!filter) return true;
  return `${item.associateName} ${item.plate || ''} ${item.consultantName} ${item.assignedAnalystName || ''} ${item.reviewedByName || ''} ${item.supervisionNote || ''} ${item.status}`
    .toLowerCase()
    .includes(filter);
}

function inspectionRows(items, emptyMessage) {
  const rows = items.map(item => {
    const currentPublicUrl = item.publicUrl
      ? (window.NH_URLS?.retratoUrl(item.publicUrl) || item.publicUrl)
      : null;
    const filesAvailable = hasFiles(item);
    const needsFiles = inspectionNeedsFiles(item);
    const partialResubmission = filesAvailable && needsFiles;
    const statusActions = filesAvailable
      ? `<button class="outline small-button" data-analyze="${item.id}" type="button">Ver documentos enviados</button>`
      : '';
    const pendingActions = needsFiles && item.status !== 'APPROVED'
      ? `${actionLink(item.associateInspectionWhatsappUrl, partialResubmission ? 'Enviar pendências' : 'Enviar link', 'secondary')}${actionLink(currentPublicUrl, partialResubmission ? 'Refazer pendências' : 'Fazer vistoria')}`
      : '';
    const renewal = item.status === 'APPROVED' && item.acceptanceReapprovalRequired && !item.allThreeAcceptancesCompleted
      ? `<button class="secondary small-button" type="button" data-reapprove="${esc(item.id)}">Reaprovar e gerar novos links (7 dias)</button>`
      : '';

    const canMoveQueue = item.status === 'APPROVED' && !item.allThreeAcceptancesCompleted;
    const queueMoveAction = canMoveQueue
      ? item.acceptanceQueueManualAt
        ? `<button class="outline small-button" type="button" data-acceptance-queue="undo" data-queue-inspection="${esc(item.id)}">Voltar para Aguardando aceite</button>`
        : `<button class="secondary small-button" type="button" data-acceptance-queue="mark-completed" data-queue-inspection="${esc(item.id)}">Mover para Aceites realizados</button>`
      : '';
    const acceptanceSummary = item.status === 'APPROVED'
      ? `<small class="table-code">${Number(item.acceptanceCompletedCount || 0)}/3 assinaturas reais · ${item.acceptanceQueueManualAt && !item.allThreeAcceptancesCompleted ? 'Movido manualmente — há assinaturas pendentes' : item.allThreeAcceptancesCompleted ? 'Concluídas' : item.acceptanceReapprovalRequired ? 'Prazo vencido' : 'Aguardando'}</small>`
      : '';

    return `<tr>
      <td><strong>${esc(item.associateName)}</strong><small class="table-code">${esc(formatPhone(item.whatsapp) || 'Sem WhatsApp')}</small></td>
      <td>${esc(item.plate || '0 km — sem placa')}</td>
      <td>${esc(item.consultantName)}</td>
      <td>${esc(item.assignedAnalystName || 'Não vinculado')}</td>
      <td>${date(item.completedAt || item.createdAt)}</td>
      <td><div class="status-with-action">${badge(item.status, item)}${item.status === 'APPROVED' ? `<small class="table-code">${Number(item.acceptanceCompletedCount || 0)}/3 · ${item.acceptanceQueueManualAt && !item.allThreeAcceptancesCompleted ? 'Movido manualmente (assinaturas pendentes)' : item.acceptanceReapprovalRequired ? 'Prazo vencido' : item.allThreeAcceptancesCompleted ? 'Concluídos' : 'Aguardando'}</small>` : ''}${statusActions}</div></td>
      <td><div class="row-actions">${renewal}${queueMoveAction}${pendingActions}<button class="secondary small-button" data-analyze="${item.id}" type="button">Analisar</button></div>${acceptanceSummary}</td>
    </tr>`;
  }).join('');

  return rows || `<tr><td colspan="7" class="empty-state">${esc(emptyMessage)}</td></tr>`;
}

function queueCountLabel(total) {
  return `${total} ${total === 1 ? 'registro' : 'registros'}`;
}

function render() {
  const filter = $('filter').value.trim().toLowerCase();
  const filtered = inspections.filter(item => inspectionMatchesFilter(item, filter));

  const counts = { analyst_pending: 0, review: 0, acceptance: 0, accepted: 0, rejected: 0 };
  filtered.forEach(item => { counts[inspectionQueueKey(item)] += 1; });
  Object.entries(counts).forEach(([key, value]) => {
    const counter = $(`analysis-tab-${key}-count`);
    if (counter) counter.textContent = value;
  });

  document.querySelectorAll('[data-analysis-queue]').forEach(button => {
    button.classList.toggle('active', button.dataset.analysisQueue === activeAnalysisQueue);
    button.setAttribute('aria-pressed', button.dataset.analysisQueue === activeAnalysisQueue ? 'true' : 'false');
  });
  const queueInfo = ANALYSIS_QUEUE_INFO[activeAnalysisQueue] || ANALYSIS_QUEUE_INFO.review;
  $('analysis-status-description').textContent = queueInfo.description;

  const queueItems = filtered.filter(item => inspectionQueueKey(item) === activeAnalysisQueue);
  const newInspections = queueItems.filter(item => item.requestType === 'NEW_INSPECTION');
  const billingUpdates = queueItems.filter(item => item.requestType !== 'NEW_INSPECTION');

  $('new-inspections-body').innerHTML = inspectionRows(newInspections, `Nenhuma nova vistoria em “${queueInfo.label}”.`);
  $('billing-inspections-body').innerHTML = inspectionRows(billingUpdates, `Nenhuma atualização de boleto em “${queueInfo.label}”.`);
  $('new-inspections-count').textContent = queueCountLabel(newInspections.length);
  $('billing-inspections-count').textContent = queueCountLabel(billingUpdates.length);

  document.querySelectorAll('[data-analyze]').forEach(button => {
    button.addEventListener('click', () => openInspection(button.dataset.analyze));
  });
  document.querySelectorAll('[data-reapprove]').forEach(button => {
    button.addEventListener('click', async () => {
      const id = button.dataset.reapprove;
      const item = inspections.find(row => row.id === id);
      if (!item || !item.acceptanceReapprovalRequired) return;
      const reason = window.prompt('A Supervisão confirma nova aprovação para mais 7 dias. Informe o motivo (mínimo 5 caracteres):');
      if (reason === null) return;
      if (reason.trim().length < 5) return message('Informe um motivo com pelo menos cinco caracteres.');
      button.disabled = true;
      try {
        await api(`/api/supervision/inspections/${encodeURIComponent(id)}/acceptance/reapprove`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ reason: reason.trim() })
        });
        await load();
        message('Reaprovação registrada. Novos links para os aceites pendentes liberados por 7 dias. A analista poderá enviá-los.', 'success');
      } catch (error) { message(error.message); }
      finally { button.disabled = false; }
    });
  });
  document.querySelectorAll('[data-acceptance-queue]').forEach(button => {
    button.addEventListener('click', async () => {
      const id = button.dataset.queueInspection;
      const action = button.dataset.acceptanceQueue;
      const item = inspections.find(row => row.id === id);
      if (!item || item.status !== 'APPROVED' || !['mark-completed','undo'].includes(action)) return;
      const moving = action === 'mark-completed';
      const reason = window.prompt(moving
        ? 'Motivo para mover para Aceites realizados mesmo com assinaturas pendentes (mínimo cinco caracteres):'
        : 'Motivo para voltar para Aguardando aceite (mínimo cinco caracteres):');
      if (reason === null) return;
      if (reason.trim().length < 5) return message('Informe ao menos cinco caracteres.');
      const confirmed = await confirmAnalysisAction(
        moving ? 'Mover manualmente para Aceites realizados?' : 'Desfazer movimentação manual?',
        moving
          ? `Apenas a fila será modificada. Existem ${Number(item.acceptanceCompletedCount || 0)}/3 aceites verdadeiros: nenhum contrato ou termo pendente será considerado assinado. O motivo e o responsável ficarão na auditoria.`
          : 'Os aceites digitais já realizados serão preservados; a fila voltará a acompanhar o estado real das assinaturas.',
        moving ? 'Mover na fila' : 'Desfazer movimentação'
      );
      if (!confirmed) return;
      button.disabled = true;
      try {
        await api(`/api/supervision/inspections/${encodeURIComponent(id)}/acceptance/queue/${action}`, {
          method:'POST', headers:{'Content-Type':'application/json'},
          body:JSON.stringify({reason:reason.trim()})
        });
        activeAnalysisQueue = moving ? 'accepted' : 'acceptance';
        await load();
        message(moving ? 'Movida manualmente: termos não assinados continuam pendentes.' :
          'Movimentação manual desfeita; as assinaturas continuam intactas.', 'success');
      } catch(error){ message(error.message); }
      finally{ button.disabled=false; }
    });
  });
}

function details(items) {
  return items.map(([label, value]) => `<div><span>${esc(label)}</span><strong>${esc(value ?? '—')}</strong></div>`).join('');
}

function links(items) {
  return items
    .filter(([url]) => url)
    .map(([url, label, style = 'outline']) => `<a class="button ${style}" href="${esc(url)}" target="_blank" rel="noopener">${esc(label)}</a>`)
    .join('');
}

function populateAnalystReviewer(item) {
  const wrap = $('inspection-analyst-wrap');
  const select = $('inspection-analyst');
  const save = $('save-inspection-analyst');
  if (!wrap || !select || !save) return;
  wrap.hidden = Boolean(item.digitalAcceptedAt) || ['CANCELLED', 'EXPIRED'].includes(item.status);
  select.replaceChildren(new Option('Selecione um analista ativo', ''));
  (Array.isArray(analysts) ? analysts : []).filter(a => a.active !== false).forEach(a => {
    select.add(new Option(a.name, a.id));
  });
  select.value = item.assignedAnalystId || '';
  save.disabled = wrap.hidden;
  $('inspection-analyst-helper').textContent = item.assignedAnalystName
    ? `Responsável atual: ${item.assignedAnalystName}. A alteração ficará registrada no histórico.`
    : 'Escolha um analista ativo. A mudança ficará registrada no histórico.';
}

function configureStatusOptions(item) {
  const select = $('inspection-status');
  if (item?.status === 'APPROVED' || item?.status === 'REJECTED') select.value = item.status;
  else select.value = '';

  const pendingAnalyst = item?.analysisStage === 'ANALYST_QUEUE' || item?.analysisStage === 'ANALYST_PENDING';
  // A Supervisão usa o marcador explícito do cadastro. Não inferimos cadastro
  // realizado apenas porque a vistoria está/esteve aprovada.
  const registrationCompleted = Boolean(item?.registrationCompletedAt);
  const registrationBlocked = Boolean(item?.digitalAcceptedAt) || ['CANCELLED', 'EXPIRED'].includes(item?.status);
  const canManageRegistration = !registrationBlocked;
  const canDecide = item?.analysisStage === 'SUPERVISION_QUEUE' || (['APPROVED', 'REJECTED'].includes(item?.status) && !item?.digitalAcceptedAt);

  // A Supervisão pode reabrir ou concluir novamente o cadastro em qualquer viewport,
  // inclusive após aprovação, até o associado efetivar o aceite digital.
  $('supervision-registration-actions').hidden = !canManageRegistration;
  const supervisionRegistrationNotComplete = $('supervision-registration-not-complete');
  const supervisionRegistrationComplete = $('supervision-registration-complete');
  // Supervisor pode escolher qualquer estado até o aceite digital; o estado atual
  // fica destacado, mas ambos os controles permanecem disponíveis.
  supervisionRegistrationNotComplete.disabled = !canManageRegistration;
  supervisionRegistrationComplete.disabled = !canManageRegistration;
  supervisionRegistrationNotComplete.classList.toggle('is-current', !registrationCompleted);
  supervisionRegistrationComplete.classList.toggle('is-current', registrationCompleted);
  supervisionRegistrationNotComplete.setAttribute('aria-pressed', String(!registrationCompleted));
  supervisionRegistrationComplete.setAttribute('aria-pressed', String(registrationCompleted));
  $('supervision-decision-status-wrap').hidden = pendingAnalyst;
  $('supervision-decision-note-wrap').hidden = pendingAnalyst;
  $('save-supervision-decision').hidden = !canDecide;
  select.disabled = !canDecide;
  $('inspection-note').readOnly = !canDecide;

  if (canManageRegistration) {
    const pending = inspectionPendingCount(item);
    if (registrationCompleted) {
      $('supervision-registration-helper').textContent = item?.status === 'APPROVED'
        ? 'A vistoria está aprovada e o cadastro está realizado. Antes do aceite digital, a Supervisão pode marcar Cadastro não realizado para reabrir e corrigir os dados.'
        : 'O cadastro está realizado. Se precisar corrigir a vistoria antes do aceite digital, marque Cadastro não realizado para reabrir a etapa.';
    } else {
      $('supervision-registration-helper').textContent = pending > 0
        ? `Existem ${pending} ${pending === 1 ? 'pendência' : 'pendências'} de arquivo/documento. A Supervisão pode assumir a responsabilidade e registrar Cadastro realizado mesmo assim.`
        : 'O cadastro está aberto. Depois das correções, registre Cadastro realizado para liberar a decisão final.';
    }
    $('supervision-registration-note').value = item?.adminNote || '';
  } else {
    $('supervision-registration-note').value = '';
  }
}

function supervisionStageLabel(item) {
  if (item?.status === 'APPROVED') return 'Aprovada';
  if (item?.status === 'REJECTED') return 'Rejeitada';
  if (item?.analysisStage === 'SUPERVISION_QUEUE') return 'Cadastro realizado · aguardando decisão da supervisão';
  if (item?.analysisStage === 'ANALYST_PENDING') return `Pendência do analista · ${analystPendingLabel(item)}`;
  if (item?.analysisStage === 'ANALYST_QUEUE') return analystPendingLabel(item);
  return 'Em acompanhamento';
}


async function syncSupervisionResponsibilityTermBox(item) {
  const box = $('supervision-responsibility-term-box');
  const send = $('supervision-send-responsibility-term');
  const copy = $('supervision-copy-responsibility-term');
  const open = $('supervision-open-responsibility-term');
  const download = $('supervision-download-responsibility-term');
  const status = $('supervision-responsibility-term-status');
  const damages = $('supervision-responsibility-term-damages');
  const available = Boolean(item?.id);
  box.hidden = !available;
  if (!available) return;
  [send, copy, open, download].forEach(button => { if (button) button.dataset.inspectionId = item.id; });
  if (damages) { damages.value = item.preexistingDamageDetails || ''; damages.disabled = false; }
  if (send) { send.disabled = false; send.textContent = 'Enviar somente o termo'; }
  if (copy) copy.disabled = false;
  if (open) open.disabled = false;
  if (download) download.disabled = false;
  if (status) status.textContent = item.preexistingDamageDetails
    ? 'Termo com aceite pendente. As avarias registradas já estão preenchidas; o documento pode ser baixado agora ou enviado ao associado para aceite.'
    : 'Termo com aceite pendente. Informe as avarias para preparar o documento; ele ficará disponível para download antes e depois do aceite.';
  try {
    const info = await api(`/api/supervision/inspections/${encodeURIComponent(item.id)}/responsibility-term`);
    if (!info) return;
    box.dataset.publicUrl = info.publicUrl || '';
    if (damages && info.damageDetails) { damages.value = info.damageDetails; damages.disabled = Boolean(info.accepted); }
    if (info.accepted) {
      const gps = info.acceptanceLatitude != null && info.acceptanceLongitude != null
        ? ` · GPS ${Number(info.acceptanceLatitude).toFixed(6)}, ${Number(info.acceptanceLongitude).toFixed(6)}` : '';
      const sentTo = info.whatsappSentTo ? ` · enviado para ${info.whatsappSentTo}` : '';
      const ip = info.acceptanceIp ? ` · IP ${info.acceptanceIp}` : '';
      const inheritedAcceptance = info.acceptanceSource === 'DOSSIER_ACCEPTANCE' ? ' · aceite integrado do dossiê original' : '';
      if (status) status.textContent = `✓ Termo assinado digitalmente em ${date(info.acceptedAt)}${inheritedAcceptance}${sentTo}${ip}${gps}. O termo assinado está incorporado ao dossiê final e permanece disponível para download separado.`;
      if (send) { send.disabled = true; send.textContent = 'Termo assinado'; }
      if (copy) { copy.disabled = true; copy.textContent = 'Reenvio bloqueado'; }
      if (open) { open.disabled = false; open.textContent = 'Abrir termo assinado'; }
      if (download) { download.disabled = false; download.textContent = 'Baixar termo assinado (PDF)'; }
      if (dossierDownload) dossierDownload.hidden = !item.digitalAcceptedAt;
    } else if (info.sentAt) {
      const sentTo = info.whatsappSentTo ? ` para ${info.whatsappSentTo}` : '';
      if (status) status.textContent = `Termo com aceite pendente. Enviado${sentTo} em ${date(info.sentAt)} e aguardando confirmação do associado.`;
    } else if (status) status.textContent = 'Termo com aceite pendente. Disponível para envio e download.';
  } catch (_) {}
}

async function prepareSupervisionResponsibilityTerm(id) {
  if (!id) throw new Error('Vistoria não encontrada para o Termo de Responsabilidade.');
  const damageDetails = String($('supervision-responsibility-term-damages')?.value || '').trim();
  return api(`/api/supervision/inspections/${encodeURIComponent(id)}/responsibility-term/prepare`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ damageDetails: damageDetails || null })
  });
}

function openInspection(id) {
  const item = inspections.find(value => value.id === id);
  if (!item) return;
  const approvedDossier = item.status === 'APPROVED';
  if (item.expiredWithoutFiles && !approvedDossier) {
    window.alert('Vistoria/cotação vencida, precisa ser refeita.');
    return;
  }

  releaseMediaUrls();
  const filesAvailable = hasFiles(item);
  const needsFiles = inspectionNeedsFiles(item);
  const pendingCount = inspectionPendingCount(item);
  const currentPublicUrl = item.publicUrl
    ? (window.NH_URLS?.retratoUrl(item.publicUrl) || item.publicUrl)
    : null;

  $('inspection-id').value = item.id;
  $('dialog-title').textContent = `${item.plate || '0 km — sem placa'} — ${item.associateName}`;
  $('edit-associate-name').value = item.associateName || '';
  $('edit-associate-cpf').value = item.cpf || item.maskedCpf || '';
  $('edit-associate-whatsapp').value = formatPhone(item.whatsapp) || '';
  $('edit-associate-plate').value = item.plate || '';
  $('edit-zero-km').value = item.zeroKm ? 'true' : 'false';
  $('edit-vehicle-model').value = item.vehicleModel || '';
  $('edit-model-year').value = item.modelYear || '';
  $('edit-residence-address').value = item.residenceAddress || '';
  $('supervision-contract-fipe').value = window.NHMoney?.format(item.pendingFipeValue ?? item.fipeValue) || (item.pendingFipeValue ?? item.fipeValue ?? '');
  $('supervision-contract-monthly').value = window.NHMoney?.format(item.pendingMonthlyValue ?? item.monthlyValue) || (item.pendingMonthlyValue ?? item.monthlyValue ?? '');
  $('supervision-contract-plan').textContent = item.selectedPlanName || item.contractedPlan || '—';
  renderCommercialDiscountOptions('supervision-contract-discount', item, item.pendingDiscountPercent ?? item.discountPercent ?? 0);
  $('supervision-contract-branding').value = item.pendingRearWindowBranding || item.rearWindowBranding || 'NOT_APPLICABLE';
  window.NHMoney?.refresh($('supervision-contract-fipe'));
  window.NHMoney?.refresh($('supervision-contract-monthly'));
  const editableLocked = Boolean(item.digitalAcceptedAt);
  const digitalLock = $('supervision-digital-lock');
  if (digitalLock) digitalLock.hidden = !editableLocked;
  ['edit-associate-name','edit-associate-cpf','edit-associate-whatsapp','edit-associate-plate','edit-zero-km','edit-vehicle-model','edit-model-year','edit-residence-address','save-editable-details']
    .forEach(id => { const el = $(id); if (el) el.disabled = editableLocked; });
  const hasContractValues = item.requestType === 'NEW_INSPECTION' && item.fipeValue != null && item.monthlyValue != null;
  const contractLocked = Boolean(item.digitalAcceptedAt);
  renderCommercialBenefits('supervision-contract-benefits', item, contractLocked || !hasContractValues);
  refreshCommercialRules(SUPERVISION_COMMERCIAL_CONFIG, contractLocked || !hasContractValues);
  initializeCommercialPricingPreview(SUPERVISION_COMMERCIAL_CONFIG, item);
  const contractSection = $('supervision-contract-values-section');
  if (contractSection) contractSection.hidden = item.requestType !== 'NEW_INSPECTION';
  ['supervision-contract-fipe','supervision-contract-monthly','supervision-contract-discount','supervision-contract-branding','supervision-save-contract-values']
    .forEach(id => { const el = $(id); if (el) el.disabled = contractLocked || !hasContractValues; });
  const contractHelper = $('supervision-contract-values-helper');
  if (contractHelper) contractHelper.textContent = contractLocked
    ? 'O aceite digital do associado já foi concluído. O dossiê comercial está bloqueado para edição.'
    : (hasContractValues
        ? 'A Supervisão pode corrigir FIPE, desconto, mensalidade, benefícios e adicionais inclusive em vistoria aprovada, até o associado concluir o aceite digital.'
        : 'Esta vistoria não possui valores de cotação vinculados para edição.');

  const pendingContractBox = $('supervision-contract-change-pending');
  if (pendingContractBox) {
    const canSendContractAcceptance = Boolean(item.contractChangePending && item.registrationCompletedAt);
    pendingContractBox.hidden = !canSendContractAcceptance;
    if (canSendContractAcceptance) {
      $('supervision-contract-change-pending-text').textContent = `FIPE proposta: ${brl.format(item.pendingFipeValue)} · mensalidade com os adicionais atuais: ${brl.format(item.pendingMonthlyValue)}. O contrato só será atualizado depois da confirmação do associado.`;
      const sendLink = $('supervision-send-contract-change-link');
      sendLink.href = item.contractChangeWhatsappUrl || item.contractChangeConfirmationUrl || '#';
      sendLink.hidden = !(item.contractChangeWhatsappUrl || item.contractChangeConfirmationUrl);
      $('supervision-copy-contract-change-link').dataset.url = item.contractChangeConfirmationUrl || '';
    }
  }
  $('inspection-note').value = item.adminNote || '';
  $('supervision-note').value = item.supervisionNote || '';
  $('supervision-note').disabled = editableLocked;
  $('save-supervision-note').disabled = editableLocked;
  $('supervision-note-meta').textContent = item.supervisionNoteUpdatedAt
    ? `Última atualização: ${date(item.supervisionNoteUpdatedAt)}${item.supervisionNoteByName ? ` por ${item.supervisionNoteByName}` : ''}. A observação fica visível para o analista.`
    : 'A observação ficará visível para o analista ao abrir esta vistoria.';
  populateAnalystReviewer(item);
  configureStatusOptions(item);

  const retentionText = filesAvailable
    ? (needsFiles
      ? `${pendingCount} ${pendingCount === 1 ? 'item pendente' : 'itens pendentes'}; os demais arquivos continuam armazenados no sistema.`
      : 'Arquivos confirmados permanecem disponíveis junto à vistoria.')
    : (Number(item.expiredAssetCount || 0) > 0
      ? 'Há arquivo histórico indisponível. Novos arquivos confirmados permanecem vinculados à vistoria.'
      : 'Aguardando envio do associado.');

  const discountPercent = Number(item.discountPercent || 0);
  const rearWindowBrandingLabel = item.rearWindowBranding === 'ANY_PERFORATED'
    ? 'Perfurado no vigia traseiro'
    : item.rearWindowBranding === 'NH_AND_OTHER_COMPANY'
      ? 'Perfurado com NH + outra empresa'
      : item.rearWindowBranding === 'NH_ONLY'
        ? 'Perfurado somente com a logomarca NH'
        : 'Não se aplica';

  const inspectionDetails = [
    ['Associado', item.associateName],
    ['CPF', item.cpf || item.maskedCpf || '—'],
    ['WhatsApp', formatPhone(item.whatsapp) || '—'],
    ['Modelo', item.vehicleModel || '—'],
    ['Ano do modelo', item.modelYear || '—'],
    ['Consultor', item.consultantName],
    ['Analista responsável', item.assignedAnalystName || 'Não vinculado'],
    ['Cadastro feito por', item.registrationCompletedByName || '—'],
    ['Etapa', supervisionStageLabel(item)],
    ['Fila de aceites', item.acceptanceQueueManualAt && !item.allThreeAcceptancesCompleted
      ? `Aceites realizados (movido por ${item.acceptanceQueueManualBy || 'Supervisão'} em ${date(item.acceptanceQueueManualAt)}; assinaturas pendentes)`
      : item.allThreeAcceptancesCompleted ? 'Aceites assinados' : 'Fila automática'],
    ['Motivo da movimentação', item.acceptanceQueueManualReason || '—'],
    ['Placa', item.plate || '0 km — sem placa'],
    ['Tipo', item.requestType === 'NEW_INSPECTION' ? 'Nova vistoria' : 'Atualização de boleto']
  ];

  if (item.requestType === 'BILL_UPDATE') {
    inspectionDetails.push(['Plano já contratado', item.contractedPlan || '—']);
  }

  if (item.requestType === 'NEW_INSPECTION') {
    inspectionDetails.push(['PDF da cotação', item.quotationPdfUrl ? 'Disponível para visualização' : '—']);
    if (item.billingDueDay) inspectionDetails.push(['Vencimento mensal', `Dia ${item.billingDueDay}`]);
    if (item.firstBillingDueDate) {
      inspectionDetails.push(['Primeiro vencimento', new Date(`${item.firstBillingDueDate}T12:00:00`).toLocaleDateString('pt-BR')]);
    }
  }

  if (item.requestType === 'NEW_INSPECTION' && discountPercent > 0) {
    inspectionDetails.push(['Desconto da cotação', `${discountPercent}%`]);
    if (item.rearWindowBranding && item.rearWindowBranding !== 'NOT_APPLICABLE') {
      inspectionDetails.push(['Condição do vigia traseiro', rearWindowBrandingLabel]);
    }
  }

  inspectionDetails.push(
    ['Criada em', date(item.createdAt)],
    ['Concluída em', date(item.completedAt)],
    ['Arquivos disponíveis', item.assetCount],
    ['Situação dos arquivos', retentionText],
    ['Endereço', item.residenceAddress || '—'],
    ['Responsável pela última análise', item.reviewedByName || '—'],
    ['Observação da última análise', item.adminNote || '—'],
    ['O.B.S. Supervisão', item.supervisionNote || '—'],
    ['Aceite digital do associado', item.digitalAcceptedAt ? `Confirmado em ${date(item.digitalAcceptedAt)}` : (item.status === 'APPROVED' ? 'Aguardando confirmação WebAuthn' : '—')],
    ['WhatsApp que recebeu o aceite', item.digitalAcceptedAt ? (formatPhone(item.digitalAcceptanceWhatsappSentTo) || 'Não registrado') : '—'],
    ['ID do aparelho do aceite', item.digitalAcceptedAt ? (item.digitalAcceptanceDeviceId || 'Não registrado') : '—'],
    ['GPS do aparelho no aceite', item.digitalAcceptedAt ? digitalAcceptanceGps(item) : '—'],
    ['GPS capturado em', item.digitalAcceptedAt ? date(item.digitalAcceptanceLocationCapturedAt) : '—'],
    ['Verificação WebAuthn', item.digitalAcceptedAt ? (item.digitalAcceptanceUserVerified ? 'Usuário verificado pelo aparelho' : 'Registrada') : '—'],
    ['Hash da prova digital', item.digitalAcceptanceProofHash || '—']
  );

  $('inspection-details').innerHTML = details(inspectionDetails);

  const discountNote = $('discount-validation-note');
  if (item.requestType === 'NEW_INSPECTION' && discountPercent > 0 && item.rearWindowBranding && item.rearWindowBranding !== 'NOT_APPLICABLE') {
    discountNote.hidden = false;
    discountNote.className = 'message';
    discountNote.textContent = `Validação do desconto de ${discountPercent}%: antes de aprovar, confira na foto da traseira se o perfurado do vigia traseiro está claramente visível.`;
  } else {
    discountNote.hidden = true;
    discountNote.textContent = '';
  }

  $('inspection-links').innerHTML = links([
    [needsFiles ? item.associateInspectionWhatsappUrl : null, filesAvailable ? 'Enviar link para refazer pendências' : 'Enviar link ao associado', 'secondary'],
    [currentPublicUrl, needsFiles && filesAvailable ? 'Abrir link das pendências' : 'Abrir link da vistoria'],
    [item.requestType === 'NEW_INSPECTION' ? item.quotationPdfUrl : null, 'Ver PDF da cotação'],
    [!filesAvailable ? item.teamWhatsappUrl : null, 'Comunicar equipe pelo WhatsApp']
  ]);

  renderInspectionFiles(item);
  showNotificationButton(item);
  $('message').className = '';
  $('message').textContent = '';
  clearAnalysisDialogMessage($('inspection-dialog'));
  window.NHInspectionDocuments?.render({ rootId: 'supervision-document-center', item, role: 'supervision', api, sendDossier: undefined, downloadDossier: undefined });
  $('inspection-dialog').showModal();
}

function renderInspectionFiles(item) {
  const section = $('inspection-files-section');
  const grid = $('inspection-files-grid');
  const sourceAssets = Array.isArray(item.assets) ? item.assets : [];
  const assets = item.completedAt && !sourceAssets.some(asset => asset.type === 'REPORT')
    ? [...sourceAssets, { id: 'regenerated-report', type: 'REPORT', label: 'Relatório da vistoria', fileName: 'relatorio-vistoria.pdf', fileSize: 0, contentType: 'application/pdf', available: false }]
    : sourceAssets;
  const available = assets.filter(asset => asset.available);
  const expired = assets.filter(asset => !asset.available && asset.purgedAt);

  section.hidden = assets.length === 0;
  if (section.hidden) {
    grid.innerHTML = '';
    return;
  }

  $('inspection-files-retention').textContent = available.length
    ? 'Arquivos confirmados permanecem disponíveis junto à vistoria.'
    : 'Há arquivo histórico indisponível. Novos arquivos confirmados permanecem vinculados à vistoria.';
  $('download-all-files').hidden = available.length === 0;
  $('download-all-files').dataset.inspectionId = item.id;

  grid.innerHTML = assets.map(asset => {
    const title = asset.label || ASSET_LABELS[asset.type] || 'Arquivo';
    const image = asset.available && String(asset.contentType || '').startsWith('image/');
    const video = asset.available && String(asset.contentType || '').startsWith('video/');
    const preview = image
      ? `<div class="inspection-media-preview"><span class="inspection-media-loading">Carregando imagem...</span><img data-image-preview="${asset.id}" alt="${esc(title)}" hidden></div>`
      : video
        ? `<div class="inspection-media-preview"><div class="inspection-media-placeholder">▶ Vídeo disponível</div><video data-video-preview="${asset.id}" controls hidden></video></div>`
        : `<div class="inspection-media-preview"><div class="inspection-media-placeholder">${asset.type === 'REPORT' ? 'PDF' : 'DOCUMENTO'}</div></div>`;
    const canDelete = asset.available && !item.digitalAcceptedAt && ['PHOTO', 'VIDEO', 'SIGNATURE', 'VEHICLE_DOCUMENT', 'IDENTITY_DOCUMENT'].includes(asset.type);
    const legacyLargeVideo = video && Number(asset.fileSize || 0) > 15 * 1024 * 1024;
    const downloadName = legacyLargeVideo ? compactedVideoFileName(asset.fileName) : asset.fileName;
    const compressionNote = legacyLargeVideo
      ? '<small class="inspection-media-note">Vídeo original preservado sem limite de MB.</small>'
      : '';
    const canRegenerateReport = asset.type === 'REPORT' && Boolean(item.completedAt);
    const actions = canRegenerateReport
      ? `<div class="inspection-media-actions"><button class="secondary" data-analysis-download-report="${item.id}" type="button">Baixar relatório</button></div>`
      : asset.available
        ? `<div class="inspection-media-actions">${video ? `<button class="outline" data-play-video="${asset.id}" type="button">Reproduzir</button>` : ''}<button class="secondary" data-download-asset="${asset.id}" data-file-name="${esc(downloadName)}" type="button">${legacyLargeVideo ? 'Baixar WebM' : 'Baixar'}</button>${canDelete ? `<button class="danger" data-delete-asset="${asset.id}" data-file-name="${esc(title)}" type="button">Reprovar arquivo</button>` : ''}</div>`
        : `<div class="inspection-media-expired">${inspectionNeedsFiles(item) && asset.type !== 'REPORT' ? 'Arquivo excluído / aguardando reenvio.' : 'Arquivo histórico indisponível.'}</div>`;
    return `<article class="inspection-media-card ${asset.available ? '' : 'expired'}">${preview}<div class="inspection-media-body"><strong>${esc(title)}</strong><small>${esc(asset.fileName)}</small><small>${formatBytes(asset.fileSize)} · ${esc(asset.contentType || 'arquivo')}</small>${compressionNote}${actions}</div></article>`;
  }).join('');

  scheduleImagePreviews(item.id, available.filter(asset => String(asset.contentType || '').startsWith('image/')));
  grid.querySelectorAll('[data-download-asset]').forEach(button => {
    button.addEventListener('click', () => downloadAsset(item.id, button.dataset.downloadAsset, button.dataset.fileName, button));
  });
  grid.querySelectorAll('[data-analysis-download-report]').forEach(button => {
    button.addEventListener('click', () => downloadAnalysisReport(item.id, button));
  });
  grid.querySelectorAll('[data-play-video]').forEach(button => {
    button.addEventListener('click', () => playVideo(item.id, button.dataset.playVideo, button));
  });
  grid.querySelectorAll('[data-delete-asset]').forEach(button => {
    button.addEventListener('click', () => deleteInspectionAsset(
      item.id, button.dataset.deleteAsset, button.dataset.fileName || 'arquivo', button
    ));
  });
}

async function deleteInspectionAsset(inspectionId, assetId, label, button) {
  const confirmed = await confirmAnalysisAction(
    'Reprovar este arquivo?',
    `O arquivo “${label}” será reprovado e removido do envio atual. A vistoria voltará automaticamente para o mesmo analista na aba Pendências de vistoria.`,
    'Reprovar arquivo'
  );
  if (!confirmed) return;

  const dialog = $('inspection-dialog');
  button.disabled = true;
  const original = button.textContent;
  button.textContent = 'Excluindo...';
  try {
    await api(`/api/supervision/inspections/${encodeURIComponent(inspectionId)}/assets/${encodeURIComponent(assetId)}`, { method: 'DELETE' });
    releaseMediaUrls();
    if (dialog.open) dialog.close();
    await load();
    const updated = inspections.find(item => item.id === inspectionId);
    if (updated) openInspection(inspectionId);
    message('Arquivo reprovado. A vistoria voltou para o analista responsável na aba Pendências de vistoria.', 'success');
  } catch (error) {
    message(error.message);
    button.disabled = false;
    button.textContent = original;
  }
}

async function loadImagePreview(inspectionId, asset) {
  const image = document.querySelector(`[data-image-preview="${asset.id}"]`);
  if (!image) return;
  const loading = image.parentElement.querySelector('.inspection-media-loading');
  try {
    const blob = await previewBlob(inspectionId, asset.id);
    if (!image.isConnected) return;
    const url = URL.createObjectURL(blob);
    mediaObjectUrls.add(url);
    image.onload = () => { if (loading?.isConnected) loading.remove(); };
    image.onerror = () => showPreviewFallback(image, loading);
    image.src = url;
    image.hidden = false;
  } catch (error) {
    if (!error?.authExpired) showPreviewFallback(image, loading);
  }
}

async function playVideo(inspectionId, assetId, button) {
  const video = document.querySelector(`[data-video-preview="${assetId}"]`);
  if (!video) return;
  button.disabled = true;
  button.textContent = 'Carregando...';
  try {
    const blob = await apiBlob(`/api/supervision/inspections/${inspectionId}/assets/${assetId}`);
    const url = URL.createObjectURL(blob);
    mediaObjectUrls.add(url);
    video.src = url;
    video.hidden = false;
    video.previousElementSibling?.remove();
    await video.play().catch(() => {});
    button.textContent = 'Vídeo carregado';
  } catch (error) {
    button.disabled = false;
    button.textContent = 'Tentar novamente';
    message(error.message);
  }
}


function compactedVideoFileName(fileName) {
  const value = String(fileName || 'video-vistoria').trim();
  const dot = value.lastIndexOf('.');
  const base = dot > 0 ? value.slice(0, dot) : value;
  return `${base}-compactado.webm`;
}

async function downloadAsset(inspectionId, assetId, fileName, button) {
  const original = button.textContent;
  button.disabled = true;
  button.textContent = 'Baixando...';
  try {
    const blob = await apiBlob(`/api/supervision/inspections/${inspectionId}/assets/${assetId}?download=true`);
    triggerDownload(blob, fileName || 'arquivo-vistoria');
  } catch (error) {
    message(error.message);
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
}

async function downloadAnalysisReport(inspectionId, button) {
  const original = button.textContent;
  button.disabled = true;
  button.textContent = 'Gerando relatório...';
  try {
    const blob = await apiBlob(`/api/supervision/inspections/${inspectionId}/report`);
    triggerDownload(blob, `relatorio-vistoria-${inspectionId}.pdf`);
  } catch (error) {
    message(error.message);
    await confirmAnalysisAction('Erro ao baixar relatório', error.message || 'Não foi possível gerar o relatório desta vistoria.', 'Fechar');
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
}

function triggerDownload(blob, fileName) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1500);
}

async function downloadAllFiles() {
  const id = $('download-all-files').dataset.inspectionId;
  if (!id) return;
  const button = $('download-all-files');
  button.disabled = true;
  button.textContent = 'Preparando pacote...';
  try {
    const blob = await apiBlob(`/api/supervision/inspections/${id}/assets.zip`);
    triggerDownload(blob, `arquivos-vistoria-${id}.zip`);
  } catch (error) {
    message(error.message);
  } finally {
    button.disabled = false;
    button.textContent = 'Baixar todos (.zip)';
  }
}

function showNotificationButton(item) {
  const box = $('associate-notification');
  const button = $('notify-associate');
  if (item?.associateDecisionWhatsappUrl && item.associateDecisionMessagePending) {
    button.textContent = item.status === 'APPROVED'
      ? 'Enviar aprovação ao associado'
      : 'Enviar recusa ao associado';
    button.dataset.inspectionId = item.id;
    box.hidden = false;
  } else {
    button.removeAttribute('data-inspection-id');
    box.hidden = true;
  }

  const webauthnBox = $('webauthn-notification');
  const sendWebauthn = $('send-webauthn-token');
  const copyWebauthn = $('copy-webauthn-link');
  const downloadWebauthn = $('download-webauthn-dossier');
  const webauthnTitle = $('webauthn-status-title');
  const webauthnText = $('webauthn-status-text');
  const acceptedWebauthn = Boolean(item?.digitalAcceptedAt);
  const pendingWebauthn = item?.status === 'APPROVED' && Boolean(item?.registrationCompletedAt) && !item?.contractChangePending && !acceptedWebauthn && Boolean(item?.publicUrl);
  if (acceptedWebauthn) {
    webauthnBox.hidden = false;
    if (webauthnTitle) webauthnTitle.textContent = '✓ Aceite digital realizado';
    if (webauthnText) webauthnText.textContent = `Aceite digital realizado em ${date(item.digitalAcceptedAt)}. Baixe o dossiê final aceito pelo associado.`;
    sendWebauthn.hidden = true;
    copyWebauthn.hidden = true;
    sendWebauthn.removeAttribute('data-inspection-id');
    copyWebauthn.removeAttribute('data-inspection-id');
    if (downloadWebauthn) { downloadWebauthn.hidden = false; downloadWebauthn.dataset.inspectionId = item.id; }
  } else if (pendingWebauthn) {
    webauthnBox.hidden = false;
    if (webauthnTitle) webauthnTitle.textContent = 'Aceite digital WebAuthn pendente';
    if (webauthnText) webauthnText.textContent = 'Vistoria aprovada. A Supervisão também pode enviar ou copiar o link seguro de aceite do contrato; a assinatura só será confirmada após o WebAuthn do associado.';
    sendWebauthn.hidden = false;
    copyWebauthn.hidden = false;
    sendWebauthn.dataset.inspectionId = item.id;
    copyWebauthn.dataset.inspectionId = item.id;
    webauthnBox.hidden = false;
  } else {
    sendWebauthn.removeAttribute('data-inspection-id');
    copyWebauthn.removeAttribute('data-inspection-id');
    webauthnBox.hidden = true;
  }
}

$('supervision-registration-not-complete')?.addEventListener('click', async () => {
  const id = $('inspection-id').value;
  const item = inspections.find(value => value.id === id);
  if (!item) return message('Vistoria não encontrada.');
  if (item.digitalAcceptedAt) return message('O aceite digital do associado já foi concluído. Esta vistoria está bloqueada para edição.');
  if (!item.registrationCompletedAt) return message('O cadastro já está marcado como não realizado.', 'success');

  const note = $('supervision-registration-note').value.trim();
  const confirmed = await confirmAnalysisAction(
    'Marcar Cadastro não realizado e reabrir?',
    'A vistoria voltará para a etapa de cadastro para permitir correções. A Supervisão pode fazer isso mesmo com arquivos pendentes. Se existir uma cerimônia de aceite digital ainda não concluída, ela será invalidada e deverá ser enviada novamente após a nova liberação.',
    'Reabrir cadastro'
  );
  if (!confirmed) return;

  const button = $('supervision-registration-not-complete');
  const original = button.textContent;
  button.disabled = true;
  button.textContent = 'Reabrindo...';
  try {
    await api(`/api/supervision/inspections/${encodeURIComponent(id)}/registration-not-complete`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ note })
    });
    if ($('inspection-dialog').open) $('inspection-dialog').close();
    activeAnalysisQueue = 'analyst_pending';
    await load();
    const updated = inspections.find(value => value.id === id);
    if (updated) openInspection(id);
    message('Cadastro reaberto como não realizado. Depois da correção, use “Cadastro realizado · Liberar decisão”.', 'success');
  } catch (error) {
    message(error.message);
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
});

$('supervision-registration-complete')?.addEventListener('click', async () => {
  const id = $('inspection-id').value;
  const item = inspections.find(value => value.id === id);
  if (!item) return message('Vistoria não encontrada.');
  if (item.digitalAcceptedAt) return message('O aceite digital do associado já foi concluído. Esta vistoria está bloqueada para edição.');
  if (item.registrationCompletedAt) return message('O cadastro já está marcado como realizado.', 'success');

  const pending = inspectionPendingCount(item);
  const note = $('supervision-registration-note').value.trim();
  const warning = item.contractChangePending
    ? 'O cadastro será marcado como realizado. A decisão final continuará bloqueada somente até o associado confirmar a alteração comercial pendente.'
    : (pending > 0
      ? `Esta vistoria ainda possui ${pending} ${pending === 1 ? 'pendência' : 'pendências'}. Ao continuar, a Supervisão assume a responsabilidade pela etapa de cadastro e libera a vistoria para decisão final.`
      : 'A Supervisão assumirá a etapa de cadastro que ainda não foi concluída pelo analista e liberará a vistoria para decisão final.');
  const confirmed = await confirmAnalysisAction('Registrar Cadastro realizado?', warning, 'Cadastro realizado');
  if (!confirmed) return;

  const button = $('supervision-registration-complete');
  const original = button.textContent;
  button.disabled = true;
  button.textContent = 'Registrando...';
  try {
    await api(`/api/supervision/inspections/${encodeURIComponent(id)}/registration-complete`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ note })
    });
    if ($('inspection-dialog').open) $('inspection-dialog').close();
    activeAnalysisQueue = 'review';
    await load();
    const updated = inspections.find(value => value.id === id);
    if (updated) openInspection(id);
    message('Cadastro realizado pela Supervisão. A decisão final de aprovar ou rejeitar já está liberada.', 'success');
  } catch (error) {
    message(error.message);
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
});

$('save-supervision-note').addEventListener('click', async () => {
  const id = $('inspection-id').value;
  if (!id) return;
  const button = $('save-supervision-note');
  const original = button.textContent;
  button.disabled = true;
  button.textContent = 'Salvando...';
  try {
    const updated = await api(`/api/supervision/inspections/${encodeURIComponent(id)}/supervision-note`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ note: $('supervision-note').value.trim() })
    });
    const index = inspections.findIndex(item => item.id === updated.id);
    if (index >= 0) inspections[index] = updated;
    $('supervision-note').value = updated.supervisionNote || '';
    $('supervision-note-meta').textContent = updated.supervisionNoteUpdatedAt
      ? `Última atualização: ${date(updated.supervisionNoteUpdatedAt)}${updated.supervisionNoteByName ? ` por ${updated.supervisionNoteByName}` : ''}. A observação fica visível para o analista.`
      : 'A observação ficará visível para o analista ao abrir esta vistoria.';
    render();
    message(updated.supervisionNote ? 'O.B.S. da Supervisão salva e disponibilizada ao analista.' : 'O.B.S. da Supervisão removida.', 'success');
  } catch (error) {
    message(error.message);
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
});

$('save-editable-details').addEventListener('click', async () => {
  const id = $('inspection-id').value;
  if (!id) return;
  const button = $('save-editable-details');
  const original = button.textContent;
  button.disabled = true;
  button.textContent = 'Salvando...';
  try {
    const updated = await api(`/api/supervision/inspections/${encodeURIComponent(id)}/details`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        associateName: $('edit-associate-name').value.trim(),
        cpf: normalizedCpf($('edit-associate-cpf').value),
        whatsapp: $('edit-associate-whatsapp').value.trim(),
        plate: $('edit-associate-plate').value.trim(),
        zeroKm: $('edit-zero-km').value === 'true',
        model: $('edit-vehicle-model').value.trim(),
        modelYear: Number($('edit-model-year').value),
        residenceAddress: $('edit-residence-address').value.trim()
      })
    });
    const index = inspections.findIndex(item => item.id === updated.id);
    if (index >= 0) inspections[index] = updated;
    render();
    openInspection(updated.id);
    message('Dados do associado e veículo atualizados. A cotação vinculada foi sincronizada sem alterar a FIPE.', 'success');
  } catch (error) { message(error.message); }
  finally { button.disabled = false; button.textContent = original; }
});

$('supervision-save-contract-values')?.addEventListener('click', async () => {
  const id = $('inspection-id').value;
  if (!id) return;
  const manualMonthlyOverride = false;
  const preview = await requestCommercialPricingPreview(SUPERVISION_COMMERCIAL_CONFIG, true);
  if (!preview) return message('Não foi possível recalcular o valor pela tabela atual do plano.');
  const fipeValue = window.NHMoney?.parse($('supervision-contract-fipe').value);
  const monthlyValue = window.NHMoney?.parse($('supervision-contract-monthly').value);
  const discountPercent = Number($('supervision-contract-discount').value || 0);
  const rearWindowBranding = $('supervision-contract-branding').value || 'NOT_APPLICABLE';
  const benefitCodes = selectedCommercialBenefits('supervision-contract-benefits');
  if (!Number.isFinite(fipeValue) || fipeValue <= 0) return message('Informe um valor FIPE válido.');
  if (!Number.isFinite(monthlyValue) || monthlyValue <= 0) return message('Informe uma mensalidade válida.');
  const currentItem = inspections.find(item => item.id === id);
  const approvedBeforeDigitalAcceptance = currentItem?.status === 'APPROVED' && !currentItem?.digitalAcceptedAt;
  const confirmationSuffix = approvedBeforeDigitalAcceptance
    ? ' Como a vistoria está aprovada e ainda sem aceite digital, a revisão será aplicada agora e o dossiê final será regenerado para o novo aceite.'
    : ' Se houver alteração contratual que exija confirmação, o sistema preparará o fluxo correspondente para o associado.';
  if (!window.confirm(`Confirma FIPE ${brl.format(fipeValue)} e mensalidade final ${brl.format(monthlyValue)}?${confirmationSuffix}`)) return;
  const button = $('supervision-save-contract-values');
  const original = button.textContent;
  button.disabled = true;
  button.textContent = 'Salvando...';
  try {
    const updated = await api(`/api/supervision/inspections/${encodeURIComponent(id)}/contract-values`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fipeValue, monthlyValue, discountPercent, rearWindowBranding, benefitCodes, manualMonthlyOverride })
    });
    const index = inspections.findIndex(item => item.id === updated.id);
    if (index >= 0) inspections[index] = updated;
    render();
    openInspection(updated.id);
    message(updated.contractChangePending
      ? 'Novo valor preparado. Envie o link ao associado; o contrato só será atualizado após a confirmação dele.'
      : 'Revisão comercial salva no dossiê final.', 'success');
  } catch (error) { message(error.message); }
  finally { button.disabled = false; button.textContent = original; }
});


$('supervision-copy-contract-change-link')?.addEventListener('click', async () => {
  const url = $('supervision-copy-contract-change-link').dataset.url || '';
  if (!url) return message('Não há link de confirmação pendente.');
  try {
    await navigator.clipboard.writeText(url);
    message('Link de confirmação copiado.', 'success');
  } catch (_error) {
    window.prompt('Copie o link abaixo:', url);
  }
});


$('supervision-send-responsibility-term')?.addEventListener('click', async () => {
  const button = $('supervision-send-responsibility-term');
  const id = button.dataset.inspectionId;
  button.disabled = true;
  try {
    const info = await prepareSupervisionResponsibilityTerm(id);
    if (info.accepted) {
      message('Aceite concluído. Não é necessário reenviar este termo ao associado.', 'success');
    } else if (info.whatsappUrl) {
      window.open(info.whatsappUrl, '_blank', 'noopener,noreferrer');
      try { await api(`/api/supervision/inspections/${encodeURIComponent(id)}/responsibility-term/message-sent`, { method: 'POST' }); } catch (_) {}
      message('WhatsApp aberto com o link exclusivo do Termo de Responsabilidade.', 'success');
    } else if (info.publicUrl) {
      try { await navigator.clipboard.writeText(info.publicUrl); message('Link do termo copiado para envio manual.', 'success'); }
      catch (_) { window.prompt('Copie o link do Termo de Responsabilidade:', info.publicUrl); }
    }
    const item = inspections.find(value => value.id === id); if (item) syncSupervisionResponsibilityTermBox(item);
  } catch (error) { message(error.message); }
  finally { button.disabled = false; }
});

$('supervision-copy-responsibility-term')?.addEventListener('click', async () => {
  const id = $('supervision-copy-responsibility-term').dataset.inspectionId;
  try {
    const info = await prepareSupervisionResponsibilityTerm(id);
    if (info.accepted) return message('Aceite concluído. O termo não precisa ser reenviado.', 'success');
    if (!info.publicUrl) throw new Error('Não foi possível gerar o link do termo.');
    try { await navigator.clipboard.writeText(info.publicUrl); message('Link do Termo de Responsabilidade copiado.', 'success'); }
    catch (_) { window.prompt('Copie o link do Termo de Responsabilidade:', info.publicUrl); }
  } catch (error) { message(error.message); }
});

$('supervision-open-responsibility-term')?.addEventListener('click', async () => {
  const id = $('supervision-open-responsibility-term').dataset.inspectionId;
  try { const info = await prepareSupervisionResponsibilityTerm(id); if (info.publicUrl) window.open(info.publicUrl, '_blank', 'noopener,noreferrer'); }
  catch (error) { message(error.message); }
});

$('supervision-download-responsibility-term')?.addEventListener('click', async () => {
  const id = $('supervision-download-responsibility-term').dataset.inspectionId;
  try {
    const info = await prepareSupervisionResponsibilityTerm(id);
    if (!info.publicToken) throw new Error('Não foi possível preparar o PDF do termo.');
    const blob = await apiBlob(`/api/public/responsibility-terms/${encodeURIComponent(info.publicToken)}/term.pdf`);
    const url = URL.createObjectURL(blob), a = document.createElement('a');
    a.href = url; a.download = `termo-responsabilidade-${info.vehiclePlate || 'sem-placa'}.pdf`;
    document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 2000);
  } catch (error) { message(error.message); }
});

$('inspection-form').addEventListener('submit', async event => {
  event.preventDefault();
  const id = $('inspection-id').value;
  const item = inspections.find(value => value.id === id);
  const revisableFinalDecision = ['APPROVED', 'REJECTED'].includes(item?.status)
    && !item?.digitalAcceptedAt;
  if (!item || (item.analysisStage !== 'SUPERVISION_QUEUE' && !revisableFinalDecision)) {
    return message(item?.digitalAcceptedAt
      ? 'Esta vistoria já possui aceite digital do associado e não pode mais ser alterada.'
      : 'Esta vistoria ainda está com o analista. Use O.B.S. Supervisão para registrar orientações; a decisão final só fica disponível após Cadastro feito.');
  }
  const status = $('inspection-status').value;
  const note = $('inspection-note').value.trim();
  if (!['APPROVED', 'REJECTED'].includes(status)) return message('Selecione Aprovar ou Rejeitar vistoria.');
  if (!note) return message('Informe na observação por que esta vistoria está sendo aprovada ou rejeitada.');
  try {
    const updated = await api(`/api/supervision/inspections/${id}/status`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status, adminNote: note })
    });
    const index = inspections.findIndex(item => item.id === updated.id);
    if (index >= 0) inspections[index] = updated;
    render();
    message('Decisão da supervisão salva com sucesso.', 'success');
    if (updated.associateDecisionWhatsappUrl && updated.associateDecisionMessagePending) {
      $('inspection-dialog').close();
      openDecisionCommunication(updated);
    }
  } catch (error) { message(error.message); }
});

function openDecisionCommunication(item) {
  if (!item?.associateDecisionWhatsappUrl) return;
  activeDecisionCommunication = item;
  const approved = item.status === 'APPROVED';
  $('decision-whatsapp-icon').textContent = approved ? '✓' : '!';
  $('decision-whatsapp-icon').classList.toggle('rejected', !approved);
  $('decision-whatsapp-text').textContent = approved
    ? `A vistoria de ${item.associateName} foi aprovada. Comunique a aprovação ao associado.`
    : `A vistoria de ${item.associateName} foi rejeitada. Comunique a rejeição e as orientações ao associado.`;
  $('decision-whatsapp-details').innerHTML = `<div><span>Associado</span><strong>${esc(item.associateName)}</strong></div><div><span>Veículo</span><strong>${esc(item.plate || '0 km — sem placa')}</strong></div><div><span>Novo status</span><strong>${approved ? 'Aprovada' : 'Rejeitada'}</strong></div>`;
  $('message').className = '';
  $('message').textContent = '';
  clearAnalysisDialogMessage($('decision-whatsapp-dialog'));
  $('decision-whatsapp-dialog').showModal();
}

async function sendDecisionCommunication() {
  const item = activeDecisionCommunication;
  if (!item) return;
  window.open(item.associateDecisionWhatsappUrl, '_blank', 'noopener,noreferrer');
  const button = $('decision-whatsapp-send');
  button.disabled = true;
  button.textContent = 'Registrando...';
  try {
    const updated = await api(`/api/supervision/inspections/${encodeURIComponent(item.id)}/decision-message-sent`, { method: 'POST' });
    const index = inspections.findIndex(value => value.id === updated.id);
    if (index >= 0) inspections[index] = updated;
    $('decision-whatsapp-dialog').close();
    activeDecisionCommunication = null;
    render();
    message('Mensagem preparada no WhatsApp. Comunicação registrada.', 'success');
  } catch (error) {
    message(error.message);
  } finally {
    button.disabled = false;
    button.textContent = 'Enviar mensagem ao associado';
  }
}

function webauthnPendingInspection(buttonId) {
  const id = $(buttonId)?.dataset?.inspectionId;
  return inspections.find(value => value.id === id);
}

function webauthnAcceptanceUrl(item) {
  if (!item?.publicUrl) return '';
  try {
    const url = new URL(item.publicUrl, window.location.origin);
    url.pathname = '/retrato/';
    return url.toString();
  } catch (_) {
    return item.publicUrl;
  }
}

$('send-webauthn-token')?.addEventListener('click', async () => {
  const item = webauthnPendingInspection('send-webauthn-token');
  if (!item) return message('Vistoria não encontrada para envio do WebAuthn.');
  if (item.digitalAcceptedAt) return message('O associado já concluiu o aceite digital WebAuthn.', 'success');
  if (item.associateDecisionWhatsappUrl) {
    window.open(item.associateDecisionWhatsappUrl, '_blank', 'noopener,noreferrer');
    try {
      const updated = await api(`/api/supervision/inspections/${encodeURIComponent(item.id)}/decision-message-sent`, { method: 'POST' });
      const index = inspections.findIndex(value => value.id === updated.id);
      if (index >= 0) inspections[index] = updated;
      message('WhatsApp aberto com o link do aceite. Número de destino registrado para auditoria.', 'success');
    } catch (error) {
      message(`WhatsApp aberto, mas não foi possível registrar o destino: ${error.message}`);
    }
    return;
  }
  const link = webauthnAcceptanceUrl(item);
  if (!link) return message('Não foi possível montar o link do aceite WebAuthn.');
  navigator.clipboard?.writeText(link).then(
    () => message('O associado não possui WhatsApp válido cadastrado. Link WebAuthn copiado para você enviar manualmente.', 'success'),
    () => message(`Copie e envie este link ao associado: ${link}`)
  );
});

$('copy-webauthn-link')?.addEventListener('click', async () => {
  const item = webauthnPendingInspection('copy-webauthn-link');
  const link = webauthnAcceptanceUrl(item);
  if (!link) return message('Não foi possível montar o link do aceite WebAuthn.');
  try {
    await navigator.clipboard.writeText(link);
    message('Link/token WebAuthn copiado.', 'success');
  } catch (_) {
    window.prompt('Copie o link do aceite WebAuthn:', link);
  }
});

$('save-inspection-analyst')?.addEventListener('click', async () => {
  const id = $('inspection-id').value;
  const analystId = $('inspection-analyst').value;
  if (!id || !analystId) return message('Selecione um analista ativo antes de salvar.');
  const item = inspections.find(value => value.id === id);
  if (item?.digitalAcceptedAt) return message('O aceite digital já foi concluído. Não é possível redistribuir.');
  const analyst = analysts.find(a => String(a.id) === String(analystId));
  const confirmed = await confirmAnalysisAction(
    'Alterar analista responsável?',
    `A vistoria será redistribuída para ${analyst?.name || 'o analista selecionado'}. A ação será registrada na auditoria.`,
    'Confirmar redistribuição'
  );
  if (!confirmed) return;
  const button = $('save-inspection-analyst');
  button.disabled = true;
  try {
    const updated = await api(`/api/supervision/inspections/${id}/analyst`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({analystId})
    });
    const index = inspections.findIndex(value => value.id === updated.id);
    if (index >= 0) inspections[index] = updated;
    populateAnalystReviewer(updated);
    render();
    message('Analista alterado. A operação foi registrada na auditoria.', 'success');
  } catch (error) { message(error.message); populateAnalystReviewer(item); }
  finally { button.disabled = false; }
});

$('notify-associate').addEventListener('click', () => {
  const id = $('notify-associate').dataset.inspectionId;
  const item = inspections.find(value => value.id === id);
  if (item) openDecisionCommunication(item);
});
$('decision-whatsapp-send').addEventListener('click', () => sendDecisionCommunication());
$('decision-whatsapp-later').addEventListener('click', () => {
  $('decision-whatsapp-dialog').close();
  activeDecisionCommunication = null;
});

function openFirstPasswordDialog() {
  const dialog = $('first-password-dialog');
  $('first-current-password').value = '';
  $('first-new-password').value = '';
  $('first-confirm-password').value = '';
  $('first-password-message').className = '';
  $('first-password-message').textContent = '';
  if (!dialog.open) dialog.showModal();
}

$('first-password-dialog').addEventListener('cancel', event => event.preventDefault());

$('first-password-form').addEventListener('submit', async event => {
  event.preventDefault();
  const box = $('first-password-message');
  const currentPassword = $('first-current-password').value;
  const newPassword = $('first-new-password').value;
  if (newPassword !== $('first-confirm-password').value) {
    box.className = 'message error'; box.textContent = 'As novas senhas não são iguais.'; return;
  }
  try {
    currentUser = await api('/api/auth/change-password', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ currentPassword, newPassword })
    });
    $('first-password-dialog').close();
    message('Senha alterada com sucesso. Bem-vindo ao seu painel!', 'success');
    await load();
  } catch (error) {
    box.className = 'message error'; box.textContent = error.message;
  }
});

$('login-form').addEventListener('submit', async event => {
  event.preventDefault();
  const box = $('login-message');
  box.textContent = '';
  try {
    const data = await window.NH_FACE_AUTH.login($('username').value.trim(), $('password').value);
    localStorage.setItem(TOKEN_KEY, data.token);
    localStorage.setItem(ROLE_KEY, data.role);
    token = data.token;
    markSessionActivity(true);
    currentUser = data;
    if (data.role === 'ADMIN') {
      location.href = '/admin/';
    } else if (data.role === 'SUPERVISION_ANALYSIS') {
      showView();
      if (data.passwordChangeRequired) openFirstPasswordDialog();
      else await load();
    } else if (data.role === 'ANALYST') {
      location.href = '/analise/';
    } else if (data.role === 'TOW_DRIVER') {
      location.href = '/guincho/';
    } else if (data.role === 'WORKSHOP_MANAGER') {
      location.href = '/oficina/';
    } else if (data.role === 'EVENT_OPERATOR') {
      location.href = '/checklist/';
    } else if (data.role === 'BUYER') {
      location.href = '/financeiro/';
    } else {
      location.href = '/colaborador/';
    }
  } catch (error) {
    box.className = 'message error';
    box.textContent = error.message;
  }
});

$('download-all-files').addEventListener('click', downloadAllFiles);
document.querySelectorAll('[data-analysis-queue]').forEach(button => {
  button.addEventListener('click', () => {
    activeAnalysisQueue = button.dataset.analysisQueue || 'review';
    render();
  });
});
$('filter').addEventListener('input', render);
$('refresh').addEventListener('click', () => load().catch(error => message(error.message)));
$('close-dialog').addEventListener('click', () => { releaseMediaUrls(); $('inspection-dialog').close(); });
$('cancel-dialog').addEventListener('click', () => { releaseMediaUrls(); $('inspection-dialog').close(); });
$('inspection-dialog').addEventListener('close', () => { clearAnalysisDialogMessage($('inspection-dialog')); releaseMediaUrls(); });
$('logout').addEventListener('click', () => showLogin());

window.addEventListener('pageshow', () => {
  if (!token) return;
  if (sessionExpired()) showLogin('Sua sessão expirou. Entre novamente.');
  else if (inactivityExpired()) showLogin('Sessão encerrada após 20 minutos de inatividade. Entre novamente.');
  else scheduleInactivityCheck();
});

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'visible' || !token) return;
  if (sessionExpired()) showLogin('Sua sessão expirou. Entre novamente.');
  else if (inactivityExpired()) showLogin('Sessão encerrada após 20 minutos de inatividade. Entre novamente.');
  else scheduleInactivityCheck();
});

installInactivityTracking();
if (token && lastActivityAtMs() === null) markSessionActivity(true);
else scheduleInactivityCheck();
boot();

bindCommercialPricingPreview(SUPERVISION_COMMERCIAL_CONFIG);


// V83.125 — termo independente de ciência de possível atraso de peças.
// O WebAuthn e seu registro pertencem somente a este documento, não ao termo de avarias.
const PARTS_DELAY_SUPERVISION_DEFAULT = 'Peças de reposição compatíveis com o modelo do veículo, sujeitas à disponibilidade no mercado.';
async function syncSupervisionPartsDelayTerm(item) {
  const box = $('supervision-parts-delay-term-box');
  if (!box || !item?.id) return;
  box.hidden = false;
  box.dataset.inspectionId = item.id;
  const notes = $('supervision-parts-delay-term-notes');
  const status = $('supervision-parts-delay-term-status');
  const send = $('supervision-send-parts-delay-term');
  const copy = $('supervision-copy-parts-delay-term');
  notes.disabled = false;
  send.disabled = false;
  copy.disabled = false;
  notes.value = PARTS_DELAY_SUPERVISION_DEFAULT;
  status.textContent = 'Documento independente: ciência de possível atraso superior a 90 dias na obtenção de peças, sem prorrogação automática de prazos.';
  try {
    const info = await api(`/api/supervision/inspections/${encodeURIComponent(item.id)}/parts-delay-term`);
    if (!box.isConnected || box.dataset.inspectionId !== item.id || !info) return;
    notes.value = info.damageDetails || PARTS_DELAY_SUPERVISION_DEFAULT;
    if (info.accepted) {
      notes.disabled = true; send.disabled = true; copy.disabled = true;
      send.textContent = 'Termo assinado';
      status.textContent = `✓ Termo assinado digitalmente por WebAuthn em ${date(info.acceptedAt)}. PDF e prova do aceite disponíveis para download; reenvio bloqueado.`;
    } else {
      send.textContent = 'Enviar termo de ciência';
      status.textContent = info.sentAt
        ? `Termo enviado em ${date(info.sentAt)}${info.whatsappSentTo ? ' para ' + info.whatsappSentTo : ''}. Aguardando aceite digital do associado.`
        : 'Termo pronto para envio e consulta. Peças em falta podem levar prazo superior a 90 dias, sem prorrogação automática.';
    }
  } catch (error) {
    status.textContent = 'Não foi possível consultar o status do termo de ciência. ' + (error.message || '');
  }
}
async function prepareSupervisionPartsDelayTerm() {
  const id = $('supervision-parts-delay-term-box')?.dataset.inspectionId;
  if (!id) throw new Error('Abra uma vistoria para preparar o termo.');
  const note = $('supervision-parts-delay-term-notes').value.trim();
  const info = await api(`/api/supervision/inspections/${encodeURIComponent(id)}/parts-delay-term/prepare`, {
    method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({damageDetails:note || PARTS_DELAY_SUPERVISION_DEFAULT})
  });
  return {id,info};
}
$('supervision-send-parts-delay-term')?.addEventListener('click', async () => {
  const button = $('supervision-send-parts-delay-term'); button.disabled = true;
  try {
    const {id,info} = await prepareSupervisionPartsDelayTerm();
    if (info.accepted) return message('O termo de ciência já foi assinado; o reenvio está bloqueado.','success');
    if (info.whatsappUrl) {
      // Navega ao WhatsApp após preparar o link; em navegadores móveis não depende de pop-up.
      await api(`/api/supervision/inspections/${encodeURIComponent(id)}/parts-delay-term/message-sent`, {method:'POST'});
      window.location.href = info.whatsappUrl;
    } else if (info.publicUrl) {
      try { await navigator.clipboard.writeText(info.publicUrl); message('Link do termo copiado para envio manual.','success'); }
      catch (_) { window.prompt('Copie o link do termo:', info.publicUrl); }
    } else throw new Error('Link de ciência indisponível.');
    const item = inspections.find(row => row.id === id);
    if (item) syncSupervisionPartsDelayTerm(item);
  } catch (error) { message(error.message); }
  finally { button.disabled = false; }
});
$('supervision-copy-parts-delay-term')?.addEventListener('click', async () => {
  try {
    const {info} = await prepareSupervisionPartsDelayTerm();
    if (info.accepted) return message('Termo já assinado. O reenvio está bloqueado.', 'success');
    if (!info.publicUrl) throw new Error('Link indisponível.');
    try { await navigator.clipboard.writeText(info.publicUrl); message('Link do termo copiado.','success'); }
    catch (_) { window.prompt('Copie o link do termo:',info.publicUrl); }
  } catch (error) { message(error.message); }
});
$('supervision-open-parts-delay-term')?.addEventListener('click', async () => {
  const newWindow = window.open('about:blank', '_blank');
  try {
    const {info} = await prepareSupervisionPartsDelayTerm();
    if (!info.publicUrl) throw new Error('Link indisponível.');
    if (newWindow) newWindow.location.href = info.publicUrl;
    else window.location.href = info.publicUrl;
  } catch (error) { newWindow?.close(); message(error.message); }
});
$('supervision-download-parts-delay-term')?.addEventListener('click', async () => {
  try {
    const {info} = await prepareSupervisionPartsDelayTerm();
    if (!info.publicToken) throw new Error('Termo indisponível.');
    const blob = await apiBlob(`/api/public/parts-delay-terms/${encodeURIComponent(info.publicToken)}/term.pdf`);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a'); a.href=url;
    a.download = `termo-ciencia-pecas-${info.vehiclePlate||'sem-placa'}.pdf`;
    document.body.appendChild(a); a.click(); a.remove();
    window.setTimeout(()=>URL.revokeObjectURL(url),2000);
  } catch (error) { message(error.message); }
});

// Atualiza as abas de aceite sem que o analista precise recarregar a página.
setInterval(() => {
  if (!token || document.hidden || $('analysis-view')?.hidden || document.querySelector('dialog[open]')) return;
  load().catch(() => {});
}, 45000);
