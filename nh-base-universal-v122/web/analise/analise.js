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
let fipeVersionSearchSequence = 0;
let fipeVersionSearchTimer = null;
let fipeVersionChoices = [];
let selectedFipeRecordId = null;
let selectedFipeRecordValueCents = null;
let selectedFipeModel = null;
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

const ANALYSIS_COMMERCIAL_CONFIG = Object.freeze({
  discountId: 'edit-contract-discount',
  brandingId: 'edit-contract-branding',
  fipeId: 'edit-contract-fipe',
  benefitsId: 'edit-contract-benefits',
  monthlyId: 'edit-contract-monthly',
  inspectionIdId: 'inspection-id',
  previewApiBase: '/api/analysis/inspections',
  previewBaseId: 'analysis-preview-base',
  previewMandatoryId: 'analysis-preview-mandatory',
  previewMandatoryLabelId: 'analysis-preview-mandatory-label',
  previewOptionalsId: 'analysis-preview-optionals',
  previewSubtotalId: 'analysis-preview-subtotal',
  previewOneTimeId: 'analysis-preview-one-time',
  previewDiscountId: 'analysis-preview-discount',
  previewFinalId: 'analysis-preview-final',
  previewFinalNoteId: 'analysis-preview-final-note',
  previewDiscountLabelId: 'analysis-preview-discount-label'
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
  review: { label: 'Para cadastro', description: 'Vistorias dos consultores vinculados a você que aguardam conferência e a marcação “Cadastro feito”.' },
  pending: { label: 'Pendências de vistoria', description: 'Vistorias devolvidas pela supervisão ou com arquivos faltando/reenvio solicitado.' },
  acceptance: { label: 'Aguardando aceite', description: 'Após aprovação da Supervisão, o analista envia os três links. Prazo de 7 dias para cada link; prazo vencido exige nova aprovação da Supervisão.' },
  accepted: { label: 'Aceites realizados', description: 'Somente vistorias com os três aceites WebAuthn concluídos: dossiê, responsabilidade e ciência de peças.' }
};

function inspectionQueueKey(item) {
  if (item?.status === 'APPROVED') return item.allThreeAcceptancesCompleted ? 'accepted' : 'acceptance';
  if (item?.status === 'REJECTED') return 'acceptance';
  if (item?.analysisStage === 'ANALYST_PENDING' || inspectionNeedsFiles(item)) return 'pending';
  return 'review';
}

function analystRegistrationState(item) {
  // A etapa operacional é a fonte de verdade. Registros históricos podem possuir
  // registrationCompletedAt legado mesmo depois de terem voltado para a fila do Cadastro.
  if (item?.analysisStage === 'SUPERVISION_QUEUE' || item?.analysisStage === 'FINISHED') {
    return ['COMPLETED', 'Cadastro feito', 'ok'];
  }
  if (inspectionNeedsFiles(item) || item?.analysisStage === 'ANALYST_PENDING') {
    return ['AWAITING_DOCUMENTS', 'Aguardando documentos', 'warn'];
  }
  return ['NOT_COMPLETED', 'Cadastro não feito', 'warn'];
}

function registrationLocked(item) {
  return item?.analysisStage === 'SUPERVISION_QUEUE' || item?.analysisStage === 'FINISHED';
}

function analystBadge(item) {
  if (item?.status === 'APPROVED') {
    if (item.allThreeAcceptancesCompleted) return '<span class="badge ok">3/3 aceites realizados</span>';
    if (item.acceptanceReapprovalRequired) return `<span class="badge off">Prazo vencido — Supervisão</span>`;
    return `<span class="badge warn">${Number(item.acceptanceCompletedCount || 0)}/3 aceites · aguardando</span>`;
    if (item.decisionMessageSentAt) return '<span class="badge warn">Aguardando assinatura</span>';
    return '<span class="badge warn">Enviar link de aceite</span>';
  }
  if (item?.status === 'REJECTED') return '<span class="badge warn">Comunicar recusa</span>';
  const [, label, kind] = analystRegistrationState(item);
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
  if ($('analysis-welcome-text')) $('analysis-welcome-text').textContent = 'Bem-vindo, seu painel de Analista está pronto e atualizado.';
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
      const blob = await apiBlob(`/api/analysis/inspections/${inspectionId}/assets/${assetId}`);
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
    if (me.role === 'SUPERVISION_ANALYSIS') { location.replace('/supervisao/'); return; }
    if (me.role === 'TOW_DRIVER') { location.replace('/guincho/'); return; }
    if (me.role === 'WORKSHOP_MANAGER') { location.replace('/oficina/'); return; }
    if (me.role === 'EVENT_OPERATOR') { location.replace('/checklist/'); return; }
    if (me.role === 'BUYER') { location.replace('/financeiro/'); return; }
    if (me.role !== 'ANALYST') { location.replace('/colaborador/'); return; }
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
      api('/api/analysis/inspections'),
      api('/api/analysis/inspections/analysts')
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
  return `${item.associateName} ${item.plate || ''} ${item.consultantName} ${item.reviewedByName || ''} ${item.status}`
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
    const pendingActions = needsFiles
      ? `${actionLink(item.associateInspectionWhatsappUrl, partialResubmission ? 'Enviar ao associado' : 'Enviar link ao associado', 'secondary')}${actionLink(item.consultantInspectionWhatsappUrl, 'Enviar ao consultor', 'outline')}${actionLink(currentPublicUrl, partialResubmission ? 'Abrir pendências' : 'Abrir vistoria')}`
      : '';

    return `<tr>
      <td><strong>${esc(item.associateName)}</strong><small class="table-code">${esc(formatPhone(item.whatsapp) || 'Sem WhatsApp')}</small></td>
      <td>${esc(item.plate || '0 km — sem placa')}</td>
      <td>${esc(item.consultantName)}</td>
      <td>${esc(item.assignedAnalystName || currentUser?.consultantName || '—')}</td>
      <td>${date(item.completedAt || item.createdAt)}</td>
      <td><div class="status-with-action">${analystBadge(item)}${item.status === 'APPROVED' ? `<small class="table-code" title="Dossiê / Responsabilidade / Ciência de Peças">${item.dossierAcceptanceCompleted ? '✓' : '○'} Dossiê · ${item.responsibilityAcceptanceCompleted ? '✓' : '○'} Responsabilidade · ${item.partsDelayAcceptanceCompleted ? '✓' : '○'} Peças</small>` : ''}${statusActions}</div></td>
      <td><div class="row-actions">${pendingActions}<button class="secondary small-button" data-analyze="${item.id}" type="button">Analisar</button></div></td>
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

  const counts = { review: 0, pending: 0, acceptance: 0, accepted: 0 };
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

function syncNewAnalystInput() {
  const select = $('inspection-analyst');
  const wrap = $('inspection-new-analyst-wrap');
  const input = $('inspection-new-analyst-name');
  const isNew = select.value === '__NEW__';
  wrap.hidden = !isNew;
  input.required = isNew;
  if (!isNew) input.value = '';
}

function populateAnalystReviewer(item) {
  const select = $('inspection-analyst');
  const helper = $('inspection-analyst-helper');
  const linkedId = currentUser?.role === 'ANALYST' ? currentUser?.consultantId : null;
  const linkedName = currentUser?.role === 'ANALYST' ? currentUser?.consultantName : null;

  if (linkedId) {
    select.innerHTML = `<option value="${esc(linkedId)}">${esc(linkedName || 'Analista vinculado')}</option>`;
    select.value = linkedId;
    select.disabled = true;
    helper.textContent = `Login identificado como ${linkedName || 'analista vinculado'}. Este nome ficará registrado na análise.`;
    $('inspection-new-analyst-wrap').hidden = true;
    $('inspection-new-analyst-name').required = false;
    return;
  }

  select.disabled = false;
  const options = ['<option value="">Selecione o analista responsável</option>'];
  analysts.filter(item => item.active && item.role === 'ANALYST').forEach(analyst => {
    options.push(`<option value="${analyst.id}">${esc(analyst.name)}</option>`);
  });
  options.push('<option value="__NEW__">+ Cadastrar novo analista</option>');
  select.innerHTML = options.join('');

  const previousId = item?.reviewedByRole === 'ANALYST' ? item.reviewedByCollaboratorId : null;
  if (previousId && analysts.some(analyst => analyst.id === previousId && analyst.active)) {
    select.value = previousId;
  } else if (!analysts.some(analyst => analyst.active && analyst.role === 'ANALYST')) {
    select.value = '__NEW__';
  } else {
    select.value = '';
  }
  helper.textContent = 'Selecione quem está realizando a análise. Se o nome não existir, cadastre-o aqui e ele será incluído automaticamente em Colaboradores como Analista.';
  syncNewAnalystInput();
}

function configureStatusOptions(item) {
  const select = $('inspection-status');
  const [value] = analystRegistrationState(item);
  select.value = value;
  select.disabled = true;
}


async function syncAnalysisResponsibilityTermBox(item) {
  const box = $('analysis-responsibility-term-box');
  const send = $('analysis-send-responsibility-term');
  const copy = $('analysis-copy-responsibility-term');
  const open = $('analysis-open-responsibility-term');
  const download = $('analysis-download-responsibility-term');
  const status = $('analysis-responsibility-term-status');
  const damages = $('analysis-responsibility-term-damages');
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
    const info = await api(`/api/analysis/inspections/${encodeURIComponent(item.id)}/responsibility-term`);
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

async function prepareAnalysisResponsibilityTerm(id) {
  if (!id) throw new Error('Vistoria não encontrada para o Termo de Responsabilidade.');
  const damageDetails = String($('analysis-responsibility-term-damages')?.value || '').trim();
  return api(`/api/analysis/inspections/${encodeURIComponent(id)}/responsibility-term/prepare`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ damageDetails: damageDetails || null })
  });
}

// CEP exclusivo do cadastro pelo analista. Sem dependência do formulário de cotação.
const ANALYSIS_ADDRESS_IDS = {
  street:'edit-residence-street', number:'edit-residence-number', complement:'edit-residence-complement',
  neighborhood:'edit-residence-neighborhood', city:'edit-residence-city', state:'edit-residence-state'
};
let analysisCepSequence = 0;
let analysisCepController = null;
let analysisCepTimer = null;
let analysisLastCep = '';
let analysisAutoFilled = {};
const cepDigits = text => String(text || '').replace(/\D/g, '').slice(0,8);
const cepMask = value => { const d=cepDigits(value); return d.length>5 ? `${d.slice(0,5)}-${d.slice(5)}` : d; };
function analysisCepStatus(text, status='info') {
  const el = $('analysis-cep-status'); if(el) { el.textContent = text; el.dataset.kind = status; }
}
function cancelAnalysisCep() {
  ++analysisCepSequence;
  clearTimeout(analysisCepTimer);
  analysisCepController?.abort(); analysisCepController=null;
  const button=$('analysis-find-cep'); if(button) { button.disabled=false; button.textContent='Buscar CEP'; }
}
function clearAnalysisAutofill() {
  for(const [key,value] of Object.entries(analysisAutoFilled)) {
    const el=$(ANALYSIS_ADDRESS_IDS[key]); if(el && el.value===value) el.value='';
  }
  analysisAutoFilled={};
}
async function fetchAnalysisCep(cep,signal) {
  for(const url of [`https://opencep.com/v1/${cep}`,`https://viacep.com.br/ws/${cep}/json/`]) {
    try {
      const response=await fetch(url,{signal,headers:{Accept:'application/json'},cache:'no-store'});
      if(!response.ok) continue;
      const data=await response.json();
      if(data && data.erro!==true && data.erro!=='true' && data.localidade && data.uf) return data;
    } catch(err) { if(signal.aborted) throw err; }
  }
  return null;
}
async function lookupAnalysisCep(force=false) {
  const input=$('edit-residence-cep'), cep=cepDigits(input.value);
  if(cep.length!==8) { analysisCepStatus('O CEP deve conter 8 números.','error'); return false; }
  if(!force && analysisLastCep===cep) return true;
  cancelAnalysisCep();
  const sequence=analysisCepSequence, controller=new AbortController();
  analysisCepController=controller;
  const button=$('analysis-find-cep'); button.disabled=true; button.textContent='Buscando...';
  analysisCepStatus('Consultando endereço...','loading');
  const timeout=setTimeout(()=>controller.abort(),7000);
  try {
    const data=await fetchAnalysisCep(cep,controller.signal);
    if(sequence!==analysisCepSequence || cepDigits(input.value)!==cep) return false;
    if(!data) { analysisCepStatus('CEP não encontrado ou API indisponível. Preencha manualmente.','error'); return false; }
    clearAnalysisAutofill();
    for(const [key,prop] of Object.entries({street:'logradouro',neighborhood:'bairro',city:'localidade',state:'uf'})) {
      const el=$(ANALYSIS_ADDRESS_IDS[key]); const value=String(data[prop]||'').trim();
      if(!el || !value || (key==='state' && ![...el.options].some(option=>option.value===value.toUpperCase()))) continue;
      el.value=key==='state'?value.toUpperCase():value; analysisAutoFilled[key]=el.value;
    }
    analysisLastCep=cep;
    // CEP novo: endereço legado só deve ser usado se o analista ainda não confirmou um endereço estruturado.
    $('analysis-legacy-address-wrap').hidden=true;
    analysisCepStatus('Endereço localizado. Confira os dados e informe o número.','success');
    return true;
  } catch(err) {
    if(sequence===analysisCepSequence) analysisCepStatus('Falha na consulta. Preencha os dados manualmente.','error');
    return false;
  } finally {
    clearTimeout(timeout);
    if(sequence===analysisCepSequence) { analysisCepController=null; button.disabled=false; button.textContent='Buscar CEP'; }
  }
}
function populateAnalysisAddress(item) {
  cancelAnalysisCep(); analysisLastCep=''; analysisAutoFilled={};
  for(const id of Object.values(ANALYSIS_ADDRESS_IDS)) $(id).value='';
  const address=String(item.residenceAddress||'').trim();
  const found=address.match(/(?:^|,\s*)CEP\s+(\d{5})-?(\d{3})\s*$/i);
  $('edit-residence-cep').value=found?`${found[1]}-${found[2]}`:'';
  $('edit-residence-address').value=address;
  // Endereço salvo pela versão atual: reabrir e editar sem perder os campos.
  const clean=found?address.slice(0,found.index).replace(/,\s*$/,''):address;
  const parts=clean.split(/,\s*/).filter(Boolean);
  const numIndex=parts.findIndex(part=>/^n[ºo°.]?\s+/i.test(part));
  const townIndex=parts.findIndex(part=>/^[^,]+\/[A-Z]{2}$/.test(part));
  const structured=found && numIndex===1 && townIndex>=3;
  if(structured) {
    $('edit-residence-street').value=parts[0];
    $('edit-residence-number').value=parts[1].replace(/^n[ºo°.]?\s+/i,'');
    const middle=parts.slice(2,townIndex);
    $('edit-residence-neighborhood').value=middle.pop()||'';
    $('edit-residence-complement').value=middle.join(', ');
    const city=parts[townIndex].match(/^(.*)\/([A-Z]{2})$/);
    $('edit-residence-city').value=city?.[1]||'';
    $('edit-residence-state').value=city?.[2]||'';
  }
  $('analysis-legacy-address-wrap').hidden=!address || structured;
  analysisCepStatus(found?'Endereço cadastrado. Confira ou atualize os dados.':'Informe o CEP ou preencha o endereço manualmente.');
}
function analysisAddressPayload() {
  const existing=$('edit-residence-address').value.trim();
  const cep=cepDigits($('edit-residence-cep').value);
  const data=Object.fromEntries(Object.entries(ANALYSIS_ADDRESS_IDS).map(([key,id])=>[key,$(id).value.trim()]));
  const hasStructured=Object.values(data).some(Boolean) || cep.length>0;
  if(!hasStructured) return existing;
  if(existing && cep.length===8 && !Object.values(data).some(Boolean) && cepDigits(existing.match(/CEP\s+(\d{5})-?(\d{3})/i)?.[0])===cep) return existing;
  if(cep.length!==8) throw new Error('Informe o CEP com 8 números.');
  if(!data.street || !data.number || !data.neighborhood || !data.city || !data.state)
    throw new Error('Para salvar o endereço, informe rua, número, bairro, cidade e UF.');
  const address=[data.street,`nº ${data.number}`,data.complement||null,data.neighborhood,`${data.city}/${data.state}`,`CEP ${cepMask(cep)}`].filter(Boolean).join(', ');
  if(address.length>600) throw new Error('O endereço ultrapassou 600 caracteres.');
  return address;
}
function setupAnalysisCep() {
  const input=$('edit-residence-cep');
  input.addEventListener('input',()=>{
    input.value=cepMask(input.value); cancelAnalysisCep();
    if(cepDigits(input.value)!==analysisLastCep) { analysisLastCep=''; clearAnalysisAutofill(); }
    if(cepDigits(input.value).length===8) analysisCepTimer=setTimeout(()=>lookupAnalysisCep(),350);
    else analysisCepStatus('Informe os 8 números do CEP.');
  });
  input.addEventListener('blur',()=>{
    if(cepDigits(input.value).length===8 && cepDigits(input.value)!==analysisLastCep) lookupAnalysisCep();
  });
  $('analysis-find-cep').addEventListener('click',()=>lookupAnalysisCep(true));
  Object.entries(ANALYSIS_ADDRESS_IDS).forEach(([key,id])=>{
    $(id).addEventListener('input',()=>{ if(analysisAutoFilled[key]!==$(id).value) delete analysisAutoFilled[key]; });
  });
}
let analysisPlansRequest=0;
async function loadAnalysisPlans(item, locked) {
  const select=$('edit-contract-plan-select');
  const button=$('save-contract-plan');
  const sequence=++analysisPlansRequest;
  select.replaceChildren(new Option('Carregando planos disponíveis...',''));
  const eligible=item.requestType==='NEW_INSPECTION' && item.fipeValue!=null && !locked && !item.digitalAcceptedAt;
  select.disabled=button.disabled=!eligible;
  if(!eligible) { select.replaceChildren(new Option('Alteração indisponível','')); return; }
  try {
    const list=await api(`/api/analysis/inspections/${encodeURIComponent(item.id)}/plans`);
    if(sequence!==analysisPlansRequest || $('inspection-id').value!==item.id) return;
    select.replaceChildren(...list.map(plan=>new Option(plan.name,plan.code)));
    if(!list.length) { select.add(new Option('Nenhum plano disponível','')); select.disabled=button.disabled=true; return; }
    select.value=item.selectedPlanCode||list.find(plan=>plan.selected)?.code||list[0].code;
    button.disabled=select.value===(item.selectedPlanCode||'');
    select.onchange=()=>button.disabled=select.value===(item.selectedPlanCode||'');
  } catch(error) {
    if(sequence!==analysisPlansRequest) return;
    select.replaceChildren(new Option('Falha ao carregar planos',''));
    select.disabled=button.disabled=true;
    analysisCepStatus(`Não foi possível carregar os planos: ${error.message}`,'error');
  }
}

