const pageParams = new URLSearchParams(window.location.search);
const PORTAL_TOKEN_KEY = 'nhPortalToken';
const PORTAL_ROLE_KEY = 'nhPortalRole';
const requestedSelfService = pageParams.get('origem') === 'site' || pageParams.get('modo') === 'cliente';
const authenticatedConsultantOnPublicFlow = Boolean(localStorage.getItem(PORTAL_TOKEN_KEY)) && localStorage.getItem(PORTAL_ROLE_KEY) === 'CONSULTANT';
const isSelfService = requestedSelfService && !authenticatedConsultantOnPublicFlow;
const SESSION_KEY = isSelfService ? 'nh-cotacao-cliente-session-v1' : 'nh-cotacao-session-v7';
const PORTAL_LAST_ACTIVITY_KEY = 'nhPortalLastActivityAt';
const CONSULTANT_KEY = 'nhSelectedConsultant';
const PORTAL_INACTIVITY_LIMIT_MS = 20 * 60 * 1000;
const PORTAL_ACTIVITY_WRITE_THROTTLE_MS = 15 * 1000;
let portalActivityWriteAt = 0;
let portalRedirectingToLogin = false;

function currentPortalToken() {
  return localStorage.getItem(PORTAL_TOKEN_KEY);
}

function parseSelectedConsultant() {
  try {
    return JSON.parse(localStorage.getItem(CONSULTANT_KEY) || 'null');
  } catch (_) {
    return null;
  }
}

const selectedConsultant = parseSelectedConsultant();

function portalTokenExpiresAtMs(value = currentPortalToken()) {
  if (!value) return null;
  const parts = String(value).split('.');
  if (parts.length !== 5) return null;
  const seconds = Number(parts[2]);
  return Number.isFinite(seconds) ? seconds * 1000 : null;
}

function portalLastActivityAtMs() {
  const value = Number(localStorage.getItem(PORTAL_LAST_ACTIVITY_KEY));
  return Number.isFinite(value) && value > 0 ? value : null;
}

function portalTokenExpired() {
  const expiresAt = portalTokenExpiresAtMs();
  return expiresAt !== null && Date.now() >= expiresAt;
}

function portalInactivityExpired() {
  const lastActivity = portalLastActivityAtMs();
  return lastActivity !== null && Date.now() - lastActivity >= PORTAL_INACTIVITY_LIMIT_MS;
}

function clearPortalSession() {
  localStorage.removeItem(PORTAL_TOKEN_KEY);
  localStorage.removeItem(PORTAL_ROLE_KEY);
  localStorage.removeItem(CONSULTANT_KEY);
  localStorage.removeItem(PORTAL_LAST_ACTIVITY_KEY);
}

function redirectToPortalLogin(message) {
  if (isSelfService || portalRedirectingToLogin) return;
  portalRedirectingToLogin = true;
  try {
    sessionStorage.setItem('nhPortalLoginMessage', message || 'Sua sessão expirou. Entre novamente.');
  } catch (_) {}
  clearPortalSession();
  window.location.replace('/colaborador/');
}

function ensurePortalSession() {
  if (isSelfService) return true;
  const authToken = currentPortalToken();
  if (!authToken || !selectedConsultant?.id) {
    redirectToPortalLogin('Sua sessão foi encerrada. Entre novamente para continuar a cotação.');
    return false;
  }
  if (portalTokenExpired()) {
    redirectToPortalLogin('Sua sessão expirou. Entre novamente para continuar a cotação.');
    return false;
  }
  if (portalInactivityExpired()) {
    redirectToPortalLogin('Sessão encerrada após 20 minutos de inatividade. Entre novamente.');
    return false;
  }
  return true;
}

function markPortalActivity(force = false) {
  if (isSelfService || !currentPortalToken() || portalRedirectingToLogin) return;
  const now = Date.now();
  if (!force && now - portalActivityWriteAt < PORTAL_ACTIVITY_WRITE_THROTTLE_MS) return;
  portalActivityWriteAt = now;
  localStorage.setItem(PORTAL_LAST_ACTIVITY_KEY, String(now));
}

function installPortalSessionGuard() {
  if (isSelfService) return;
  ['pointerdown', 'keydown', 'touchstart', 'scroll'].forEach(eventName => {
    window.addEventListener(eventName, () => {
      if (ensurePortalSession()) markPortalActivity();
    }, { passive: true });
  });
  window.addEventListener('storage', event => {
    if (event.key === PORTAL_TOKEN_KEY && !event.newValue) {
      redirectToPortalLogin('Sua sessão foi encerrada. Entre novamente.');
    }
  });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && ensurePortalSession()) markPortalActivity(true);
  });
  window.setInterval(() => ensurePortalSession(), 30 * 1000);
}

const state = {
  vehicleType: 'CAR',
  promoMotorcycleTier: null,
  promotionalMotorcyclePrices: [],
  activeVehicleCategoryCodes: null,
  plans: [],
  selectedPlanCode: '',
  selectedOptionalCodes: new Set(),
  discountPercent: 0,
  associateId: null,
  associateOptions: [],
  rearWindowBranding: 'NOT_APPLICABLE',
  discountConfirmed: false,
  quote: null,
  inspectionRequirements: [],
  inspectionFiles: [],
  inspectionIndex: 0,
  previewUrls: []
};

const $ = (id) => document.getElementById(id);
const brl = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const BILLING_DUE_DAYS = new Set([5, 10, 15, 20, 25, 30]);

if (!isSelfService) {
  queueMicrotask(() => {
    if (ensurePortalSession()) {
      markPortalActivity(true);
      installPortalSessionGuard();
    }
  });
}

function localDateOnly(date = new Date()) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function addCalendarDays(date, days) {
  const result = localDateOnly(date);
  result.setDate(result.getDate() + days);
  return result;
}