// Consulta local ao dataset validado.
let analysisFipePicker=null;
function analysisVehicleModelForSave() {
  return analysisFipePicker?.persistedModel() || $('edit-vehicle-model').value.trim();
}
function initializeAnalysisFipePicker(item,locked) {
  if(!window.NHFipePicker) return;
  if(!analysisFipePicker) analysisFipePicker=window.NHFipePicker.create({
    get:$,api,typeId:'edit-fipe-type',brandId:'edit-fipe-brand',
    brandListId:'edit-fipe-brand-list',modelId:'edit-vehicle-model',modelListId:'edit-fipe-model-list',
    onFilterChange:()=>scheduleAnalysisFipeVersions(280),onStatus:(text)=>fipeVersionHint(text)
  });
  analysisFipePicker?.initialize(item,locked);
}

// Consulta local ao dataset validado. Uma versão nunca é inferida silenciosamente se houver
// múltiplas possibilidades; preço/código permanecem juntos até a confirmação contratual.
function fipeVersionHint(text, kind = 'neutral') {
  const status = $('analysis-fipe-version-status');
  if (!status) return;
  status.textContent = text;
  status.style.color = kind === 'error' ? '#a22128' : kind === 'success' ? '#176043' : '';
}
function resetFipeVersionSelection(clearCode = true) {
  selectedFipeRecordId = null;
  selectedFipeRecordValueCents = null;
  selectedFipeModel = null;
  if (clearCode) {
    $('edit-fipe-code').value = '';
    $('edit-fipe-display').value = '';
  }
}
function applySelectedFipeVersion(row) {
  if (!row) return;
  const amount = Number(row.valueCents) / 100;
  if (!Number.isFinite(amount) || amount <= 0) return fipeVersionHint('Registro FIPE inválido. Confira manualmente.', 'error');
  selectedFipeRecordId = Number(row.id);
  selectedFipeRecordValueCents = Number(row.valueCents);
  selectedFipeModel = String(row.model || '');
  $('edit-fipe-code').value = row.codeFipe || '';
  $('edit-fipe-display').value = brl.format(amount);
  $('edit-contract-fipe').value = window.NHMoney?.format(amount) || amount.toFixed(2).replace('.', ',');
  window.NHMoney?.refresh($('edit-contract-fipe'));
  fipeVersionHint(`FIPE ${String(row.referenceMonth).padStart(2,'0')}/${row.referenceYear} · ${brl.format(amount)} · código ${row.codeFipe}. Salve os dados do veículo e depois os valores do contrato para registrar a alteração.`, 'success');
  refreshCommercialRules(ANALYSIS_COMMERCIAL_CONFIG, $('edit-contract-fipe').disabled);
  markCommercialMonthlyAutomatic(ANALYSIS_COMMERCIAL_CONFIG);
  scheduleCommercialPricingPreview(ANALYSIS_COMMERCIAL_CONFIG, true, 0);
}
async function lookupAnalysisFipeVersions() {
  const request = ++fipeVersionSearchSequence;
  const model = analysisFipePicker ? analysisFipePicker.queryModel() : $('edit-vehicle-model').value.trim();
  const year = Number($('edit-model-year').value);
  const id = $('inspection-id').value;
  const select = $('edit-fipe-version');
  resetFipeVersionSelection(false);
  fipeVersionChoices = [];
  if (!id || model.length < 2 || !Number.isInteger(year) || year < 1950 || year > 2100 || (analysisFipePicker && !analysisFipePicker.brand())) {
    select.replaceChildren(new Option('Informe modelo e ano-modelo',''));
    fipeVersionHint('Informe tipo, marca, modelo e ano-modelo para pesquisar as versões FIPE.');
    return;
  }
  const inspection = inspections.find(i => i.id === id);
  if (!inspection || inspection.requestType !== 'NEW_INSPECTION' || registrationLocked(inspection) || !!inspection.digitalAcceptedAt) return;
  select.replaceChildren(new Option('Consultando versões FIPE...',''));
  select.disabled = true;
  fipeVersionHint('Buscando versões para o ano-modelo informado...');
  try {
    const params = new URLSearchParams({model,year:String(year),zeroKm:$('edit-zero-km').value,category:inspection.vehicleType || ''});
    if (analysisFipePicker) {
      params.set('brand',analysisFipePicker.brand());
      params.set('type',analysisFipePicker.type());
    }
    const rows = await api(`/api/analysis/fipe/versions?${params.toString()}`);
    if (request !== fipeVersionSearchSequence || $('inspection-id').value !== id) return;
    fipeVersionChoices = Array.isArray(rows) ? rows : [];
    select.disabled = false;
    if (!fipeVersionChoices.length) {
      let noTable=false;
      try { noTable=(await api('/api/analysis/fipe/status'))?.available===false; } catch (_) {}
      if (request !== fipeVersionSearchSequence || $('inspection-id').value !== id) return;
      select.replaceChildren(new Option(noTable?'API FIPE indisponível':'Nenhuma versão encontrada — preenchimento manual',''));
      fipeVersionHint(noTable?'A API FIPE está indisponível. Verifique a conexão com o serviço independente.':'Nenhuma correspondência na FIPE. Confira marca, modelo, tipo, ano e condição de 0 km; é possível ajustar o valor manualmente sem atribuir um código inexistente.',noTable?'error':'');
      return;
    }
    select.replaceChildren(new Option('Selecione a versão exata',''));
    fipeVersionChoices.forEach((row) => {
      const amount=Number(row.valueCents)/100;
      const label=`${row.brand} · ${row.model} · ${row.fuel || 'Combustível não informado'} · ${brl.format(amount)} · ${row.codeFipe}`;
      select.add(new Option(label,String(row.id)));
    });
    if (fipeVersionChoices.length === 1) {
      select.value=String(fipeVersionChoices[0].id);
      applySelectedFipeVersion(fipeVersionChoices[0]);
    } else {
      fipeVersionHint(`${fipeVersionChoices.length}${fipeVersionChoices.length === 70 ? '+' : ''} versões encontradas. Selecione a versão correta para preencher o valor e o código FIPE.`);
    }
  } catch(error) {
    if (request !== fipeVersionSearchSequence) return;
    select.replaceChildren(new Option('FIPE indisponível — consulte novamente',''));
    select.disabled = false;
    fipeVersionHint(`Consulta FIPE indisponível: ${error.message}. Os dados continuam editáveis manualmente.`, 'error');
  }
}
function scheduleAnalysisFipeVersions(delay = 350) {
  clearTimeout(fipeVersionSearchTimer);
  ++fipeVersionSearchSequence;
  resetFipeVersionSelection();
  $('edit-fipe-version').replaceChildren(new Option('Aguardando pesquisa...',''));
  fipeVersionSearchTimer = setTimeout(lookupAnalysisFipeVersions,delay);
}
['edit-vehicle-model','edit-model-year','edit-zero-km'].forEach(id => {
  $(id).addEventListener(id==='edit-zero-km' ? 'change' : 'input',()=>scheduleAnalysisFipeVersions());
});
$('edit-fipe-version').addEventListener('change',()=> {
  const record=fipeVersionChoices.find(row => String(row.id)===$('edit-fipe-version').value);
  resetFipeVersionSelection();
  if (record) applySelectedFipeVersion(record);
  else fipeVersionHint('Selecione a versão exata para preencher o valor FIPE e seu código.');
});
$('edit-contract-fipe').addEventListener('input',()=> {
  if (selectedFipeRecordId !== null) {
    const amount = window.NHMoney?.parse($('edit-contract-fipe').value);
    if (Number.isFinite(amount) && Math.round(amount*100) !== selectedFipeRecordValueCents) {
      resetFipeVersionSelection();
      $('edit-fipe-version').value='';
      fipeVersionHint('Valor FIPE editado manualmente. Selecione novamente uma versão para vincular seu código.');
    }
  }
});

function openInspection(id) {
  const item = inspections.find(value => value.id === id);
  if (!item) return;
  if (item.expiredWithoutFiles) {
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
  clearTimeout(fipeVersionSearchTimer);
  fipeVersionSearchSequence++;
  resetFipeVersionSelection(false);
  $('edit-fipe-code').value = item.vehicleFipeCode || '';
  $('edit-fipe-display').value = '';
  $('edit-fipe-version').replaceChildren(new Option('Aguardando consulta FIPE...',''));
  $('edit-fipe-version').disabled = true;
  fipeVersionHint(item.vehicleFipeCode
    ? `Código FIPE salvo: ${item.vehicleFipeCode}${item.vehicleFipeReference ? ' · referência '+item.vehicleFipeReference : ''}. Consulte as versões para atualizar.`
    : 'Preencha modelo e ano para localizar versões FIPE; o preço será atualizado somente após salvar a revisão comercial.');
  $('edit-residence-address').value = item.residenceAddress || '';
  $('edit-contract-fipe').value = window.NHMoney?.format(item.pendingFipeValue ?? item.fipeValue) || (item.pendingFipeValue ?? item.fipeValue ?? '');
  $('edit-contract-monthly').value = window.NHMoney?.format(item.pendingMonthlyValue ?? item.monthlyValue) || (item.pendingMonthlyValue ?? item.monthlyValue ?? '');
  $('edit-contract-plan').textContent = item.selectedPlanName || item.contractedPlan || '—';
  renderCommercialDiscountOptions('edit-contract-discount', item, item.pendingDiscountPercent ?? item.discountPercent ?? 0);
  $('edit-contract-branding').value = item.pendingRearWindowBranding || item.rearWindowBranding || 'NOT_APPLICABLE';
  window.NHMoney?.refresh($('edit-contract-fipe'));
  window.NHMoney?.refresh($('edit-contract-monthly'));
  const editableLocked = registrationLocked(item);
  renderCommercialBenefits('edit-contract-benefits', item, editableLocked);
  refreshCommercialRules(ANALYSIS_COMMERCIAL_CONFIG, editableLocked);
  initializeCommercialPricingPreview(ANALYSIS_COMMERCIAL_CONFIG, item);
  ['edit-associate-name','edit-associate-cpf','edit-associate-whatsapp','edit-associate-plate','edit-zero-km','edit-fipe-type','edit-fipe-brand','edit-vehicle-model','edit-model-year','edit-fipe-version','edit-residence-address','edit-residence-cep',...Object.values(ANALYSIS_ADDRESS_IDS),'analysis-find-cep','save-editable-details','edit-contract-fipe','edit-contract-monthly','edit-contract-discount','edit-contract-branding','save-contract-values']
    .forEach(id => { const el = $(id); if (el) el.disabled = editableLocked; });

  initializeAnalysisFipePicker(item,editableLocked || item.requestType!=='NEW_INSPECTION');
  if (!window.NHFipePicker && !editableLocked && item.requestType === 'NEW_INSPECTION' && item.fipeValue != null)
    fipeVersionSearchTimer=setTimeout(lookupAnalysisFipeVersions,250);

  const hasContractValues = item.requestType === 'NEW_INSPECTION' && item.fipeValue != null && item.monthlyValue != null;
  const contractSection = $('contract-values-section');
  if (contractSection) contractSection.hidden = item.requestType !== 'NEW_INSPECTION';
  const contractHelper = $('contract-values-helper');
  if (contractHelper) {
    contractHelper.textContent = editableLocked
      ? 'FIPE e mensalidade ficam bloqueadas depois de Cadastro feito. Para registros pendentes ou Cadastro não feito, inclusive históricos, a edição permanece disponível.'
      : (hasContractValues
          ? 'FIPE e mensalidade podem ser corrigidas enquanto o cadastro estiver pendente ou marcado como não feito, inclusive em vistorias antigas. Os valores salvos passam a valer no PPV/PDF contratual e ficam registrados no histórico.'
          : 'Esta vistoria não possui valores de cotação vinculados para edição.');
  }
  if (!hasContractValues) {
    ['edit-contract-fipe','edit-contract-monthly','edit-contract-discount','edit-contract-branding','save-contract-values'].forEach(id => { const el = $(id); if (el) el.disabled = true; });
  }

  const pendingContractBox = $('contract-change-pending');
  if (pendingContractBox) {
    pendingContractBox.hidden = !item.contractChangePending;
    if (item.contractChangePending) {
      $('contract-change-pending-text').textContent = `FIPE proposta: ${brl.format(item.pendingFipeValue)} · mensalidade com os adicionais atuais: ${brl.format(item.pendingMonthlyValue)}. O contrato só será atualizado depois da confirmação do associado.`;
      const sendLink = $('send-contract-change-link');
      sendLink.href = item.contractChangeWhatsappUrl || item.contractChangeConfirmationUrl || '#';
      sendLink.hidden = !(item.contractChangeWhatsappUrl || item.contractChangeConfirmationUrl);
      $('copy-contract-change-link').dataset.url = item.contractChangeConfirmationUrl || '';
    }
  }

  // Não deixe ações de Cadastro sumirem em registros históricos que ainda estão
  // na fila do analista. O backend continua validando a transição.
  const registrationNotComplete = $('registration-not-complete');
  const registrationComplete = $('registration-complete');
  if (registrationNotComplete) {
    registrationNotComplete.hidden = false;
    registrationNotComplete.disabled = editableLocked;
  }
  if (registrationComplete) {
    registrationComplete.hidden = false;
    registrationComplete.disabled = editableLocked || item.contractChangePending;
  }
  $('inspection-note').value = item.adminNote || '';
  $('supervision-note').value = item.supervisionNote || '';
  $('supervision-note-meta').textContent = item.supervisionNoteUpdatedAt
    ? `Última atualização: ${date(item.supervisionNoteUpdatedAt)}${item.supervisionNoteByName ? ` por ${item.supervisionNoteByName}` : ''}.`
    : 'As orientações registradas pela supervisão aparecem aqui.';
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
    ['Analista responsável', item.assignedAnalystName || currentUser?.consultantName || '—'],
    ['Situação do cadastro', analystRegistrationState(item)[1]],
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
    ['GPS capturado em', item.digitalAcceptedAt ? date(item.digitalAcceptanceLocationCapturedAt) : '—']
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
    [needsFiles ? item.associateInspectionWhatsappUrl : null, filesAvailable ? 'Enviar pendências ao associado' : 'Enviar link ao associado', 'secondary'],
    [needsFiles ? item.consultantInspectionWhatsappUrl : null, 'Enviar pendências ao consultor', 'outline'],
    [currentPublicUrl, needsFiles && filesAvailable ? 'Abrir link das pendências' : 'Abrir link da vistoria'],
    [item.requestType === 'NEW_INSPECTION' ? item.quotationPdfUrl : null, 'Ver PDF da cotação'],
    [!filesAvailable ? item.teamWhatsappUrl : null, 'Comunicar equipe pelo WhatsApp']
  ]);

  renderInspectionFiles(item);
  showNotificationButton(item);
  $('message').className = '';
  $('message').textContent = '';
  clearAnalysisDialogMessage($('inspection-dialog'));
  window.NHInspectionDocuments?.render({ rootId: 'analysis-document-center', item, role: 'analyst', api, sendDossier: item => sendAnalysisDocumentAcceptance(item), downloadDossier: (item, button) => downloadAnalysisFinalDossier(item.id, button) });
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
        ? `<div class="inspection-media-actions">${video ? `<button class="outline" data-play-video="${asset.id}" type="button">Reproduzir</button>` : ''}<button class="secondary" data-download-asset="${asset.id}" data-file-name="${esc(downloadName)}" type="button">${legacyLargeVideo ? 'Baixar WebM' : 'Baixar'}</button>${canDelete ? `<button class="danger" data-delete-asset="${asset.id}" data-file-name="${esc(title)}" type="button">Excluir / solicitar novamente</button>` : ''}</div>`
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
    'Excluir arquivo da vistoria?',
    `O arquivo “${label}” será excluído. Os demais arquivos aceitos serão mantidos e o mesmo link da vistoria passará a pedir somente esta pendência (e qualquer outra que estiver faltando).`,
    'Excluir e solicitar novamente'
  );
  if (!confirmed) return;

  const dialog = $('inspection-dialog');
  button.disabled = true;
  const original = button.textContent;
  button.textContent = 'Excluindo...';
  try {
    await api(`/api/analysis/inspections/${encodeURIComponent(inspectionId)}/assets/${encodeURIComponent(assetId)}`, { method: 'DELETE' });
    releaseMediaUrls();
    if (dialog.open) dialog.close();
    await load();
    const updated = inspections.find(item => item.id === inspectionId);
    if (updated) openInspection(inspectionId);
    message('Arquivo excluído. A vistoria foi reaberta e o link agora pede somente os arquivos pendentes.', 'success');
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
    const blob = await apiBlob(`/api/analysis/inspections/${inspectionId}/assets/${assetId}`);
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
    const blob = await apiBlob(`/api/analysis/inspections/${inspectionId}/assets/${assetId}?download=true`);
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
    const blob = await apiBlob(`/api/analysis/inspections/${inspectionId}/report`);
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
    const blob = await apiBlob(`/api/analysis/inspections/${id}/assets.zip`);
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
}