function isoLocalDate(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function billingDueOptions(baseDate = new Date()) {
  const minimum = addCalendarDays(baseDate, 30);
  const maximum = addCalendarDays(baseDate, 40);
  const options = [];
  for (let cursor = new Date(minimum); cursor <= maximum; cursor.setDate(cursor.getDate() + 1)) {
    if (BILLING_DUE_DAYS.has(cursor.getDate())) options.push(new Date(cursor));
  }
  return options;
}

function renderBillingDueOptions(preferredValue = '') {
  const select = $('firstBillingDueDate');
  if (!select) return;
  const options = billingDueOptions();
  select.innerHTML = '<option value="">Selecione o vencimento</option>' + options.map(date => {
    const value = isoLocalDate(date);
    const label = `Dia ${date.getDate()} — primeiro vencimento em ${date.toLocaleDateString('pt-BR')}`;
    return `<option value="${value}">${label}</option>`;
  }).join('');
  if (preferredValue && options.some(date => isoLocalDate(date) === preferredValue)) {
    select.value = preferredValue;
  }
  const minimum = addCalendarDays(new Date(), 30);
  const maximum = addCalendarDays(new Date(), 40);
  const help = $('billing-due-help');
  if (help) {
    help.textContent = `Escolha um vencimento entre ${minimum.toLocaleDateString('pt-BR')} e ${maximum.toLocaleDateString('pt-BR')}. Disponíveis somente os dias 5, 10, 15, 20, 25 e 30.`;
  }
}

function apiPath(path) { return window.NH_API?.backend(path) || path; }
function quoteApiPath(suffix = '') {
  return `${isSelfService ? '/api/public/quotes' : '/api/quotes'}${suffix}`;
}

async function loadPromotionalMotorcyclePrices() {
  try {
    const response = await fetch(apiPath('/api/public/quotes/promotional-motorcycle-prices'));
    if (!response.ok) return;
    const items = await response.json();
    state.promotionalMotorcyclePrices = Array.isArray(items) ? items : [];
    const availableTiers = new Set(state.promotionalMotorcyclePrices.map(item => item.tierCode));

    document.querySelectorAll('.promo-motorcycle-option').forEach(button => {
      const available = availableTiers.has(button.dataset.promoTier);
      button.hidden = !available;
      button.disabled = !available;
      if (!available && button.dataset.promoTier === state.promoMotorcycleTier) {
        state.promoMotorcycleTier = null;
        button.classList.remove('active');
      }
    });

    state.promotionalMotorcyclePrices.forEach(item => {
      const button = document.querySelector(`.promo-motorcycle-option[data-promo-tier="${item.tierCode}"]`);
      if (!button) return;
      button.dataset.minCc = String(item.minCc ?? '');
      button.dataset.maxCc = String(item.maxCc ?? '');
      button.dataset.cc = String(item.maxCc ?? '');
      button.dataset.minFipe = item.minFipe == null ? '0' : String(item.minFipe);
      button.dataset.maxFipe = item.maxFipe == null ? '' : String(item.maxFipe);
      button.dataset.monthlyPrice = String(item.monthlyPrice ?? '');
      const label = button.querySelector('strong');
      const value = button.querySelector('span');
      if (label) label.textContent = promotionalMotorcycleButtonLabel(item);
      if (value) value.textContent = `${brl.format(item.monthlyPrice)}/mês`;
    });
    syncVehicleCategoryAvailability();
  } catch (_) {
    // Mantém os valores padrão do HTML se a API estiver temporariamente indisponível.
  }
}


async function loadVehicleCategories() {
  try {
    const response = await fetch(apiPath('/api/public/quotes/categories'), { cache: 'no-store' });
    if (!response.ok) return;
    const items = await response.json();
    state.activeVehicleCategoryCodes = new Set((Array.isArray(items) ? items : []).map(item => item.code));
    syncVehicleCategoryAvailability();
  } catch (_) {
    // Em caso de indisponibilidade temporária, preserva o comportamento anterior da tela.
  }
}

function rawCategoryActive(code) {
  return state.activeVehicleCategoryCodes == null || state.activeVehicleCategoryCodes.has(code);
}

function buttonCategoryAvailable(type) {
  if (type === 'CAR') return rawCategoryActive('CAR_NATIONAL') || rawCategoryActive('CAR_IMPORTED');
  if (type === 'MOTORCYCLE_PROMO_2026') {
    return rawCategoryActive(type) && state.promotionalMotorcyclePrices.length > 0;
  }
  return rawCategoryActive(type);
}

function syncVehicleCategoryAvailability() {
  document.querySelectorAll('#vehicle-options .vehicle-option').forEach(button => {
    const available = buttonCategoryAvailable(button.dataset.type);
    button.hidden = !available;
    button.disabled = !available;
    if (!available) button.classList.remove('active');
  });

  const carOrigin = $('carOrigin');
  if (carOrigin) {
    [...carOrigin.options].forEach(option => {
      const available = rawCategoryActive(option.value);
      option.hidden = !available;
      option.disabled = !available;
    });
    if (carOrigin.selectedOptions[0]?.disabled) {
      const firstAvailable = [...carOrigin.options].find(option => !option.disabled);
      if (firstAvailable) carOrigin.value = firstAvailable.value;
    }
  }

  const current = document.querySelector(`.vehicle-option[data-type="${state.vehicleType}"]`);
  if (!current || current.hidden || current.disabled) {
    const firstAvailable = [...document.querySelectorAll('#vehicle-options .vehicle-option')].find(button => !button.hidden && !button.disabled);
    if (firstAvailable) state.vehicleType = firstAvailable.dataset.type;
  }

  document.querySelectorAll('#vehicle-options .vehicle-option').forEach(button =>
    button.classList.toggle('active', button.dataset.type === state.vehicleType && !button.hidden && !button.disabled)
  );
  updateConditionalFields();
}

function parseMoney(value) {
  return window.NHMoney?.parse(value) ?? NaN;
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function isPromoMotorcycleCategory() {
  return state.vehicleType === 'MOTORCYCLE_PROMO_2026';
}

function categoryCode() {
  return state.vehicleType === 'CAR' ? $('carOrigin').value : state.vehicleType;
}

function effectiveRegion() {
  return 'NATIONAL';
}

function effectiveMotorcycleOrigin() {
  return isMotorcycle() && !isPromoMotorcycleCategory() ? $('region').value : null;
}

function isZeroKm() {
  return document.querySelector('input[name="zeroKm"]:checked')?.value === 'true';
}

function auctionOrChassisRemarkedValue() {
  const value = document.querySelector('input[name="auctionOrChassisRemarked"]:checked')?.value;
  if (value === 'true') return true;
  if (value === 'false') return false;
  return null;
}

function indemnityFipePercent() {
  return auctionOrChassisRemarkedValue() === true ? 70 : 100;
}

function isMotorcycle() {
  return String(state.vehicleType || '').startsWith('MOTORCYCLE');
}

function promotionalMotorcycleItemFromButton(button) {
  if (!button) return null;
  const apiItem = state.promotionalMotorcyclePrices.find(item => item.tierCode === button.dataset.promoTier);
  if (apiItem) return apiItem;
  const minCc = Number(button.dataset.minCc || 0);
  const maxCc = Number(button.dataset.maxCc || button.dataset.cc || 0);
  const minFipe = button.dataset.minFipe ? Number(button.dataset.minFipe) : 0;
  const maxFipe = button.dataset.maxFipe ? Number(button.dataset.maxFipe) : null;
  const monthlyPrice = Number(button.dataset.monthlyPrice || 0);
  return {
    tierCode: button.dataset.promoTier,
    label: button.querySelector('strong')?.textContent || '',
    minCc,
    maxCc,
    minFipe: Number.isFinite(minFipe) ? minFipe : 0,
    maxFipe: Number.isFinite(maxFipe) ? maxFipe : null,
    monthlyPrice
  };
}

function selectedPromotionalMotorcycleItem() {
  return promotionalMotorcycleItemFromButton(document.querySelector('.promo-motorcycle-option.active'));
}

function promotionalMotorcycleEligibilityText(item) {
  if (!item) return 'a condição promocional selecionada';
  const minCc = Number(item.minCc || 0);
  const maxCc = Number(item.maxCc || 0);
  let ccText;
  if (minCc <= 1 && maxCc > 0) ccText = `motos até ${maxCc}cc`;
  else if (minCc > 0 && maxCc > 0 && minCc === maxCc) ccText = `motos de ${maxCc}cc`;
  else if (minCc > 0 && maxCc > 0) ccText = `motos de ${minCc}cc a ${maxCc}cc`;
  else ccText = 'motos da faixa selecionada';

  const minFipe = Number(item.minFipe || 0);
  const maxFipe = item.maxFipe == null || item.maxFipe === '' ? null : Number(item.maxFipe);
  const hasMinFipe = Number.isFinite(minFipe) && minFipe > 0;
  const hasMaxFipe = maxFipe != null && Number.isFinite(maxFipe) && maxFipe >= 0;
  if (hasMinFipe && hasMaxFipe) {
    ccText += ` com FIPE de ${brl.format(minFipe)} até ${brl.format(maxFipe)}`;
  } else if (hasMaxFipe) {
    ccText += ` com FIPE de até ${brl.format(maxFipe)}`;
  } else if (hasMinFipe) {
    ccText += ` com FIPE a partir de ${brl.format(minFipe)}`;
  }
  return ccText;
}

function promotionalMotorcycleButtonLabel(item) {
  const configuredLabel = String(item?.label || '').trim();
  if (configuredLabel) return configuredLabel;
  const text = promotionalMotorcycleEligibilityText(item);
  return text.charAt(0).toUpperCase() + text.slice(1);
}

function promoMotorcycleCc() {
  const selected = document.querySelector('.promo-motorcycle-option.active');
  if (!selected) return null;
  const cc = Number(selected.dataset.cc || 0);
  return Number.isInteger(cc) && cc > 0 ? cc : null;
}

function motorcycleCcValue() {
  if (!isPromoMotorcycleCategory()) return null;
  return promoMotorcycleCc();
}

function syncMotorcycleOptions() {
  const promo = isPromoMotorcycleCategory();
  const promoSelector = $('promo-motorcycle-options');
  if (promoSelector) promoSelector.hidden = !promo;

  document.querySelectorAll('.promo-motorcycle-option').forEach(button => {
    button.classList.toggle('active', promo && button.dataset.promoTier === state.promoMotorcycleTier);
  });
}

function validateMotorcycleForm() {
  if (!isPromoMotorcycleCategory()) return null;
  const cc = promoMotorcycleCc();
  const item = selectedPromotionalMotorcycleItem();
  if (!cc || !state.promoMotorcycleTier || !item) {
    throw new Error('Selecione uma faixa da Tabela Promocional de Motocicletas.');
  }

  const fipeValue = parseMoney($('fipeValue').value);
  const minFipe = item.minFipe == null || item.minFipe === '' ? 0 : Number(item.minFipe);
  const maxFipe = item.maxFipe == null || item.maxFipe === '' ? null : Number(item.maxFipe);
  const belowMinimum = Number.isFinite(minFipe) && minFipe > 0 && (!fipeValue || fipeValue < minFipe);
  const aboveMaximum = Number.isFinite(maxFipe) && maxFipe >= 0 && (!fipeValue || fipeValue > maxFipe);
  if (belowMinimum || aboveMaximum) {
    throw new Error(`A faixa de ${brl.format(Number(item.monthlyPrice || 0))} é exclusiva para ${promotionalMotorcycleEligibilityText(item)}.`);
  }
  return cc;
}

function syncZeroKmOptions() {
  const zeroKm = isZeroKm();
  document.querySelectorAll('.binary-option').forEach(option => {
    const input = option.querySelector('input[name="zeroKm"]');
    option.classList.toggle('selected', Boolean(input?.checked));
  });

  const plate = $('plate');
  if (!plate) return;
  plate.disabled = zeroKm;
  plate.required = !zeroKm;
  plate.setAttribute('aria-required', String(!zeroKm));
  plate.placeholder = zeroKm ? 'Não necessário para veículo 0 km' : 'ABC1D23';
  if (zeroKm) plate.value = '';

  if ($('plate-required')) $('plate-required').hidden = zeroKm;
  if ($('plate-help')) {
    $('plate-help').textContent = zeroKm
      ? 'A placa poderá ser cadastrada depois do emplacamento.'
      : 'Obrigatória para veículos que não são 0 km.';
  }
}

function syncVehicleHistoryOptions() {
  document.querySelectorAll('.vehicle-history-selector .binary-option').forEach(option => {
    const input = option.querySelector('input[name="auctionOrChassisRemarked"]');
    option.classList.toggle('selected', Boolean(input?.checked));
  });
  if ($('selected-indemnity-percent')) {
    $('selected-indemnity-percent').textContent = `${indemnityFipePercent()}% da FIPE`;
  }
}

function selectedPlan() {
  return state.plans.find(plan => plan.code === state.selectedPlanCode);
}

function selectedOptionalCoverages() {
  const plan = selectedPlan();
  if (!plan) return [];
  return plan.coverages.filter(coverage =>
    coverage.status === 'OPTIONAL' && state.selectedOptionalCodes.has(coverage.code)
  );
}

function optionalMonthlyValue() {
  return selectedOptionalCoverages().reduce(
    (total, coverage) => total + Number(coverage.monthlyPrice || 0),
    0
  );
}

function discountOptionForPlan(plan, percent = state.discountPercent) {
  const value = Number(percent || 0);
  if (!plan || value <= 0) return null;
  return (Array.isArray(plan.discounts) ? plan.discounts : []).find(option => Number(option.percent) === value) || null;
}

function selectedDiscountOption(percent = state.discountPercent) {
  return discountOptionForPlan(selectedPlan(), percent);
}

function discountRequiresConfirmation(percent = state.discountPercent) {
  return Boolean(selectedDiscountOption(percent)?.requiresConfirmation);
}

function discountBenefitEffect(plan, coverageCode, percent = state.discountPercent) {
  const option = discountOptionForPlan(plan, percent);
  if (!option || !option.available) return null;
  const code = String(coverageCode || '').trim().toUpperCase();
  if (code === 'THIRD_PARTY_BASE') return null;
  return (Array.isArray(option.benefitEffects) ? option.benefitEffects : [])
    .find(effect => String(effect.coverageCode || '').trim().toUpperCase() === code) || null;
}

function synchronizeOptionalsWithDiscount() {
  const plan = selectedPlan();
  if (!plan) return;
  const option = selectedDiscountOption();
  if (!option || !option.available) return;
  (option.benefitEffects || []).forEach(effect => {
    const code = String(effect.coverageCode || '').trim();
    if (!code) return;
    if (effect.action === 'REMOVE') state.selectedOptionalCodes.delete(code);
    if (effect.action === 'FORCE_INCLUDE') {
      const coverage = (plan.coverages || []).find(item => item.code === code && isOptionalCoverage(item));
      if (coverage?.monthlyPrice != null) state.selectedOptionalCodes.add(code);
    }
  });
}

function renderDiscountOptions() {
  const container = $('discount-options');
  if (!container || isSelfService) return;
  const plan = selectedPlan();
  const options = Array.isArray(plan?.discounts) ? plan.discounts : [];
  container.innerHTML = `${options.map(option => `
    <button type="button" class="discount-button ${Number(state.discountPercent) === Number(option.percent) ? 'active' : ''}"
      data-discount="${Number(option.percent)}" ${option.available ? '' : 'disabled'}
      title="${escapeHtml(option.available ? (option.conditionText || option.name || '') : (option.unavailableReason || 'Desconto indisponível para este veículo.'))}">
      ${Number(option.percent)}%
    </button>`).join('')}
    <button id="clear-discount" type="button" class="discount-clear ${Number(state.discountPercent) === 0 ? 'active' : ''}">Sem desconto</button>`;

  container.querySelectorAll('[data-discount]').forEach(button => {
    button.addEventListener('click', () => {
      setDiscount(Number(button.dataset.discount));
      persistSession();
    });
  });
  $('clear-discount')?.addEventListener('click', () => {
    setDiscount(0);
    persistSession();
  });
}

function updateDiscountAvailability() {
  renderDiscountOptions();
}

function setDiscount(percent, { preserveConfirmation = false, silent = false } = {}) {
  if (isSelfService) return;
  const requested = Math.max(0, Math.min(100, Number(percent) || 0));
  let normalized = requested;
  let option = null;

  if (normalized > 0) {
    option = selectedDiscountOption(normalized);
    if (!option || !option.available) {
      const reason = option?.unavailableReason || `O desconto de ${normalized}% não está liberado para este plano/veículo.`;
      normalized = 0;
      option = null;
      if (!silent) showError(reason);
    }
  }

  state.discountPercent = normalized;
  state.rearWindowBranding = option?.rearWindowBranding || 'NOT_APPLICABLE';
  const requiresConfirmation = Boolean(option?.requiresConfirmation);
  state.discountConfirmed = requiresConfirmation ? (preserveConfirmation && state.discountConfirmed) : true;
  synchronizeOptionalsWithDiscount();
  renderDiscountOptions();

  const conditionBox = $('discount-condition');
  const checkbox = $('discount-confirmation');
  if (conditionBox && checkbox) {
    conditionBox.hidden = !requiresConfirmation;
    $('discount-condition-text').textContent = requiresConfirmation
      ? `Confirmo a condição comercial: ${option?.conditionText || 'perfurado no vigia traseiro obrigatório'}.`
      : '';
    checkbox.checked = requiresConfirmation ? Boolean(state.discountConfirmed) : false;
  }
  updateSelectionSummary();
  if (state.plans.length) { renderComparison(); renderOptionals(); }
}

function setLoading(button, loading, loadingText, normalText) {
  button.disabled = loading;
  button.textContent = loading ? loadingText : normalText;
}

function showError(message) {
  const box = $('error-box');
  box.textContent = `⚠️ ${message}`;
  box.hidden = false;
}

function clearError() {
  $('error-box').hidden = true;
  $('error-box').textContent = '';
}

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  let authToken = null;

  if (!isSelfService) {
    if (!ensurePortalSession()) {
      const error = new Error('Sua sessão expirou. Entre novamente para continuar.');
      error.authExpired = true;
      throw error;
    }
    authToken = currentPortalToken();
    if (authToken) headers.set('Authorization', `Bearer ${authToken}`);
    markPortalActivity();
  }

  let response;
  try {
    response = await fetch(apiPath(path), { ...options, headers, cache: options.cache || 'no-store' });
  } catch (_) {
    throw new Error('Não foi possível conectar ao servidor. Verifique sua internet e tente novamente.');
  }

  if (!response.ok) {
    const body = await response.json().catch(() => null);

    if (!isSelfService && (response.status === 401 || response.status === 403)) {
      redirectToPortalLogin('Sua sessão expirou. Entre novamente para continuar a cotação.');
      const error = new Error('Sua sessão expirou. Entre novamente para continuar.');
      error.authExpired = true;
      throw error;
    }

    if ([502, 503, 504].includes(response.status)) {
      throw new Error('O servidor está temporariamente indisponível. Aguarde alguns segundos e tente novamente.');
    }

    throw new Error(body?.message || `Não foi possível concluir a solicitação (HTTP ${response.status}).`);
  }

  return response.status === 204 ? null : response.json();
}

function formSnapshot() {
  return {
    customerName: $('customerName').value,
    whatsapp: $('whatsapp').value,
    customerCpf: $('customerCpf')?.value || '',
    plate: $('plate').value,
    model: $('model').value,
    manufactureYear: $('manufactureYear').value,
    fipeValue: $('fipeValue').value,
    zeroKm: isZeroKm(),
    auctionOrChassisRemarked: auctionOrChassisRemarkedValue(),
    motorcycle: isMotorcycle(),
    promoMotorcycleTier: state.promoMotorcycleTier || '',
    observation: $('observation')?.value || '',
    firstBillingDueDate: $('firstBillingDueDate')?.value || '',
    carOrigin: $('carOrigin').value,
    region: $('region').value
  };
}

function persistSession() {
  if (!isSelfService) return;
  try {
    localStorage.setItem(SESSION_KEY, JSON.stringify({
      vehicleType: state.vehicleType,
      promoMotorcycleTier: state.promoMotorcycleTier,
      discountPercent: state.discountPercent,
      rearWindowBranding: state.rearWindowBranding,
      discountConfirmed: state.discountConfirmed,
      form: formSnapshot(),
      quoteId: state.quote?.id || null
    }));
  } catch (_) {
    // O sistema continua funcional mesmo quando o navegador bloqueia o storage.
  }
}

async function restoreSession() {
  if (!isSelfService) {
    try { localStorage.removeItem(SESSION_KEY); } catch (_) {}
    try { sessionStorage.removeItem(SESSION_KEY); } catch (_) {}
    return;
  }
  try {
    const saved = JSON.parse(localStorage.getItem(SESSION_KEY) || 'null');
    if (!saved) return;

    state.vehicleType = saved.vehicleType || 'CAR';
    const savedVehicleButton = document.querySelector(`.vehicle-option[data-type="${state.vehicleType}"]`);
    if (!savedVehicleButton || savedVehicleButton.hidden || savedVehicleButton.disabled) {
      const firstAvailable = [...document.querySelectorAll('#vehicle-options .vehicle-option')].find(button => !button.hidden && !button.disabled);
      state.vehicleType = firstAvailable?.dataset.type || 'CAR';
    }

    const savedPromoTier = saved.form?.promoMotorcycleTier || saved.promoMotorcycleTier || null;
    const savedPromoButton = savedPromoTier
      ? document.querySelector(`.promo-motorcycle-option[data-promo-tier="${savedPromoTier}"]`)
      : null;
    state.promoMotorcycleTier = savedPromoButton && !savedPromoButton.hidden && !savedPromoButton.disabled
      ? savedPromoTier
      : null;
    $('vehicle-options').querySelectorAll('.vehicle-option').forEach(item =>
      item.classList.toggle('active', item.dataset.type === state.vehicleType)
    );
    updateConditionalFields();
    if (!isSelfService) {
      state.discountPercent = Number(saved.discountPercent || 0);
      state.rearWindowBranding = saved.rearWindowBranding || 'NOT_APPLICABLE';
      state.discountConfirmed = Boolean(saved.discountConfirmed);
      // As opções de desconto são carregadas junto com os planos; a validação definitiva ocorre após a simulação.
    }

    const savedZeroKm = saved.form?.zeroKm === true || saved.form?.zeroKm === 'true';
    const zeroKmInput = document.querySelector(`input[name="zeroKm"][value="${savedZeroKm}"]`);
    if (zeroKmInput) zeroKmInput.checked = true;
    const savedVehicleHistory = saved.form?.auctionOrChassisRemarked;
    if (savedVehicleHistory === true || savedVehicleHistory === false || savedVehicleHistory === 'true' || savedVehicleHistory === 'false') {
      const historyInput = document.querySelector(`input[name="auctionOrChassisRemarked"][value="${String(savedVehicleHistory)}"]`);
      if (historyInput) historyInput.checked = true;
    }
    syncZeroKmOptions();
    syncVehicleHistoryOptions();
    renderBillingDueOptions(saved.form?.firstBillingDueDate || '');

    Object.entries(saved.form || {}).forEach(([id, value]) => {
      if (id !== 'consultantName' && id !== 'zeroKm' && id !== 'motorcycle' && id !== 'promoMotorcycleTier' && id !== 'firstBillingDueDate' && id !== 'auctionOrChassisRemarked' && $(id) && value != null) $(id).value = value;
    });
    window.NHMoney?.refresh($('fipeValue'));
    syncZeroKmOptions();
    syncMotorcycleOptions();

    if (saved.quoteId) {
      const quote = await api(quoteApiPath(`/${saved.quoteId}`));
      renderQuote(quote, false);
    }
  } catch (_) {
    localStorage.removeItem(SESSION_KEY);
  }
}

function resetResults() {
  state.plans = [];
  state.selectedPlanCode = '';
  state.selectedOptionalCodes.clear();
  state.discountPercent = 0;
  state.rearWindowBranding = 'NOT_APPLICABLE';
  state.discountConfirmed = false;
  state.quote = null;
  $('plans-section').hidden = true;
  $('proposal-section').hidden = true;
  $('optional-section').hidden = true;
  $('decision-result').innerHTML = '';
  if (!isSelfService) setDiscount(0);
  persistSession();
}

function updateConditionalFields() {
  $('car-origin-field').hidden = state.vehicleType !== 'CAR';
  $('region-field').hidden = !state.vehicleType.startsWith('MOTORCYCLE') || isPromoMotorcycleCategory();
  syncMotorcycleOptions();
  updateDiscountAvailability();
}

function normalizedCoverageStatus(coverage) {
  return String(coverage?.status || '').trim().toUpperCase();
}

function isOptionalCoverage(coverage) {
  return normalizedCoverageStatus(coverage) === 'OPTIONAL';
}

function coverageIcon(status) {
  return status === 'INCLUDED' ? '✅' : '❌';
}

function renderPlans() {
  const cards = $('plan-cards');
  cards.style.setProperty('--plan-count', state.plans.length);
  cards.innerHTML = state.plans.map(plan => {
    const pricing = priceWithSelectedDiscount(plan, 0);
    const activeOption = discountOptionForPlan(plan, state.discountPercent);
    const discountApplied = !isSelfService && Number(state.discountPercent || 0) > 0 && activeOption?.available;
    return `
    <article class="plan-card ${plan.code === state.selectedPlanCode ? 'selected' : ''}" data-code="${plan.code}">
      ${plan.code === 'MOTO_PROMO_2026' ? '<span class="recommended">TABELA PROMOCIONAL</span>' : (plan.name.toLowerCase().includes('completo') ? '<span class="recommended">MAIS COMPLETO</span>' : '')}
      <div class="plan-card-head">
        <h3>${escapeHtml(plan.name)}</h3><p>${escapeHtml(plan.subtitle || '')}</p><span class="national-scope-badge">🌎 Abrangência nacional</span><strong>${brl.format(pricing.total)}</strong><span>valor mensal${Number(plan.mandatoryMonthlyFee || 0) > 0 ? ' com rastreador obrigatório' : ''}</span>
        ${discountApplied ? `<small class="mandatory-fee-note">Desconto de ${Number(state.discountPercent)}% aplicado conforme a regra deste plano.</small>` : ''}
        ${Number(plan.oneTimeFee || 0) > 0 ? `<small class="mandatory-fee-note">Taxa única de instalação: ${brl.format(plan.oneTimeFee)}</small>` : ''}
      </div>
      <label class="choose-plan"><input type="radio" name="plan" value="${plan.code}" ${plan.code === state.selectedPlanCode ? 'checked' : ''}>Escolher este plano</label>
    </article>`;
  }).join('');

  cards.querySelectorAll('input[name="plan"]').forEach(input => input.addEventListener('change', () => {
    state.selectedPlanCode = input.value;
    state.selectedOptionalCodes.clear();
    if (!isSelfService) setDiscount(0);
    renderPlans();
    renderComparison();
    renderOptionals();
    updateSelectionSummary();
  }));

  renderDiscountOptions();
  renderOptionals();
  updateSelectionSummary();
}

function coverageDetailForCurrentDiscount(coverage, plan = selectedPlan()) {
  const discount = Number(state.discountPercent || 0);
  let detail = !isSelfService && discount > 0 && coverage?.discountedDetail
    ? String(coverage.discountedDetail)
    : String(coverage?.detail || '');
  const effect = !isSelfService ? discountBenefitEffect(plan, coverage?.code, discount) : null;
  if (effect?.detailOverride) detail = String(effect.detailOverride);
  else if (effect?.amountOverride != null) {
    const amount = brl.format(Number(effect.amountOverride));
    detail = detail ? `${detail} · Limite configurado: ${amount}` : `Limite configurado: ${amount}`;
  }
  return detail;
}

function priceWithSelectedDiscount(plan, optionalValue = 0) {
  const tableValue = Number(plan?.tableMonthlyValue ?? plan?.monthlyValue ?? 0);
  const mandatoryValue = Number(plan?.mandatoryMonthlyFee || 0);
  const optionals = Number(optionalValue || 0);
  const subtotal = tableValue + mandatoryValue + optionals;
  if (isSelfService) return { subtotal, discountValue: 0, total: subtotal };
  const option = discountOptionForPlan(plan, state.discountPercent);
  if (!option || !option.available || Number(state.discountPercent || 0) <= 0) {
    return { subtotal, discountValue: 0, total: subtotal };
  }
  const discountable = (option.applyToBase ? tableValue : 0)
    + (option.applyToMandatoryFee ? mandatoryValue : 0)
    + (option.applyToOptionals ? optionals : 0);
  const discountValue = discountable * Number(option.percent || 0) / 100;
  return { subtotal, discountValue, total: subtotal - discountValue };
}

function whatsappTarget(value) {
  const digits = String(value || '').replace(/\D/g, '');
  if (!digits) return '';
  return (digits.length === 10 || digits.length === 11) ? `55${digits}` : digits;
}

function normalizedConsultantWhatsapp(value) {
  const target = whatsappTarget(value);
  if (!/^55\d{10,11}$/.test(target)) {
    throw new Error('Informe um WhatsApp válido do consultor, com DDD.');
  }
  return target;
}

function updateSelectedConsultantProfile(consultant) {
  if (!consultant || !selectedConsultant) return;
  selectedConsultant.name = consultant.name || selectedConsultant.name;
  selectedConsultant.whatsapp = consultant.whatsapp || '';
  localStorage.setItem(CONSULTANT_KEY, JSON.stringify(selectedConsultant));
  const input = $('consultantWhatsapp');
  if (input) input.value = formatWhatsapp(selectedConsultant.whatsapp || '');
}

async function loadCurrentConsultantProfile() {
  if (isSelfService || !selectedConsultant?.id) return;
  try {
    const items = await api('/api/consultants');
    const consultant = (Array.isArray(items) ? items : []).find(item => item.id === selectedConsultant.id);
    if (consultant) updateSelectedConsultantProfile(consultant);
  } catch (_) {
    // A cotação continua disponível mesmo se o perfil não puder ser atualizado agora.
    const input = $('consultantWhatsapp');
    if (input && selectedConsultant?.whatsapp) input.value = formatWhatsapp(selectedConsultant.whatsapp);
  }
}

async function saveConsultantWhatsappFromQuote() {
  const input = $('consultantWhatsapp');
  const whatsapp = normalizedConsultantWhatsapp(input?.value || selectedConsultant?.whatsapp || '');
  const current = String(selectedConsultant?.whatsapp || '').replace(/\D/g, '');
  if (current === whatsapp) {
    if (input) input.value = formatWhatsapp(whatsapp);
    return whatsapp;
  }

  const updated = await api(`/api/consultants/${encodeURIComponent(selectedConsultant.id)}/whatsapp`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ whatsapp })
  });
  updateSelectedConsultantProfile(updated);
  return whatsapp;
}

function planComparisonPayload() {
  return {
    consultantId: selectedConsultant.id,
    customerName: $('customerName').value.trim(),
    model: $('model').value.trim(),
    plate: isZeroKm() ? '' : $('plate').value.trim().toUpperCase(),
    categoryCode: categoryCode(),
    region: effectiveRegion(),
    motorcycleOrigin: effectiveMotorcycleOrigin(),
    fipeValue: parseMoney($('fipeValue').value),
    manufactureYear: Number($('manufactureYear').value),
    auctionOrChassisRemarked: auctionOrChassisRemarkedValue(),
    motorcycle: isMotorcycle(),
    motorcycleCc: motorcycleCcValue(),
    promoMotorcycleTier: isPromoMotorcycleCategory() ? state.promoMotorcycleTier : null,
    discountPercent: Number(state.discountPercent || 0)
  };
}

async function sharePlanComparisonWithAssociate() {
  if (!state.plans.length) return showError('Calcule os planos antes de enviar a comparação.');
  const target = whatsappTarget($('whatsapp').value);
  if (!target) return showError('Informe o WhatsApp do associado antes de enviar a comparação.');
  if (!$('customerName').value.trim()) return showError('Informe o nome do associado antes de enviar a comparação.');
  if (!$('model').value.trim()) return showError('Informe o modelo do veículo antes de enviar a comparação.');
  if (discountRequiresConfirmation() && !state.discountConfirmed) {
    return showError('Confirme a condição do desconto antes de gerar o link de comparação.');
  }

  const button = $('share-plan-comparison');
  setLoading(button, true, 'Gerando link...', 'Enviar comparação de plano para associado');
  try {
    await saveConsultantWhatsappFromQuote();
    const result = await api('/api/plan-comparisons', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(planComparisonPayload())
    });
    const customer = $('customerName').value.trim();
    const message = [
      `Olá, ${customer}!`,
      '',
      'Preparei uma comparação dos planos da Empresa Cliente para o seu veículo.',
      'No link abaixo você pode comparar os planos, marcar os adicionais que desejar e ver o valor mensal atualizado:',
      '',
      (String(result.url || '').startsWith('/') ? `${location.origin}${result.url}` : result.url),
      '',
      'Depois é só tocar em “Escolher este plano e enviar ao consultor”.'
    ].join('\n');
    window.open(`https://wa.me/${target}?text=${encodeURIComponent(message)}`, '_blank', 'noopener,noreferrer');
  } catch (error) {
    showError(error.message || 'Não foi possível gerar o link de comparação.');
  } finally {
    setLoading(button, false, 'Gerando link...', 'Enviar comparação de plano para associado');
  }
}