$('registration-not-complete').addEventListener('click', async () => {
  const id = $('inspection-id').value;
  const item = inspections.find(value => value.id === id);
  if (!item) return;
  if (inspectionNeedsFiles(item)) {
    return message('Esta vistoria está em Aguardando documentos. Só é possível marcar Cadastro não feito depois que todos os documentos estiverem disponíveis.');
  }
  const confirmed = await confirmAnalysisAction(
    'Marcar Cadastro não feito?',
    'A vistoria permanecerá na sua fila de cadastro. A aprovação ou rejeição final não é feita pela Equipe de Análise.',
    'Cadastro não feito'
  );
  if (!confirmed) return;
  const button = $('registration-not-complete');
  button.disabled = true;
  try {
    const updated = await api(`/api/analysis/inspections/${encodeURIComponent(id)}/registration-not-complete`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ note: $('inspection-note').value.trim() })
    });
    const index = inspections.findIndex(value => value.id === updated.id);
    if (index >= 0) inspections[index] = updated;
    render();
    configureStatusOptions(updated);
    message('Situação atualizada para Cadastro não feito.', 'success');
  } catch (error) {
    message(error.message);
  } finally {
    button.disabled = false;
  }
});

$('registration-complete').addEventListener('click', async () => {
  const id = $('inspection-id').value;
  const item = inspections.find(value => value.id === id);
  if (!item) return;
  if (inspectionNeedsFiles(item)) {
    return message('Ainda existem arquivos pendentes. Conclua o envio antes de marcar Cadastro feito.');
  }
  const confirmed = await confirmAnalysisAction(
    'Marcar Cadastro feito?',
    'A vistoria sairá do seu painel e será enviada para a Supervisão de Análise. Se a supervisão reprovar alguma imagem ou vídeo, ela voltará para sua aba Pendências de vistoria.',
    'Cadastro feito'
  );
  if (!confirmed) return;
  const button = $('registration-complete');
  button.disabled = true;
  try {
    await api(`/api/analysis/inspections/${encodeURIComponent(id)}/registration-complete`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ note: $('inspection-note').value.trim() })
    });
    $('inspection-dialog').close();
    await load();
    message('Cadastro concluído. A vistoria foi encaminhada para a Supervisão de Análise.', 'success');
  } catch (error) {
    message(error.message);
  } finally {
    button.disabled = false;
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
    const updated = await api(`/api/analysis/inspections/${encodeURIComponent(id)}/details`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        associateName: $('edit-associate-name').value.trim(),
        cpf: normalizedCpf($('edit-associate-cpf').value),
        whatsapp: $('edit-associate-whatsapp').value.trim(),
        plate: $('edit-associate-plate').value.trim(),
        zeroKm: $('edit-zero-km').value === 'true',
        model: analysisVehicleModelForSave(),
        modelYear: Number($('edit-model-year').value),
        residenceAddress: $('edit-residence-address').value.trim()
      })
    });
    const index = inspections.findIndex(item => item.id === updated.id);
    if (index >= 0) inspections[index] = updated;
    render();
    openInspection(updated.id);
    message('Dados do associado e veículo atualizados. A cotação vinculada foi sincronizada.', 'success');
  } catch (error) { message(error.message); }
  finally { button.disabled = false; button.textContent = original; }
});