function renderComparison() {
  const coverageMap = new Map();

  state.plans.forEach(plan => {
    (plan.coverages || [])
      .filter(coverage => !isOptionalCoverage(coverage))
      .forEach(coverage => coverageMap.set(coverage.code, coverage.name));
  });

  const rows = [...coverageMap.entries()];
  const count = state.plans.length;

  $('comparison-table').innerHTML = `
    <div class="comparison-row comparison-header" style="--plan-count:${count}">
      <div>Cobertura</div>${state.plans.map(plan => `<div>${escapeHtml(plan.name)}</div>`).join('')}
    </div>
    ${rows.map(([code, name]) => `
      <div class="comparison-row" style="--plan-count:${count}">
        <div class="coverage-name">${escapeHtml(name)}</div>
        ${state.plans.map(plan => {
          const coverage = (plan.coverages || []).find(item => item.code === code && !isOptionalCoverage(item));
          const effect = discountBenefitEffect(plan, code);
          let status = normalizedCoverageStatus(coverage) === 'INCLUDED' ? 'INCLUDED' : 'NOT_INCLUDED';
          if (effect?.action === 'REMOVE') status = 'NOT_INCLUDED';
          if (effect?.action === 'FORCE_INCLUDE') status = 'INCLUDED';
          const detail = coverage
            ? (coverageDetailForCurrentDiscount(coverage, plan) || (status === 'INCLUDED' ? 'Incluído' : 'Não incluído'))
            : 'Não incluído';
          const ruleNote = effect?.action === 'REMOVE'
            ? 'Removido pela regra do desconto.'
            : effect?.action === 'FORCE_INCLUDE' ? 'Incluído pela regra do desconto.' : '';

          return `<div class="coverage-state ${status.toLowerCase()}" title="${escapeHtml([detail, ruleNote].filter(Boolean).join(' · '))}"><span>${coverageIcon(status)}</span><small>${escapeHtml([detail, ruleNote].filter(Boolean).join(' · '))}</small></div>`;
        }).join('')}
      </div>
    `).join('')}
  `;
}

function renderOptionals() {
  const section = $('optional-section');
  const list = $('optional-list');
  const plan = selectedPlan();
  const optionals = plan?.coverages.filter(isOptionalCoverage) || [];

  if (!plan || optionals.length === 0) {
    section.hidden = true;
    list.innerHTML = '';
    return;
  }

  synchronizeOptionalsWithDiscount();
  const discountOption = selectedDiscountOption();
  section.hidden = false;
  list.innerHTML = optionals.map(coverage => {
    const effect = discountBenefitEffect(plan, coverage.code);
    const removed = effect?.action === 'REMOVE';
    const forced = effect?.action === 'FORCE_INCLUDE';
    if (removed) state.selectedOptionalCodes.delete(coverage.code);
    if (forced && coverage.monthlyPrice != null) state.selectedOptionalCodes.add(coverage.code);
    const checked = state.selectedOptionalCodes.has(coverage.code);
    const hasPrice = coverage.monthlyPrice != null;
    const locked = removed || forced;
    const rawPrice = Number(coverage.monthlyPrice || 0);
    const discountedPrice = discountOption?.available && discountOption.applyToOptionals
      ? rawPrice * (100 - Number(discountOption.percent || 0)) / 100
      : rawPrice;
    const ruleText = removed
      ? 'Indisponível com este desconto.'
      : forced ? 'Incluído obrigatoriamente por este desconto.' : '';
    return `
      <label class="optional-card ${checked ? 'selected' : ''} ${!hasPrice || removed ? 'unavailable' : ''}">
        <input type="checkbox" value="${escapeHtml(coverage.code)}" ${checked ? 'checked' : ''} ${!hasPrice || locked ? 'disabled' : ''}>
        <span class="optional-check">${checked ? '✓' : '+'}</span>
        <span class="optional-content"><strong>${escapeHtml(coverage.name)}</strong><small>${escapeHtml([coverageDetailForCurrentDiscount(coverage, plan) || 'Cobertura adicional', ruleText].filter(Boolean).join(' · '))}</small></span>
        <span class="optional-price">${hasPrice ? `+ ${brl.format(discountedPrice)}<small>/mês${discountedPrice !== rawPrice ? ' com desconto' : ''}</small>` : 'Valor indisponível'}</span>
      </label>
    `;
  }).join('');

  list.querySelectorAll('input[type="checkbox"]:not(:disabled)').forEach(input => {
    input.addEventListener('change', () => {
      if (input.checked) {
        if (input.value === 'FUNERAL') state.selectedOptionalCodes.delete('FUNERAL_FAMILY');
        if (input.value === 'FUNERAL_FAMILY') state.selectedOptionalCodes.delete('FUNERAL');
        state.selectedOptionalCodes.add(input.value);
      } else {
        state.selectedOptionalCodes.delete(input.value);
      }
      renderOptionals();
      updateSelectionSummary();
    });
  });
}