$('save-contract-values').addEventListener('click', async () => {
  const id = $('inspection-id').value;
  if (!id) return;

  const manualMonthlyOverride = false;
  const preview = await requestCommercialPricingPreview(ANALYSIS_COMMERCIAL_CONFIG, true);
  if (!preview) return message('Não foi possível recalcular o valor pela tabela atual do plano.');
  const fipeValue = window.NHMoney?.parse($('edit-contract-fipe').value);
  const monthlyValue = window.NHMoney?.parse($('edit-contract-monthly').value);
  const discountPercent = Number($('edit-contract-discount').value || 0);
  const rearWindowBranding = $('edit-contract-branding').value || 'NOT_APPLICABLE';
  const benefitCodes = selectedCommercialBenefits('edit-contract-benefits');
  if (!Number.isFinite(fipeValue) || fipeValue <= 0) return message('Informe um valor FIPE válido.');
  if (!Number.isFinite(monthlyValue) || monthlyValue <= 0) return message('Informe uma mensalidade válida.');

  const confirmed = await confirmAction(
    'Alterar valores do contrato',
    `Confirma a alteração da FIPE para ${new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(fipeValue)} e da mensalidade para ${new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(monthlyValue)}? Se a mensalidade mudar, o novo valor só será aplicado ao PPV/PDF depois da confirmação do associado.`
  );
  if (!confirmed) return;

  const button = $('save-contract-values');
  const original = button.textContent;
  button.disabled = true;
  button.textContent = 'Salvando...';
  try {
    const updated = await api(`/api/analysis/inspections/${encodeURIComponent(id)}/contract-values`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fipeValue, monthlyValue, discountPercent, rearWindowBranding, benefitCodes, manualMonthlyOverride, fipeRecordId: selectedFipeRecordId })
    });
    const index = inspections.findIndex(item => item.id === updated.id);
    if (index >= 0) inspections[index] = updated;
    render();
    openInspection(updated.id);
    message(updated.contractChangePending ? 'Novo valor preparado. Envie o link ao associado; o contrato só será atualizado após a confirmação dele.' : 'Revisão comercial salva no dossiê final.', 'success');
  } catch (error) {
    message(error.message);
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
});