function updateSelectionSummary() {
  const plan = selectedPlan();
  const optionalValue = optionalMonthlyValue();
  const mandatoryValue = plan ? Number(plan.mandatoryMonthlyFee || 0) : 0;
  const tableValue = plan ? Number(plan.tableMonthlyValue ?? plan.monthlyValue) : 0;
  const oneTimeFee = plan ? Number(plan.oneTimeFee || 0) : 0;
  const pricing = plan ? priceWithSelectedDiscount(plan, optionalValue) : { subtotal: 0, discountValue: 0, total: 0 };
  const discountPercent = isSelfService ? 0 : Number(state.discountPercent || 0);
  const hasAppliedDiscount = discountPercent > 0 && pricing.discountValue > 0;
  $('selected-plan-name').textContent = plan?.name || '—';
  $('selected-plan-base-value').textContent = plan ? brl.format(tableValue) : '—';
  $('selected-mandatory-row').hidden = !plan || mandatoryValue <= 0;
  $('selected-mandatory-value').textContent = plan ? brl.format(mandatoryValue) : '—';
  $('selected-one-time-row').hidden = !plan || oneTimeFee <= 0;
  $('selected-one-time-value').textContent = plan ? brl.format(oneTimeFee) : '—';
  $('selected-optionals-value').textContent = plan ? brl.format(optionalValue) : '—';
  $('selected-pre-discount-row').hidden = !plan || discountPercent <= 0;
  $('selected-pre-discount-value').textContent = plan ? brl.format(pricing.subtotal) : '—';
  $('selected-discount-row').hidden = !plan || discountPercent <= 0;
  $('selected-discount-label').textContent = discountPercent > 0 ? `Desconto ${discountPercent}%` : 'Desconto';
  $('selected-discount-value').textContent = discountPercent > 0 ? `− ${brl.format(pricing.discountValue)}` : '—';
  $('selected-plan-value').textContent = plan ? brl.format(pricing.total) : '—';
  if ($('selected-indemnity-percent')) $('selected-indemnity-percent').textContent = `${indemnityFipePercent()}% da FIPE`;
  const conditionalDiscountPending = discountRequiresConfirmation(discountPercent) && !state.discountConfirmed;
  $('confirm-plan').disabled = !plan || conditionalDiscountPending || (discountPercent > 0 && !selectedDiscountOption(discountPercent)?.available);
}

let associateLookupTimer = null;

async function searchAssociatesForQuote() {
  if (isSelfService || !$('associateLookup')) return;
  const q = $('associateLookup').value.trim();
  if (q.length < 2) { state.associateOptions = []; $('associate-options').innerHTML = ''; state.associateId = null; return; }
  try {
    const rows = await api(`/api/commercial/associates?q=${encodeURIComponent(q)}`);
    state.associateOptions = rows || [];
    $('associate-options').innerHTML = state.associateOptions.map(a => `<option value="${escapeHtml(a.name)} • ${escapeHtml(a.document || 'sem CPF/CNPJ')}"></option>`).join('');
  } catch (_) { }
}

function applyAssociateSelection() {
  const input = $('associateLookup'); if (!input) return;
  const value = input.value.trim();
  const found = (state.associateOptions || []).find(a => `${a.name} • ${a.document || 'sem CPF/CNPJ'}` === value);
  state.associateId = found?.id || null;
  if (!found) return;
  $('customerName').value = found.name || '';
  $('customerCpf').value = found.document || '';
  if (found.whatsapp) $('whatsapp').value = found.whatsapp;
}

function formPayload() {
  const payload = {
    customerName: $('customerName').value.trim(),
    cpf: $('customerCpf').value,
    whatsapp: $('whatsapp').value,
    plate: isZeroKm() ? '' : $('plate').value.trim().toUpperCase(),
    model: $('model').value.trim(),
    manufactureYear: Number($('manufactureYear').value),
    zeroKm: isZeroKm(),
    fipeValue: parseMoney($('fipeValue').value),
    auctionOrChassisRemarked: auctionOrChassisRemarkedValue(),
    categoryCode: categoryCode(),
    region: effectiveRegion(),
    motorcycleOrigin: effectiveMotorcycleOrigin(),
    motorcycle: isMotorcycle(),
    motorcycleCc: motorcycleCcValue(),
    promoMotorcycleTier: isPromoMotorcycleCategory() ? state.promoMotorcycleTier : null,
    observation: $('observation')?.value.trim() || null,
    firstBillingDueDate: $('firstBillingDueDate')?.value || null,
    selectedPlanCode: state.selectedPlanCode,
    selectedOptionalCodes: [...state.selectedOptionalCodes]
  };
  if (!isSelfService) {
    payload.consultantId = selectedConsultant.id;
    payload.discountPercent = Number(state.discountPercent || 0);
    payload.rearWindowBranding = state.rearWindowBranding || 'NOT_APPLICABLE';
    payload.associateId = state.associateId || null;
    payload.adhesionPaymentMethod = $('adhesionPaymentMethod')?.value || 'BOLETO';
  }
  return payload;
}

function quoteStatusLabel(status, expired = false) {
  if (expired) return 'Expirada';
  if (status === 'ACCEPTED') return 'Aceita';
  if (status === 'DECLINED') return 'Não aceita';
  return 'Aguardando resposta';
}

function renderQuote(quote, scroll = true) {
  state.quote = quote;
  const selectedOptionals = quote.selectedOptionals || [];
  const baseValue = quote.baseMonthlyValue ?? quote.monthlyValue;
  const mandatoryValue = Number(quote.mandatoryMonthlyFee || 0);
  const oneTimeFee = Number(quote.oneTimeFee || 0);
  const discountPercent = Number(quote.discountPercent || 0);
  const preDiscountValue = Number(quote.preDiscountMonthlyValue ?? quote.monthlyValue);
  const discountValue = Math.max(0, preDiscountValue - Number(quote.monthlyValue || 0));
  const optionalValue = quote.optionalMonthlyValue ?? selectedOptionals.reduce(
    (total, item) => total + Number(item.monthlyPrice || 0), 0
  );

  $('proposal-title').textContent = `${quote.selectedPlanName || 'Plano'} ${quote.quoteNumber} gerada`;
  $('proposal-description').innerHTML = `O plano escolhido foi <strong>${escapeHtml(quote.selectedPlanName)}</strong>, com mensalidade total de <strong>${brl.format(quote.monthlyValue)} por mês</strong>.`;
  $('quote-details').innerHTML = `
    <div><span>Cliente</span><strong>${escapeHtml(quote.customerName)}</strong></div>
    <div><span>Veículo</span><strong>${escapeHtml(quote.model)} • ${escapeHtml(quote.plate || '0 km — sem placa')}</strong></div>
    <div><span>FIPE</span><strong>${brl.format(quote.fipeValue)}</strong></div>
    <div><span>Valor em caso de ressarcimento integral</span><strong>${Number(quote.indemnityFipePercent || 100)}% da FIPE</strong></div>
    ${quote.auctionOrChassisRemarked === true ? '<div><span>Leilão / remarcação de chassi</span><strong>Sim</strong></div>' : ''}
    <div><span>Abrangência</span><strong>Nacional</strong></div>
    ${quote.motorcycleOrigin ? `<div><span>Origem da moto</span><strong>${quote.motorcycleOrigin === 'CAPITAL' ? 'Capital' : 'Demais cidades do Nordeste'}</strong></div>` : ''}
    ${quote.observation ? `<div><span>Observação</span><strong>${escapeHtml(quote.observation)}</strong></div>` : ''}
    ${quote.billingDueDay ? `<div><span>Vencimento mensal</span><strong>Dia ${escapeHtml(quote.billingDueDay)}</strong></div>` : ''}
    ${quote.firstBillingDueDate ? `<div><span>Primeiro vencimento</span><strong>${new Date(`${quote.firstBillingDueDate}T12:00:00`).toLocaleDateString('pt-BR')}</strong></div>` : ''}
    <div><span>Veículo 0 km</span><strong>${quote.zeroKm ? 'Sim' : 'Não'}</strong></div>
    <div><span>Valor da tabela</span><strong>${brl.format(baseValue)}</strong></div>
    ${mandatoryValue > 0 ? `<div><span>Acréscimo obrigatório</span><strong>${brl.format(mandatoryValue)}</strong></div>` : ''}
    <div><span>Adicionais</span><strong>${brl.format(optionalValue)}</strong></div>
    ${discountPercent > 0 ? `<div><span>Subtotal antes do desconto</span><strong>${brl.format(preDiscountValue)}</strong></div>` : ''}
    ${discountPercent > 0 ? `<div><span>Desconto comercial</span><strong>${discountPercent}% (− ${brl.format(discountValue)})</strong></div>` : ''}
    ${quote.rearWindowBranding && quote.rearWindowBranding !== 'NOT_APPLICABLE' ? `<div><span>Condição do desconto</span><strong>${quote.rearWindowBranding === 'ANY_PERFORATED' ? 'Perfurado no vigia traseiro' : quote.rearWindowBranding === 'NH_AND_OTHER_COMPANY' ? 'Perfurado traseiro: NH + outra empresa' : quote.rearWindowBranding === 'NH_ONLY' ? 'Perfurado traseiro: somente NH' : escapeHtml(quote.rearWindowBranding)}</strong></div>` : ''}
    <div><span>Total mensal</span><strong>${brl.format(quote.monthlyValue)}</strong></div>
    ${oneTimeFee > 0 ? `<div><span>Taxa única de instalação</span><strong>${brl.format(oneTimeFee)}</strong></div>` : ''}
    <div><span>Validade</span><strong>${quote.validUntil ? new Date(quote.validUntil).toLocaleString('pt-BR') : '5 dias'}</strong></div>
    <div><span>Status</span><strong>${quoteStatusLabel(quote.status, quote.expired)}</strong></div>`;

  $('quote-optionals').innerHTML = selectedOptionals.length
    ? `<h3>Adicionais contratados</h3><div class="quote-optional-list">${selectedOptionals.map(item => `
        <div><span><strong>${escapeHtml(item.name)}</strong><small>${escapeHtml(item.detail || '')}</small></span><b>+ ${brl.format(item.monthlyPrice)}/mês</b></div>
      `).join('')}</div>`
    : '<div class="no-optionals">Nenhum adicional pago foi adicionado à proposta.</div>';

  $('proposal-section').hidden = false;
  $('accept-box').hidden = quote.status !== 'CREATED' || quote.expired;

  const shareClientPdfButton = $('share-client-pdf');
  shareClientPdfButton.hidden = isSelfService;
  shareClientPdfButton.disabled = !quote.clientWhatsappUrl;
  shareClientPdfButton.title = quote.clientWhatsappUrl
    ? `Abrir o WhatsApp do cliente ${quote.whatsapp || ''} com o link do PDF`
    : 'Informe o WhatsApp do cliente para habilitar o envio';

  renderDecisionArea(quote);
  persistSession();
  if (scroll) $('proposal-section').scrollIntoView({ behavior: 'smooth' });
}