$('copy-contract-change-link')?.addEventListener('click', async () => {
  const url = $('copy-contract-change-link').dataset.url || '';
  if (!url) return message('Não há link de confirmação pendente.');
  try {
    await navigator.clipboard.writeText(url);
    message('Link de confirmação copiado.', 'success');
  } catch (_error) {
    window.prompt('Copie o link abaixo:', url);
  }
});


$('analysis-send-responsibility-term')?.addEventListener('click', async () => {
  const button = $('analysis-send-responsibility-term');
  const id = button.dataset.inspectionId;
  button.disabled = true;
  try {
    const info = await prepareAnalysisResponsibilityTerm(id);
    if (info.accepted) {
      message('Aceite concluído. Não é necessário reenviar este termo ao associado.', 'success');
    } else if (info.whatsappUrl) {
      window.open(info.whatsappUrl, '_blank', 'noopener,noreferrer');
      try { await api(`/api/analysis/inspections/${encodeURIComponent(id)}/responsibility-term/message-sent`, { method: 'POST' }); } catch (_) {}
      message('WhatsApp aberto com o link exclusivo do Termo de Responsabilidade.', 'success');
    } else if (info.publicUrl) {
      try { await navigator.clipboard.writeText(info.publicUrl); message('Link do termo copiado para envio manual.', 'success'); }
      catch (_) { window.prompt('Copie o link do Termo de Responsabilidade:', info.publicUrl); }
    }
    const item = inspections.find(value => value.id === id); if (item) syncAnalysisResponsibilityTermBox(item);
  } catch (error) { message(error.message); }
  finally { button.disabled = false; }
});