function renderDecisionArea(quote) {
  if (quote.status === 'ACCEPTED') {
    renderInspectionStage(quote);
  } else if (quote.status === 'DECLINED') {
    $('decision-result').innerHTML = '<div class="declined-box">A proposta foi marcada como não aceita.</div>';
  } else {
    $('decision-result').innerHTML = '';
  }
}

function renderInspectionStage(quote) {
  const validityEnded = quote.validUntil && new Date(quote.validUntil).getTime() < Date.now();
  if (!isSelfService && validityEnded && !quote.inspectionUrl) {
    $('decision-result').innerHTML = `
      <div class="declined-box">Esta cotação expirou e não pode mais iniciar uma nova vistoria. Gere uma nova cotação para continuar.</div>`;
    return;
  }
  if (isSelfService) {
    const currentInspectionUrl = quote.inspectionUrl
      ? (window.NH_URLS?.retratoUrl(quote.inspectionUrl) || quote.inspectionUrl)
      : '';

    if (!quote.consultantId && !currentInspectionUrl) {
      $('decision-result').innerHTML = `
        <div class="inspection-box inspection-pending">
          <div>
            <span class="step-tag">PROPOSTA ACEITA</span>
            <h3>Aguardando atribuição de consultor</h3>
            <p>Sua proposta já foi aceita e permanece registrada. A equipe Empresa Cliente irá definir o consultor responsável; assim que isso acontecer, a vistoria digital será liberada sem você precisar fazer uma nova cotação.</p>
          </div>
        </div>`;
      return;
    }
    if (!quote.maskedCpf && !currentInspectionUrl) {
      const teamLink = quote.teamWhatsappUrl
        ? `<a class="whatsapp-button" href="${escapeHtml(quote.teamWhatsappUrl)}" target="_blank" rel="noopener">Falar com a equipe NH</a>`
        : '';
      $('decision-result').innerHTML = `
        <div class="inspection-box inspection-pending">
          <div>
            <span class="step-tag">PROPOSTA ACEITA</span>
            <h3>CPF necessário somente para liberar a vistoria</h3>
            <p>O CPF é opcional na cotação. Sua proposta foi aceita normalmente; antes de gerar a Vistoria Digital, o consultor completará este dado cadastral.</p>
          </div>
          <div class="inspection-actions">${teamLink}</div>
        </div>`;
      return;
    }
    const currentWhatsappUrl = quote.selfServiceWhatsappUrl
      ? (window.NH_URLS?.replaceLinkInCommunicationUrl(
          quote.selfServiceWhatsappUrl,
          quote.inspectionUrl,
          currentInspectionUrl
        ) || quote.selfServiceWhatsappUrl)
      : '';
    const whatsappButton = currentWhatsappUrl
      ? `<a class="whatsapp-button" href="${escapeHtml(currentWhatsappUrl)}" target="_blank" rel="noopener">Falar com um consultor pelo WhatsApp</a>`
      : '';
    const inspectionButton = currentInspectionUrl
      ? `<a class="primary-button direct-inspection-link" href="${escapeHtml(currentInspectionUrl)}">Abrir vistoria digital</a>`
      : '';
    const guidance = quote.selfServiceWhatsappUrl
      ? 'Clique em “Falar com um consultor”. A conversa será aberta no WhatsApp configurado pelo administrador e a mensagem já levará o link para você enviar as fotos e o vídeo da vistoria digital.'
      : 'O WhatsApp da equipe ainda não foi configurado pelo administrador. Use o botão “Abrir vistoria digital” para enviar as fotos e o vídeo.';
    $('decision-result').innerHTML = `
      <div class="inspection-box inspection-pending">
        <div><span class="step-tag">PRÓXIMA ETAPA</span><h3>Proposta aceita com sucesso</h3><p>${guidance}</p></div>
        <div class="inspection-actions">${whatsappButton}${inspectionButton}</div>
      </div>`;
    return;
  }

  const params = new URLSearchParams({
    name: quote.customerName,
    plate: quote.plate || '',
    zeroKm: String(Boolean(quote.zeroKm)),
    vehicleType: (String(quote.categoryCode || '').startsWith('MOTORCYCLE') || String(quote.categoryCode || '') === 'SCOOTER_ELECTRIC')
      ? 'MOTORCYCLE'
      : 'FOUR_WHEELS_OR_MORE',
    whatsapp: quote.whatsapp || '',
    quoteId: quote.id
  });
  $('decision-result').innerHTML = `
    <div class="inspection-box inspection-pending">
      <div><span class="step-tag">ETAPA 4</span><h3>Proposta aceita — gerar Retrato NH</h3><p>Crie o link de vistoria e envie ao associado. As fotos, o vídeo e os documentos serão armazenados com segurança no PostgreSQL.</p></div>
      <div class="inspection-actions"><a class="primary-button" href="/colaborador/retrato.html?${params.toString()}">Abrir Retrato NH →</a></div>
    </div>`;
}

function isMobileBrowser() {
  return window.matchMedia('(max-width: 820px)').matches
    || /Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
}

async function downloadPdf(button = $('pdf-download')) {
  if (!state.quote) return;
  persistSession();

  // No celular, reservamos uma nova aba antes do fetch para impedir que o
  // visualizador de PDF substitua a tela da cotação e apague o progresso.
  const mobilePreview = isMobileBrowser() ? window.open('about:blank', '_blank') : null;
  setLoading(button, true, 'Preparando PDF...', '⬇ Baixar PDF da cotação');
  try {
    const response = await fetch(apiPath(`/api/quotes/${state.quote.id}/pdf`));
    if (!response.ok) throw new Error('Não foi possível gerar o PDF.');
    const blob = await response.blob();
    const objectUrl = URL.createObjectURL(blob);

    if (isMobileBrowser()) {
      if (mobilePreview) {
        mobilePreview.location.href = objectUrl;
      } else {
        const anchor = document.createElement('a');
        anchor.href = objectUrl;
        anchor.target = '_blank';
        anchor.rel = 'noopener noreferrer';
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
      }
    } else {
      const anchor = document.createElement('a');
      anchor.href = objectUrl;
      anchor.download = `cotacao-${state.quote.quoteNumber}.pdf`;
      anchor.style.display = 'none';
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
    }

    setTimeout(() => URL.revokeObjectURL(objectUrl), 60000);
  } catch (error) {
    mobilePreview?.close();
    showError(error.message);
  } finally {
    setLoading(button, false, 'Preparando PDF...', '⬇ Baixar PDF da cotação');
  }
}

async function sharePdfWithClient() {
  if (!state.quote) return;
  const button = $('share-client-pdf');

  if (!state.quote.clientWhatsappUrl) {
    showError('Informe o WhatsApp do cliente para enviar o PDF da cotação.');
    return;
  }

  setLoading(button, true, 'Abrindo WhatsApp...', 'Enviar PDF ao WhatsApp do cliente');
  try {
    // O WhatsApp Web não permite anexar um arquivo automaticamente. Por isso,
    // abrimos diretamente a conversa do número informado no cadastro com uma
    // mensagem contendo o link seguro do PDF gerado pela plataforma.
    const popup = window.open(state.quote.clientWhatsappUrl, '_blank', 'noopener,noreferrer');
    if (!popup) {
      throw new Error('O navegador bloqueou a abertura do WhatsApp. Permita pop-ups e tente novamente.');
    }
  } catch (error) {
    showError(error.message);
  } finally {
    setLoading(button, false, 'Abrindo WhatsApp...', 'Enviar PDF ao WhatsApp do cliente');
  }
}

function inspectionRequirementsForQuote(quote) {
  if (String(quote.categoryCode).startsWith('MOTORCYCLE') || String(quote.categoryCode) === 'SCOOTER_ELECTRIC') {
    return [
      ['Frente da motocicleta', 'Enquadre toda a parte dianteira, mantendo placa e acessórios visíveis quando aplicável.'],
      ['Traseira da motocicleta', 'Fotografe toda a traseira, com placa legível e sem cortes.'],
      ['Lateral esquerda', 'Enquadre a motocicleta inteira pela lateral esquerda.'],
      ['Lateral direita', 'Enquadre a motocicleta inteira pela lateral direita.'],
      ['Painel e quilometragem', 'Ligue o painel e garanta que a quilometragem esteja nítida.'],
      ['Chassi / numeração', 'Aproxime apenas o necessário e mantenha toda a numeração legível.'],
      ['Motor', 'Registre o conjunto do motor com boa iluminação e sem obstruções.'],
      ['Pneus e rodas', 'Registre o estado geral dos pneus e rodas de forma nítida.'],
      ['Selfie do associado em frente à motocicleta', quote.zeroKm
        ? 'Enquadre o associado em frente à motocicleta e mostre claramente a dianteira do veículo, mesmo que ainda não tenha placa.'
        : 'Enquadre o associado em frente à motocicleta e mantenha a placa perfeitamente visível e legível.']
    ];
  }
  const rearInstruction = quote.rearWindowBranding === 'ANY_PERFORATED'
    ? 'Enquadre toda a traseira, mantenha a placa legível e mostre claramente o perfurado do vigia traseiro.'
    : quote.rearWindowBranding === 'NH_AND_OTHER_COMPANY'
      ? 'Enquadre toda a traseira, mantenha a placa legível e mostre claramente o perfurado do vigia traseiro com as logomarcas da Empresa Cliente e da outra empresa.'
      : quote.rearWindowBranding === 'NH_ONLY'
        ? 'Enquadre toda a traseira, mantenha a placa legível e mostre claramente o perfurado do vigia traseiro contendo somente a logomarca da Empresa Cliente.'
        : 'Enquadre toda a traseira e mantenha a placa perfeitamente legível.';
  return [
    ['Frente do veículo', 'Enquadre o veículo inteiro de frente, sem cortar para-choque, teto ou laterais.'],
    ['Traseira do veículo', rearInstruction],
    ['Lateral esquerda', 'Fotografe o veículo inteiro pela lateral esquerda.'],
    ['Lateral direita', 'Fotografe o veículo inteiro pela lateral direita.'],
    ['Painel e quilometragem', 'Ligue o painel e mantenha a quilometragem nítida e centralizada.'],
    ['Chassi / numeração', 'Registre a numeração do chassi sem reflexos e com todos os caracteres legíveis.'],
    ['Para-brisa dianteiro', 'Fotografe o para-brisa inteiro para demonstrar o estado do vidro.'],
    ['Interior do veículo', 'Registre bancos, painel e estado geral da cabine com boa iluminação.'],
    ['Selfie do associado em frente ao veículo', quote.zeroKm
      ? 'Enquadre o associado em frente ao veículo e mostre claramente a dianteira, mesmo que o veículo ainda não tenha placa.'
      : 'Enquadre o associado em frente ao veículo e mantenha a placa perfeitamente visível e legível.']
  ].map(([label, instruction]) => ({ label, instruction }));
}

function normalizeRequirements(requirements) {
  return requirements.map(item => Array.isArray(item)
    ? { label: item[0], instruction: item[1] }
    : item
  );
}

function openInspectionModal() {
  if (!state.quote) return;
  clearPreviewUrls();
  state.inspectionRequirements = normalizeRequirements(inspectionRequirementsForQuote(state.quote));
  state.inspectionFiles = Array(state.inspectionRequirements.length).fill(null);
  state.inspectionIndex = 0;
  $('inspection-guidelines').hidden = false;
  $('inspection-capture').hidden = true;
  $('inspection-review').hidden = true;
  $('capture-error').hidden = true;
  $('upload-status').hidden = true;
  $('inspection-modal').hidden = false;
  document.body.classList.add('modal-open');
}

function closeInspectionModal() {
  $('inspection-modal').hidden = true;
  document.body.classList.remove('modal-open');
  clearPreviewUrls();
}

function clearPreviewUrls() {
  state.previewUrls.forEach(url => URL.revokeObjectURL(url));
  state.previewUrls = [];
}

function startCapture() {
  $('inspection-guidelines').hidden = true;
  $('inspection-review').hidden = true;
  $('inspection-capture').hidden = false;
  state.inspectionIndex = 0;
  renderCaptureStep();
  setTimeout(openCamera, 160);
}

function renderCaptureStep() {
  clearPreviewUrls();
  const total = state.inspectionRequirements.length;
  const requirement = state.inspectionRequirements[state.inspectionIndex];
  const file = state.inspectionFiles[state.inspectionIndex];
  $('capture-step').textContent = `Foto ${state.inspectionIndex + 1} de ${total}`;
  $('capture-progress-bar').style.width = `${((state.inspectionIndex + (file ? 1 : 0)) / total) * 100}%`;
  $('capture-title').textContent = requirement.label;
  $('capture-instruction').textContent = requirement.instruction;
  $('capture-back').textContent = state.inspectionIndex === 0 ? '← Diretrizes' : '← Foto anterior';
  $('capture-next').textContent = state.inspectionIndex === total - 1 ? 'Revisar fotos →' : 'Próxima foto →';
  $('capture-next').disabled = !file;
  $('capture-photo').textContent = file ? 'Refazer foto' : 'Abrir câmera';
  $('capture-error').hidden = true;

  if (file) {
    const url = URL.createObjectURL(file);
    state.previewUrls.push(url);
    $('capture-preview').innerHTML = `<img src="${url}" alt="${escapeHtml(requirement.label)}"><span class="preview-approved">Foto registrada ✓</span>`;
  } else {
    $('capture-preview').innerHTML = '<div class="camera-placeholder"><span>📷</span><p>A câmera será aberta para registrar esta foto.</p></div>';
  }
}

function openCamera() {
  $('camera-input').value = '';
  $('camera-input').click();
}

async function loadPhotoSource(file) {
  if ('createImageBitmap' in window) {
    const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
    return { source: bitmap, width: bitmap.width, height: bitmap.height, close: () => bitmap.close?.() };
  }
  const url = URL.createObjectURL(file);
  try {
    const image = await new Promise((resolve, reject) => {
      const element = new Image();
      element.onload = () => resolve(element);
      element.onerror = () => reject(new Error('Não foi possível abrir a imagem.'));
      element.src = url;
    });
    return { source: image, width: image.naturalWidth, height: image.naturalHeight, close: () => URL.revokeObjectURL(url) };
  } catch (error) {
    URL.revokeObjectURL(url);
    throw error;
  }
}

const UPLOAD_MAX_BYTES = 15 * 1024 * 1024;

async function compressPhoto(file) {
  if (!file || file.size <= 0) {
    throw new Error('A foto selecionada está vazia.');
  }
  if (file.size > UPLOAD_MAX_BYTES) {
    throw new Error('Cada arquivo enviado deve possuir no máximo 15 MB.');
  }
  const photo = await loadPhotoSource(file);
  try {
    const maxDimension = 1920;
    const scale = Math.min(1, maxDimension / Math.max(photo.width, photo.height));
    const width = Math.max(1, Math.round(photo.width * scale));
    const height = Math.max(1, Math.round(photo.height * scale));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d', { alpha: false });
    context.fillStyle = '#ffffff';
    context.fillRect(0, 0, width, height);
    context.drawImage(photo.source, 0, 0, width, height);
    const blob = await new Promise((resolve, reject) =>
      canvas.toBlob(result => result ? resolve(result) : reject(new Error('Não foi possível processar a foto.')), 'image/jpeg', 0.84)
    );
    if (blob.size > UPLOAD_MAX_BYTES) {
      throw new Error('A foto processada ultrapassou 15 MB. Tire a foto novamente com uma resolução menor.');
    }
    return new File([blob], `vistoria-${state.inspectionIndex + 1}.jpg`, { type: 'image/jpeg' });
  } finally {
    photo.close();
  }
}

function showCaptureError(message) {
  $('capture-error').textContent = `⚠️ ${message}`;
  $('capture-error').hidden = false;
}

function showReview() {
  if (state.inspectionFiles.some(file => !file)) {
    showCaptureError('Registre todas as fotos obrigatórias antes de continuar.');
    return;
  }
  $('inspection-capture').hidden = true;
  $('inspection-review').hidden = false;
  clearPreviewUrls();
  $('inspection-review-grid').innerHTML = state.inspectionFiles.map((file, index) => {
    const url = URL.createObjectURL(file);
    state.previewUrls.push(url);
    return `<button class="review-photo" type="button" data-index="${index}"><img src="${url}" alt="${escapeHtml(state.inspectionRequirements[index].label)}"><span><b>${index + 1}. ${escapeHtml(state.inspectionRequirements[index].label)}</b><small>Clique para refazer</small></span></button>`;
  }).join('');
  $('inspection-review-grid').querySelectorAll('.review-photo').forEach(button => {
    button.addEventListener('click', () => {
      state.inspectionIndex = Number(button.dataset.index);
      $('inspection-review').hidden = true;
      $('inspection-capture').hidden = false;
      renderCaptureStep();
    });
  });
}

async function uploadInspection() {
  if (!state.quote || state.inspectionFiles.some(file => !file)) return;
  const button = $('inspection-upload');
  setLoading(button, true, 'Enviando fotos...', 'Enviar fotos e concluir vistoria');
  $('upload-status').hidden = false;
  $('upload-status').innerHTML = '<span class="upload-spinner"></span><div><b>Enviando e organizando a vistoria...</b><small>Não feche esta tela. O sistema está confirmando os arquivos no PostgreSQL e atualizando o relatório.</small></div>';

  try {
    const formData = new FormData();
    state.inspectionFiles.forEach((file, index) => {
      formData.append('photos', file, `${String(index + 1).padStart(2, '0')}-${file.name}`);
      formData.append('labels', state.inspectionRequirements[index].label);
    });

    const result = await api(`/api/quotes/${state.quote.id}/inspection`, {
      method: 'POST',
      body: formData
    }, true);
    state.quote = result.quote;
    persistSession();
    closeInspectionModal();
    renderQuote(result.quote);
  } catch (error) {
    $('upload-status').innerHTML = `<div class="upload-failed"><b>Não foi possível concluir o envio.</b><small>${escapeHtml(error.message)}</small></div>`;
  } finally {
    setLoading(button, false, 'Enviando fotos...', 'Enviar fotos e concluir vistoria');
  }
}

$('discount-confirmation')?.addEventListener('change', event => {
  state.discountConfirmed = Boolean(event.target.checked);
  updateSelectionSummary();
  persistSession();
});


$('vehicle-options').querySelectorAll('.vehicle-option').forEach(button => {
  button.addEventListener('click', () => {
    state.vehicleType = button.dataset.type;
    if (!isPromoMotorcycleCategory()) {
      state.promoMotorcycleTier = null;
    }
    $('vehicle-options').querySelectorAll('.vehicle-option').forEach(item => item.classList.toggle('active', item === button));
    updateConditionalFields();
    resetResults();
    clearError();
    persistSession();
  });
});

// A tabela promocional é uma escolha explícita do consultor.
document.querySelectorAll('.promo-motorcycle-option').forEach(button => {
  button.addEventListener('click', () => {
    state.promoMotorcycleTier = button.dataset.promoTier;
    document.querySelectorAll('.promo-motorcycle-option').forEach(item =>
      item.classList.toggle('active', item === button)
    );
    resetResults();
    clearError();
    persistSession();
  });
});

['consultantName','customerName','customerCpf','whatsapp','plate','model','manufactureYear','fipeValue','carOrigin','region','observation'].forEach(id => {
  if (!$(id)) return;
  $(id).addEventListener('input', () => { resetResults(); clearError(); persistSession(); });
});


document.querySelectorAll('input[name="zeroKm"]').forEach(input => {
  input.addEventListener('change', () => {
    syncZeroKmOptions();
    resetResults();
    clearError();
    persistSession();
  });
});
document.querySelectorAll('input[name="auctionOrChassisRemarked"]').forEach(input => {
  input.addEventListener('change', () => {
    syncVehicleHistoryOptions();
    updateSelectionSummary();
    clearError();
    persistSession();
  });
});
syncZeroKmOptions();
syncVehicleHistoryOptions();
syncMotorcycleOptions();
renderBillingDueOptions();
$('firstBillingDueDate')?.addEventListener('change', persistSession);

$('plate').addEventListener('input', event => event.target.value = event.target.value.toUpperCase());
$('manufactureYear').value = '';
$('manufactureYear').max = new Date().getFullYear() + 1;