$('analysis-copy-responsibility-term')?.addEventListener('click', async () => {
  const id = $('analysis-copy-responsibility-term').dataset.inspectionId;
  try {
    const info = await prepareAnalysisResponsibilityTerm(id);
    if (info.accepted) return message('Aceite concluído. O termo não precisa ser reenviado.', 'success');
    if (!info.publicUrl) throw new Error('Não foi possível gerar o link do termo.');
    try { await navigator.clipboard.writeText(info.publicUrl); message('Link do Termo de Responsabilidade copiado.', 'success'); }
    catch (_) { window.prompt('Copie o link do Termo de Responsabilidade:', info.publicUrl); }
  } catch (error) { message(error.message); }
});

$('analysis-open-responsibility-term')?.addEventListener('click', async () => {
  const id = $('analysis-open-responsibility-term').dataset.inspectionId;
  try { const info = await prepareAnalysisResponsibilityTerm(id); if (info.publicUrl) window.open(info.publicUrl, '_blank', 'noopener,noreferrer'); }
  catch (error) { message(error.message); }
});

$('analysis-download-responsibility-term')?.addEventListener('click', async () => {
  const id = $('analysis-download-responsibility-term').dataset.inspectionId;
  try {
    const info = await prepareAnalysisResponsibilityTerm(id);
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
  try {
    const analystChoice = $('inspection-analyst').value;
    const currentItem = inspections.find(value => value.id === id);
    const payload = {
      // A Equipe de Análise não aprova/rejeita. Este endpoint serve somente
      // para salvar observação mantendo a situação operacional atual.
      status: inspectionNeedsFiles(currentItem)
        ? (currentItem?.status === 'UPLOADING_FILES' ? 'UPLOADING_FILES' : 'WAITING_FILES')
        : 'UNDER_REVIEW',
      adminNote: $('inspection-note').value.trim()
    };
    if (currentUser?.consultantId) {
      payload.analystId = currentUser.consultantId;
    } else if (analystChoice === '__NEW__') {
      payload.analystName = $('inspection-new-analyst-name').value.trim();
      if (!payload.analystName) return message('Informe o nome do analista responsável.');
    } else if (analystChoice) {
      payload.analystId = analystChoice;
    } else {
      return message('Selecione o analista responsável pela análise.');
    }

    const updated = await api(`/api/analysis/inspections/${id}/status`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const index = inspections.findIndex(item => item.id === updated.id);
    if (index >= 0) inspections[index] = updated;
    if (payload.analystName) {
      analysts = await api('/api/analysis/inspections/analysts');
    }
    render();
    showNotificationButton(updated);
    message('Observação salva com sucesso.', 'success');
    if (updated.associateDecisionWhatsappUrl && updated.associateDecisionMessagePending) {
      $('inspection-dialog').close();
      openDecisionCommunication(updated);
    }
  } catch (error) {
    message(error.message);
  }
});

async function sendAnalysisDocumentAcceptance(item) {
  if (!item || item.digitalAcceptedAt) return;
  if (item.associateDecisionWhatsappUrl) return openDecisionCommunication(item);
  // Sem WhatsApp cadastrado: entregar um link verificável, nunca marcar como enviado.
  const link = item.publicUrl ? (window.NH_URLS?.retratoUrl(item.publicUrl) || item.publicUrl) : '';
  if (!link) return message('Esta vistoria ainda não tem link de aceite disponível.');
  try { await navigator.clipboard.writeText(link); message('Link seguro de aceite copiado para envio manual.', 'success'); }
  catch (_) { window.prompt('Copie o link seguro para enviar ao associado:', link); }
}

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
    const updated = await api(`/api/analysis/inspections/${encodeURIComponent(item.id)}/decision-message-sent`, { method: 'POST' });
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

$('inspection-analyst').addEventListener('change', syncNewAnalystInput);
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
      location.href = '/supervisao/';
    } else if (data.role === 'ANALYST') {
      showView();
      if (data.passwordChangeRequired) openFirstPasswordDialog();
      else await load();
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

bindCommercialPricingPreview(ANALYSIS_COMMERCIAL_CONFIG);


// V83.125 — termo independente de ciência de possível atraso de peças.
// O WebAuthn e seu registro pertencem somente a este documento, não ao termo de avarias.
const PARTS_DELAY_ANALYSIS_DEFAULT = 'Peças de reposição compatíveis com o modelo do veículo, sujeitas à disponibilidade no mercado.';
async function syncAnalysisPartsDelayTerm(item) {
  const box = $('analysis-parts-delay-term-box');
  if (!box || !item?.id) return;
  box.hidden = false;
  box.dataset.inspectionId = item.id;
  const notes = $('analysis-parts-delay-term-notes');
  const status = $('analysis-parts-delay-term-status');
  const send = $('analysis-send-parts-delay-term');
  const copy = $('analysis-copy-parts-delay-term');
  notes.disabled = false;
  send.disabled = false;
  copy.disabled = false;
  notes.value = PARTS_DELAY_ANALYSIS_DEFAULT;
  status.textContent = 'Documento independente: ciência de possível atraso superior a 90 dias na obtenção de peças, sem prorrogação automática de prazos.';
  try {
    const info = await api(`/api/analysis/inspections/${encodeURIComponent(item.id)}/parts-delay-term`);
    if (!box.isConnected || box.dataset.inspectionId !== item.id || !info) return;
    notes.value = info.damageDetails || PARTS_DELAY_ANALYSIS_DEFAULT;
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
async function prepareAnalysisPartsDelayTerm() {
  const id = $('analysis-parts-delay-term-box')?.dataset.inspectionId;
  if (!id) throw new Error('Abra uma vistoria para preparar o termo.');
  const note = $('analysis-parts-delay-term-notes').value.trim();
  const info = await api(`/api/analysis/inspections/${encodeURIComponent(id)}/parts-delay-term/prepare`, {
    method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({damageDetails:note || PARTS_DELAY_ANALYSIS_DEFAULT})
  });
  return {id,info};
}
$('analysis-send-parts-delay-term')?.addEventListener('click', async () => {
  const button = $('analysis-send-parts-delay-term'); button.disabled = true;
  try {
    const {id,info} = await prepareAnalysisPartsDelayTerm();
    if (info.accepted) return message('O termo de ciência já foi assinado; o reenvio está bloqueado.','success');
    if (info.whatsappUrl) {
      // Navega ao WhatsApp após preparar o link; em navegadores móveis não depende de pop-up.
      await api(`/api/analysis/inspections/${encodeURIComponent(id)}/parts-delay-term/message-sent`, {method:'POST'});
      window.location.href = info.whatsappUrl;
    } else if (info.publicUrl) {
      try { await navigator.clipboard.writeText(info.publicUrl); message('Link do termo copiado para envio manual.','success'); }
      catch (_) { window.prompt('Copie o link do termo:', info.publicUrl); }
    } else throw new Error('Link de ciência indisponível.');
    const item = inspections.find(row => row.id === id);
    if (item) syncAnalysisPartsDelayTerm(item);
  } catch (error) { message(error.message); }
  finally { button.disabled = false; }
});
$('analysis-copy-parts-delay-term')?.addEventListener('click', async () => {
  try {
    const {info} = await prepareAnalysisPartsDelayTerm();
    if (info.accepted) return message('Termo já assinado. O reenvio está bloqueado.', 'success');
    if (!info.publicUrl) throw new Error('Link indisponível.');
    try { await navigator.clipboard.writeText(info.publicUrl); message('Link do termo copiado.','success'); }
    catch (_) { window.prompt('Copie o link do termo:',info.publicUrl); }
  } catch (error) { message(error.message); }
});
$('analysis-open-parts-delay-term')?.addEventListener('click', async () => {
  const newWindow = window.open('about:blank', '_blank');
  try {
    const {info} = await prepareAnalysisPartsDelayTerm();
    if (!info.publicUrl) throw new Error('Link indisponível.');
    if (newWindow) newWindow.location.href = info.publicUrl;
    else window.location.href = info.publicUrl;
  } catch (error) { newWindow?.close(); message(error.message); }
});
$('analysis-download-parts-delay-term')?.addEventListener('click', async () => {
  try {
    const {info} = await prepareAnalysisPartsDelayTerm();
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