$('quote-form').addEventListener('submit', async event => {
  event.preventDefault();
  clearError();
  const button = $('simulate-button');
  setLoading(button, true, 'Calculando...', 'Calcular planos disponíveis →');
  try {
    if (!isZeroKm() && !$('plate').value.trim()) throw new Error('Informe a placa do veículo.');
    const fipeValue = parseMoney($('fipeValue').value);
    if (!fipeValue || fipeValue <= 0) throw new Error('Informe um valor FIPE válido.');
    const motorcycleCc = validateMotorcycleForm();
    const result = await api(quoteApiPath('/options'), {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        categoryCode: categoryCode(),
        region: effectiveRegion(),
        motorcycleOrigin: effectiveMotorcycleOrigin(),
        motorcycle: isMotorcycle(),
        motorcycleCc,
        promoMotorcycleTier: isPromoMotorcycleCategory() ? state.promoMotorcycleTier : null,
        fipeValue,
        manufactureYear: Number($('manufactureYear').value)
      })
    });
    if (isPromoMotorcycleCategory()) {
      const promotionalPlans = (result.plans || []).filter(plan => plan.code === 'MOTO_PROMO_2026');
      if (!promotionalPlans.length) {
        throw new Error('A Tabela Promocional de Motocicletas não está ativa em Planos.');
      }
      result.plans = promotionalPlans;
    }
    state.plans = result.plans;
    state.selectedPlanCode = result.plans[0]?.code || '';
    state.selectedOptionalCodes.clear();
    renderPlans();
    renderComparison();
    $('plans-section').hidden = false;
    $('plans-section').scrollIntoView({ behavior: 'smooth' });
  } catch (error) { showError(error.message); }
  finally { setLoading(button, false, 'Calculando...', 'Calcular planos disponíveis →'); }
});

$('confirm-plan').addEventListener('click', async () => {
  clearError();
  const button = $('confirm-plan');
  setLoading(button, true, 'Salvando...', 'Confirmar plano e gerar cotação →');
  try {
    if (!isZeroKm() && !$('plate').value.trim()) throw new Error('Informe a placa do veículo.');
    validateMotorcycleForm();
    if (!$('firstBillingDueDate')?.value) {
      throw new Error('Escolha o vencimento das mensalidades.');
    }
    if (!isSelfService && discountRequiresConfirmation() && !state.discountConfirmed) {
      throw new Error('Confirme a condição exigida para utilizar este desconto.');
    }
    const quote = await api(quoteApiPath(), {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(formPayload())
    });
    renderQuote(quote);
  } catch (error) { showError(error.message); }
  finally { setLoading(button, false, 'Salvando...', 'Confirmar plano e gerar cotação →'); }
});

async function decide(decision) {
  if (!state.quote) return;
  clearError();
  try {
    const result = await api(quoteApiPath(`/${state.quote.id}/decision`), {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ decision })
    });
    const backendInspectionUrl = result.quote.inspectionUrl || result.inspectionUrl;
    const currentInspectionUrl = backendInspectionUrl
      ? (window.NH_URLS?.retratoUrl(backendInspectionUrl) || backendInspectionUrl)
      : null;
    result.quote.inspectionUrl = currentInspectionUrl;
    result.quote.selfServiceWhatsappUrl = window.NH_URLS?.replaceLinkInCommunicationUrl(
      result.quote.selfServiceWhatsappUrl || result.whatsappUrl,
      backendInspectionUrl,
      currentInspectionUrl
    ) || result.quote.selfServiceWhatsappUrl || result.whatsappUrl;
    renderQuote(result.quote);
  } catch (error) { showError(error.message); }
}

$('accept-yes').addEventListener('click', () => decide('ACCEPTED'));
$('accept-no').addEventListener('click', () => decide('DECLINED'));
$('pdf-download').addEventListener('click', () => downloadPdf());
$('share-client-pdf').addEventListener('click', sharePdfWithClient);

$('new-quote').addEventListener('click', () => {
  $('quote-form').reset();
  $('manufactureYear').value = '';
  const zeroKmNo = document.querySelector('input[name="zeroKm"][value="false"]');
  if (zeroKmNo) zeroKmNo.checked = true;
  syncZeroKmOptions();
  renderBillingDueOptions();
  const firstAvailableVehicleButton = [...document.querySelectorAll('#vehicle-options .vehicle-option')].find(button => !button.hidden && !button.disabled);
  state.vehicleType = firstAvailableVehicleButton?.dataset.type || 'CAR';
  state.promoMotorcycleTier = null;
  syncMotorcycleOptions();
  state.discountPercent = 0;
  state.rearWindowBranding = 'NOT_APPLICABLE';
  state.discountConfirmed = false;
  $('vehicle-options').querySelectorAll('.vehicle-option').forEach(item => item.classList.toggle('active', item.dataset.type === state.vehicleType && !item.hidden && !item.disabled));
  localStorage.removeItem(SESSION_KEY);
  updateConditionalFields();
  resetResults();
  clearError();
  window.scrollTo({ top: 0, behavior: 'smooth' });
});

$('inspection-close').addEventListener('click', closeInspectionModal);
$('inspection-modal').addEventListener('click', event => {
  if (event.target === $('inspection-modal')) closeInspectionModal();
});
$('inspection-start').addEventListener('click', startCapture);
$('capture-photo').addEventListener('click', openCamera);
$('camera-input').addEventListener('change', async event => {
  const file = event.target.files?.[0];
  if (!file) return;
  $('capture-photo').disabled = true;
  $('capture-photo').textContent = 'Processando...';
  try {
    state.inspectionFiles[state.inspectionIndex] = await compressPhoto(file);
    renderCaptureStep();
  } catch (error) {
    showCaptureError(error.message || 'Não foi possível processar a foto.');
  } finally {
    $('capture-photo').disabled = false;
  }
});
$('capture-back').addEventListener('click', () => {
  if (state.inspectionIndex === 0) {
    $('inspection-capture').hidden = true;
    $('inspection-guidelines').hidden = false;
    return;
  }
  state.inspectionIndex -= 1;
  renderCaptureStep();
});
$('capture-next').addEventListener('click', () => {
  if (!state.inspectionFiles[state.inspectionIndex]) return;
  if (state.inspectionIndex === state.inspectionRequirements.length - 1) {
    showReview();
  } else {
    state.inspectionIndex += 1;
    renderCaptureStep();
  }
});
$('review-back').addEventListener('click', () => {
  state.inspectionIndex = 0;
  $('inspection-review').hidden = true;
  $('inspection-capture').hidden = false;
  renderCaptureStep();
});
$('inspection-upload').addEventListener('click', uploadInspection);

document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && !$('inspection-modal').hidden) closeInspectionModal();
});

function formatCpf(value) {
  const digits = String(value || '').replace(/\D/g, '').slice(0, 11);
  return digits
    .replace(/^(\d{3})(\d)/, '$1.$2')
    .replace(/^(\d{3})\.(\d{3})(\d)/, '$1.$2.$3')
    .replace(/\.(\d{3})(\d)/, '.$1-$2');
}

function formatWhatsapp(value) {
  const digits = String(value || '').replace(/\D/g, '').replace(/^55(?=\d{10,11}$)/, '').slice(0, 11);
  if (digits.length <= 2) return digits ? `(${digits}` : '';
  if (digits.length <= 6) return `(${digits.slice(0, 2)}) ${digits.slice(2)}`;
  if (digits.length <= 10) return `(${digits.slice(0, 2)}) ${digits.slice(2, 6)}-${digits.slice(6)}`;
  return `(${digits.slice(0, 2)}) ${digits.slice(2, 7)}-${digits.slice(7)}`;
}

function configurePageMode() {
  if (!isSelfService) {
    // Cada abertura da aba de cotação começa limpa. Mantemos somente a identidade
    // do consultor logado; os campos comerciais voltam aos respectivos placeholders.
    $('quote-form').reset();
    $('manufactureYear').value = '';
    $('fipeValue').value = '';
    window.NHMoney?.refresh($('fipeValue'));
    if ($('discount-section')) $('discount-section').hidden = false;
    $('consultantName').value = selectedConsultant.name;
    $('customer-cpf-field').hidden = false;
    if ($('associate-link-field')) $('associate-link-field').hidden = false;
    if ($('adhesion-payment-field')) $('adhesion-payment-field').hidden = false;
    $('customerCpf').required = false;
    const comparisonShareActions = $('comparison-share-actions');
    if (comparisonShareActions) comparisonShareActions.hidden = false;
    const consultantWhatsappInput = $('consultantWhatsapp');
    if (consultantWhatsappInput) consultantWhatsappInput.value = formatWhatsapp(selectedConsultant?.whatsapp || '');
    return;
  }

  document.body.classList.add('self-service-mode');
  if ($('discount-section')) $('discount-section').hidden = true;
  $('consultant-field').hidden = true;
  $('consultantName').required = false;
  $('customer-cpf-field').hidden = false;
  if ($('associate-link-field')) $('associate-link-field').hidden = true;
  if ($('adhesion-payment-field')) $('adhesion-payment-field').hidden = true;
  $('customerCpf').required = false;
  $('whatsapp').required = true;
  $('whatsapp-required').textContent = '*';
  $('environment-pill').textContent = '🔒 Cotação segura pelo site';
  $('back-link').href = '/';
  $('back-link').textContent = '← Voltar ao site';
  $('hero-title').textContent = 'Faça sua própria cotação em poucos minutos.';
  $('hero-description').textContent = 'Informe os dados do veículo, compare os planos, gere o PDF e aceite a proposta diretamente pelo site.';
  $('footer-label').textContent = 'Empresa Cliente • Cotação online para clientes';
  $('customerName').value = pageParams.get('nome') || '';
  $('whatsapp').value = formatWhatsapp(pageParams.get('whatsapp') || '');
  const zeroKmParam = pageParams.get('zeroKm');
  if (zeroKmParam === 'true' || zeroKmParam === 'false') {
    const option = document.querySelector(`input[name="zeroKm"][value="${zeroKmParam}"]`);
    if (option) option.checked = true;
  }
  $('plate').value = (pageParams.get('placa') || '').toUpperCase();
  syncZeroKmOptions();
  const comparisonShareActions = $('comparison-share-actions');
  if (comparisonShareActions) comparisonShareActions.hidden = isSelfService;
}

$('customerCpf')?.addEventListener('input', event => { event.target.value = formatCpf(event.target.value); });
$('whatsapp').addEventListener('input', event => { event.target.value = formatWhatsapp(event.target.value); });
$('consultantWhatsapp')?.addEventListener('input', event => { event.target.value = formatWhatsapp(event.target.value); });

configurePageMode();
updateConditionalFields();
Promise.all([loadVehicleCategories(), loadPromotionalMotorcyclePrices(), loadCurrentConsultantProfile()]).finally(() => {
  syncVehicleCategoryAvailability();
  
$('share-plan-comparison')?.addEventListener('click', sharePlanComparisonWithAssociate);

restoreSession().finally(() => {
    if (isSelfService) {
      // Os dados vindos do botão público devem prevalecer sobre uma sessão antiga salva no navegador.
      configurePageMode();
    } else {
      $('consultantName').value = selectedConsultant.name;
    }
  });
});


window.addEventListener('pageshow', event => {
  if (!isSelfService && event.persisted) {
    // Evita que o navegador restaure via BFCache os valores digitados numa cotação anterior.
    location.reload();
  }
});

$('associateLookup')?.addEventListener('input', () => { state.associateId = null; clearTimeout(associateLookupTimer); associateLookupTimer = setTimeout(searchAssociatesForQuote, 250); });
$('associateLookup')?.addEventListener('change', applyAssociateSelection);
