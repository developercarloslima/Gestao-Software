const TOKEN_KEY = 'nhPortalToken';
const ROLE_KEY = 'nhPortalRole';
const LAST_ACTIVITY_KEY = 'nhPortalLastActivityAt';
const LEGACY_TOKEN_KEY = 'nhTowPortalToken';
const LEGACY_ROLE_KEY = 'nhTowPortalRole';
if (!localStorage.getItem(TOKEN_KEY) && localStorage.getItem(LEGACY_TOKEN_KEY)) localStorage.setItem(TOKEN_KEY, localStorage.getItem(LEGACY_TOKEN_KEY));
if (!localStorage.getItem(ROLE_KEY) && localStorage.getItem(LEGACY_ROLE_KEY)) localStorage.setItem(ROLE_KEY, localStorage.getItem(LEGACY_ROLE_KEY));
localStorage.removeItem(LEGACY_TOKEN_KEY); localStorage.removeItem(LEGACY_ROLE_KEY);
const INACTIVITY_LIMIT_MS = 20 * 60 * 1000;
const $ = id => document.getElementById(id);
const qs = (selector, root = document) => root.querySelector(selector);
const qsa = (selector, root = document) => [...root.querySelectorAll(selector)];
const state = { me: null, records: [], current: null, template: [], createExisting: null, urls: new Map(), inactivityTimer: null };
const h = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const dt = value => value ? new Date(value).toLocaleString('pt-BR') : '—';
const bytes = value => { const n = Number(value || 0); return n < 1024 ? `${n} B` : n < 1048576 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1048576).toFixed(1)} MB`; };
const token = () => localStorage.getItem(TOKEN_KEY);
const normalizePlate = value => String(value || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 10);
const categoryLabel = v => ({ LIGHT_CAR: 'Carro leve', MOTORCYCLE: 'Motocicleta', UTILITY: 'Utilitário', TRUCK: 'Caminhão' })[v] || v || 'Não informado';
const photoKindLabel = v => ({ FRONT: 'Frente', LEFT_SIDE: 'Lateral esquerda', RIGHT_SIDE: 'Lateral direita', REAR: 'Traseira', OTHER: 'Outra' })[v] || v;
const requiredKinds = ['FRONT', 'LEFT_SIDE', 'RIGHT_SIDE', 'REAR'];

function apiUrl(path) { return window.NH_API?.backend(path) || path; }
function message(id, text = '', type = 'error') { const el = $(id); if (!el) return; el.className = text ? `message ${type}` : ''; el.textContent = text; }
function toast(text, type = 'success') { qs('.tow-toast')?.remove(); const el = document.createElement('div'); el.className = `tow-toast message ${type}`; el.textContent = text; Object.assign(el.style, { position: 'fixed', right: '18px', bottom: '18px', zIndex: '9998', maxWidth: '430px', boxShadow: '0 12px 35px rgba(7,12,64,.2)' }); document.body.appendChild(el); setTimeout(() => el.remove(), 4200); }
function clearUrls() { for (const url of state.urls.values()) URL.revokeObjectURL(url); state.urls.clear(); }
function clearSession() { localStorage.removeItem(TOKEN_KEY); localStorage.removeItem(ROLE_KEY); localStorage.removeItem(LAST_ACTIVITY_KEY); if (state.inactivityTimer) clearTimeout(state.inactivityTimer); state.inactivityTimer = null; state.me = null; state.current = null; clearUrls(); }
function showLogin(text = '') { clearSession(); qsa('dialog[open]').forEach(dialog => { try { dialog.close(); } catch (_) {} }); $('login-view').hidden = false; $('app-view').hidden = true; $('logout').hidden = true; message('login-message', text); }
function showApp() { $('login-view').hidden = true; $('app-view').hidden = false; $('logout').hidden = false; }
function markActivity() { if (!token()) return; localStorage.setItem(LAST_ACTIVITY_KEY, String(Date.now())); scheduleInactivity(); }
function scheduleInactivity() { if (state.inactivityTimer) clearTimeout(state.inactivityTimer); if (!token()) return; const last = Number(localStorage.getItem(LAST_ACTIVITY_KEY) || Date.now()); const remaining = Math.max(0, INACTIVITY_LIMIT_MS - (Date.now() - last)); state.inactivityTimer = setTimeout(() => { if (Date.now() - Number(localStorage.getItem(LAST_ACTIVITY_KEY) || 0) >= INACTIVITY_LIMIT_MS) showLogin('Sessão encerrada após 20 minutos de inatividade. Entre novamente.'); else scheduleInactivity(); }, remaining + 150); }

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (token()) headers.set('Authorization', `Bearer ${token()}`);
  const opts = { ...options, headers, cache: options.cache || 'no-store' };
  if (options.body && !(options.body instanceof FormData) && typeof options.body !== 'string') { headers.set('Content-Type', 'application/json'); opts.body = JSON.stringify(options.body); }
  const response = await fetch(apiUrl(path), opts);
  const type = response.headers.get('content-type') || '';
  const body = type.includes('application/json') ? await response.json().catch(() => null) : await response.text().catch(() => '');
  if (!response.ok) {
    if (response.status === 401) {
      const text = 'Sua sessão expirou. Entre novamente.';
      showLogin(text);
      throw new Error(text);
    }
    throw new Error(body?.message || body?.error || `Não foi possível concluir a solicitação (${response.status}).`);
  }
  markActivity();
  return response.status === 204 ? null : body;
}

async function apiBlob(path) {
  const headers = new Headers();
  if (token()) headers.set('Authorization', `Bearer ${token()}`);
  const response = await fetch(apiUrl(path), { headers, cache: 'no-store' });
  if (!response.ok) { if (response.status === 401) showLogin('Sua sessão expirou. Entre novamente.'); throw new Error('Não foi possível carregar a foto.'); }
  markActivity();
  return response.blob();
}

function redirectForRole(role) { if(window.NH_ROUTING?.redirectForRole){window.NH_ROUTING.redirectForRole(role);return;} const map={CONSULTANT:'/colaborador/',ANALYST:'/analise/',SUPERVISION_ANALYSIS:'/supervisao/',WORKSHOP_MANAGER:'/oficina/',TOW_DRIVER:'/guincho/',EVENT_OPERATOR:'/checklist/',BUYER:'/financeiro/gestao.html',ADMIN:'/admin/'}; location.replace(map[role]||'/'); }
function statusLabel(v) { return v === 'COMPLETED' ? 'Concluído' : 'Em preenchimento'; }
function statusClass(v) { return v === 'COMPLETED' ? 'tow-completed' : 'tow-progress'; }

async function login(event) {
  event.preventDefault(); message('login-message'); const button = qs('#login-form button'); button.disabled = true;
  try {
    const result = await window.NH_FACE_AUTH.login($('username').value.trim(), $('password').value);
    localStorage.setItem(TOKEN_KEY, result.token); localStorage.setItem(ROLE_KEY, result.role); localStorage.setItem(LAST_ACTIVITY_KEY, String(Date.now()));
    if (result.role !== 'TOW_DRIVER') { redirectForRole(result.role); return; }
    await bootAuthenticated();
  } catch (error) { message('login-message', error.message); } finally { button.disabled = false; }
}

async function bootAuthenticated() {
  state.me = await api('/api/auth/me');
  if (state.me.role !== 'TOW_DRIVER') { redirectForRole(state.me.role); return; }
  showApp(); $('user-chip').textContent = `✓ ${state.me.displayName || state.me.username}`; scheduleInactivity();
  if (state.me.passwordChangeRequired) { $('password-dialog').showModal(); return; }
  state.template = await api('/api/tow/template');
  await loadRecords(); route();
}

async function boot() { if (!token()) return showLogin(); try { await bootAuthenticated(); } catch (error) { showLogin(error.message); } }
async function changePassword(event) { event.preventDefault(); message('password-message'); const next = $('new-password').value, confirm = $('confirm-password').value; if (next.length < 8) return message('password-message', 'A nova senha deve ter pelo menos 8 caracteres.'); if (next !== confirm) return message('password-message', 'A confirmação da nova senha não confere.'); try { state.me = await api('/api/auth/change-password', { method: 'POST', body: { currentPassword: $('current-password').value, newPassword: next } }); $('password-dialog').close(); $('password-form').reset(); toast('Senha alterada com sucesso.'); state.template = await api('/api/tow/template'); await loadRecords(); route(); } catch (error) { message('password-message', error.message); } }

async function loadRecords() { const button = $('refresh'); if (button) button.disabled = true; message('queue-message'); try { const params = new URLSearchParams(); if ($('search').value.trim()) params.set('q', $('search').value.trim()); if ($('status-filter').value) params.set('status', $('status-filter').value); state.records = await api(`/api/tow/records${params.toString() ? `?${params}` : ''}`); renderQueue(); } catch (error) { message('queue-message', error.message); } finally { if (button) button.disabled = false; } }
function renderQueue() { $('kpi-draft').textContent = state.records.filter(r => r.status === 'DRAFT').length; $('kpi-completed').textContent = state.records.filter(r => r.status === 'COMPLETED').length; $('kpi-total').textContent = state.records.length; $('event-list').innerHTML = state.records.length ? state.records.map(r => { const total = Number(r.totalItems || 0), answered = Number(r.answeredItems || 0), pct = total ? Math.round(answered * 100 / total) : 0; return `<article class="tow-event-card"><div class="tow-event-head"><div><h3>${h(r.vehiclePlate)}</h3><small>${h(r.code)} · ${dt(r.createdAt)}</small></div><span class="badge ${statusClass(r.status)}">${h(statusLabel(r.status))}</span></div><div class="tow-meta"><div><span>Modelo</span><strong>${h(r.vehicleModel || '—')}</strong></div><div><span>Categoria</span><strong>${h(categoryLabel(r.vehicleCategory))}</strong></div><div><span>Prestador</span><strong>${h(r.providerName || '—')}</strong></div><div><span>Motorista</span><strong>${h(r.driverName || '—')}</strong></div></div><div class="tow-progress"><div><strong>${answered}/${total} itens</strong> · ${Number(r.photoCount || 0)} foto(s)</div><div class="tow-progress-bar"><span style="width:${pct}%"></span></div></div><div class="tow-card-actions"><button class="secondary open-record" data-id="${r.id}" type="button">${r.status === 'DRAFT' ? 'Continuar checklist' : 'Consultar checklist'}</button></div></article>`; }).join('') : '<div class="tow-empty">Nenhum checklist de reboque encontrado.</div>'; qsa('.open-record').forEach(btn => btn.addEventListener('click', () => { location.hash = `#/record/${btn.dataset.id}`; })); }

function groupTemplate(items) { const groups = new Map(); for (const item of items || []) { if (!groups.has(item.section)) groups.set(item.section, []); groups.get(item.section).push(item); } return groups; }
function selectedFiles(row, cameraSelector, gallerySelector) { return [...(qs(cameraSelector, row)?.files || []), ...(qs(gallerySelector, row)?.files || [])]; }
function evidenceButtons(cameraClass, galleryClass) { return `<div class="tow-file-actions"><label class="tow-file-button">📷 Tirar foto<input class="${cameraClass}" type="file" accept="image/jpeg,image/png,image/webp" capture="environment" hidden /></label><label class="tow-file-button outline-file">🖼️ Escolher da galeria<input class="${galleryClass}" type="file" accept="image/jpeg,image/png,image/webp" multiple hidden /></label></div>`; }
function refreshCreateEvidence(row) { const yes = qs('.create-answer', row)?.value === 'YES'; const box = qs('.tow-item-evidence', row); if (!box) return; box.hidden = !yes; if (!yes) { const camera = qs('.create-item-camera', row), gallery = qs('.create-item-gallery', row); if (camera) camera.value = ''; if (gallery) gallery.value = ''; } const files = selectedFiles(row, '.create-item-camera', '.create-item-gallery'); const count = qs('.tow-evidence-count', row); if (count) count.textContent = files.length ? `${files.length} foto(s) selecionada(s)` : 'Nenhuma foto selecionada'; }
function bindCreateChecklistInputs(root) { qsa('.create-answer', root).forEach(select => { const row = select.closest('[data-template-id]'); select.addEventListener('change', () => refreshCreateEvidence(row)); refreshCreateEvidence(row); }); qsa('.create-item-camera,.create-item-gallery', root).forEach(input => input.addEventListener('change', () => refreshCreateEvidence(input.closest('[data-template-id]')))); }
function renderCreateChecklist() {
  const root = $('create-checklist');
  if (!state.template.length) { root.innerHTML = '<div class="tow-empty"><strong>Carregando checklist padrão...</strong><br><small>Atualize a página se os itens não aparecerem em alguns segundos.</small></div>'; return; }
  root.innerHTML = [...groupTemplate(state.template).entries()].map(([section, items]) => `<div class="tow-create-section"><h4>${h(section)}</h4>${items.map(item => `<div class="tow-create-row" data-template-id="${item.id}"><strong>${h(item.label)}</strong><select class="create-answer"><option value="UNANSWERED">Selecione...</option><option value="YES">Sim</option><option value="NO">Não</option><option value="NOT_APPLICABLE">Não se aplica</option></select><input class="create-item-notes" maxlength="3000" placeholder="Observação opcional" /><div class="tow-item-evidence" hidden><span><strong>Foto / evidência de ${h(item.label)}</strong><small>Ao marcar Sim, fotografe agora ou escolha uma imagem da galeria.</small></span>${evidenceButtons('create-item-camera','create-item-gallery')}<small class="tow-evidence-count">Nenhuma foto selecionada</small></div></div>`).join('')}</div>`).join('');
  bindCreateChecklistInputs(root);
}
function findDraftByPlate(value) { const plate = normalizePlate(value); return state.records.find(r => normalizePlate(r.vehiclePlate) === plate && r.status === 'DRAFT') || null; }
function showExistingNotice(found) {
  const box = $('create-existing-message'), button = $('create-submit');
  state.createExisting = found || null;
  if (found) {
    box.className = 'message success';
    box.innerHTML = `Já existe o checklist <strong>${h(found.code)}</strong> em preenchimento para a placa <strong>${h(found.vehiclePlate)}</strong>. Não será criado outro atendimento: clique abaixo para abrir e continuar assinalando o checklist.`;
    button.textContent = 'Abrir checklist existente';
  } else {
    box.className = ''; box.textContent = ''; button.textContent = 'Criar e abrir checklist';
  }
}
function updateExistingNotice() {
  const plate = normalizePlate($('create-plate').value); $('create-plate').value = plate;
  showExistingNotice(plate.length >= 5 ? findDraftByPlate(plate) : null);
}
async function lookupDraftByPlate(value) {
  const plate = normalizePlate(value);
  if (plate.length < 5) return null;
  const local = findDraftByPlate(plate);
  if (local) return local;
  const params = new URLSearchParams({ q: plate, status: 'DRAFT' });
  const rows = await api(`/api/tow/records?${params}`);
  return rows.find(r => normalizePlate(r.vehiclePlate) === plate && r.status === 'DRAFT') || null;
}
async function checkExistingPlate() {
  const plate = normalizePlate($('create-plate').value); $('create-plate').value = plate;
  if (plate.length < 5) return showExistingNotice(null);
  try { showExistingNotice(await lookupDraftByPlate(plate)); }
  catch (error) { message('create-message', error.message); }
}
function openCreate() { $('create-form').reset(); state.createExisting = null; message('create-message'); showExistingNotice(null); renderCreateChecklist(); $('create-dialog').showModal(); setTimeout(() => $('create-plate').focus(), 50); }
function collectCreateAnswers() { return qsa('[data-template-id]', $('create-checklist')).map(row => ({ templateItemId: row.dataset.templateId, answer: qs('.create-answer', row).value, notes: qs('.create-item-notes', row).value.trim() })).filter(item => item.answer !== 'UNANSWERED'); }
async function uploadCreatePhotos(recordId) { const errors = []; for (const kind of [...requiredKinds, 'OTHER']) { const camera = $(`create-file-${kind}`), gallery = $(`create-gallery-${kind}`); const files = [...(camera?.files || []), ...(gallery?.files || [])]; if (!files.length) continue; const form = new FormData(); form.set('photoKind', kind); form.set('notes', 'Enviada na abertura do checklist'); files.forEach(file => form.append('files', file, file.name)); try { await api(`/api/tow/records/${recordId}/photos`, { method: 'POST', body: form }); } catch (error) { errors.push(`${photoKindLabel(kind)}: ${error.message}`); } } return errors; }
async function uploadCreateItemPhotos(record) { const errors = []; const byTemplate = new Map((record.checklist || []).filter(i => i.templateItemId).map(i => [String(i.templateItemId), i])); for (const row of qsa('[data-template-id]', $('create-checklist'))) { if (qs('.create-answer', row)?.value !== 'YES') continue; const files = selectedFiles(row, '.create-item-camera', '.create-item-gallery'); if (!files.length) continue; const item = byTemplate.get(String(row.dataset.templateId)); if (!item) { errors.push('Não foi possível vincular uma foto ao item do checklist.'); continue; } const form = new FormData(); form.set('photoKind', 'OTHER'); form.set('checklistItemId', item.id); form.set('notes', `Evidência do checklist: ${item.label}`); files.forEach(file => form.append('files', file, file.name)); try { await api(`/api/tow/records/${record.id}/photos`, { method: 'POST', body: form }); } catch (error) { errors.push(`${item.label}: ${error.message}`); } } return errors; }
async function createRecord(event) {
  event.preventDefault(); message('create-message'); const button = $('create-submit'); button.disabled = true;
  try {
    const plate = normalizePlate($('create-plate').value); $('create-plate').value = plate;
    if (plate.length < 5) throw new Error('Informe uma placa válida.');
    const existing = state.createExisting && normalizePlate(state.createExisting.vehiclePlate) === plate ? state.createExisting : await lookupDraftByPlate(plate);
    if (existing) {
      $('create-dialog').close();
      location.hash = `#/record/${existing.id}`;
      toast(`Checklist ${existing.code} localizado. Continue o preenchimento existente.`);
      return;
    }
    const record = await api('/api/tow/records', { method: 'POST', body: { vehiclePlate: plate, vehicleModel: $('create-model').value, vehicleCategory: $('create-category').value, providerName: $('create-provider').value, driverName: $('create-driver').value, driverPhone: $('create-phone').value, generalNotes: $('create-notes').value, checklist: collectCreateAnswers() } });
    const uploadErrors = [...await uploadCreatePhotos(record.id), ...await uploadCreateItemPhotos(record)];
    $('create-dialog').close(); await loadRecords(); location.hash = `#/record/${record.id}`;
    toast(`Checklist ${record.code} criado.`);
    if (uploadErrors.length) setTimeout(() => toast(`Checklist salvo, mas houve falha em foto(s): ${uploadErrors.join(' | ')}`, 'error'), 300);
  } catch (error) { message('create-message', error.message); } finally { button.disabled = false; }
}

function groupItems(items) { const m = new Map(); for (const item of items) { if (!m.has(item.section)) m.set(item.section, []); m.get(item.section).push(item); } return m; }
async function openRecord(id) { clearUrls(); $('queue-view').hidden = true; $('detail-view').hidden = false; $('event-detail').innerHTML = '<div class="card">Carregando checklist...</div>'; try { state.current = await api(`/api/tow/records/${id}`); renderDetail(); await renderPhotos(); } catch (error) { $('event-detail').innerHTML = `<div class="message error">${h(error.message)}</div>`; } }
function photoKindsPresent() { return new Set((state.current?.photos || []).filter(p => !p.checklistItemId).map(p => p.photoKind)); }
function itemEvidenceCount(itemId) { return (state.current?.photos || []).filter(p => String(p.checklistItemId || '') === String(itemId)).length; }
function towItemRowHtml(item, canEdit) { const yes = item.answer === 'YES', count = itemEvidenceCount(item.id); return `<div class="tow-row ${yes ? 'tow-answer-yes' : ''}" data-item-id="${item.id}" data-item-label="${h(item.label)}"><div class="tow-label">${h(item.label)}</div><select class="tow-answer" ${canEdit ? '' : 'disabled'}><option value="UNANSWERED" ${item.answer === 'UNANSWERED' ? 'selected' : ''}>Selecione...</option><option value="YES" ${yes ? 'selected' : ''}>Sim</option><option value="NO" ${item.answer === 'NO' ? 'selected' : ''}>Não</option><option value="NOT_APPLICABLE" ${item.answer === 'NOT_APPLICABLE' ? 'selected' : ''}>Não se aplica</option></select><input class="tow-notes" maxlength="3000" placeholder="Observação opcional" value="${h(item.notes || '')}" ${canEdit ? '' : 'disabled'} />${canEdit ? '<button class="secondary save-tow-item" type="button">Salvar</button>' : ''}<div class="tow-item-evidence" ${yes ? '' : 'hidden'}><span><strong>Foto / evidência do item</strong><small>${count ? `${count} foto(s) já registrada(s). Você pode adicionar mais.` : 'Tire uma foto agora ou escolha da galeria.'}</small></span>${canEdit ? evidenceButtons('tow-item-camera','tow-item-gallery') : ''}<small class="tow-evidence-count">${count ? `${count} foto(s) registrada(s)` : 'Nenhuma foto registrada'}</small></div>${item.updatedAt ? `<small class="tow-updated">Última atualização: ${dt(item.updatedAt)}${item.updatedBy ? ` · ${h(item.updatedBy)}` : ''}</small>` : ''}</div>`; }
function refreshTowEvidence(row) { const yes = qs('.tow-answer', row)?.value === 'YES'; const box = qs('.tow-item-evidence', row); if (!box) return; box.hidden = !yes; row.classList.toggle('tow-answer-yes', yes); if (!yes) { const camera = qs('.tow-item-camera', row), gallery = qs('.tow-item-gallery', row); if (camera) camera.value = ''; if (gallery) gallery.value = ''; } const existing = itemEvidenceCount(row.dataset.itemId); const pending = selectedFiles(row, '.tow-item-camera', '.tow-item-gallery').length; const count = qs('.tow-evidence-count', row); if (count) count.textContent = pending ? `${pending} nova(s) foto(s) selecionada(s)${existing ? ` · ${existing} já salva(s)` : ''}` : existing ? `${existing} foto(s) registrada(s)` : 'Nenhuma foto registrada'; }
function bindTowItemInputs() { qsa('.tow-answer').forEach(select => { const row = select.closest('[data-item-id]'); select.addEventListener('change', () => refreshTowEvidence(row)); refreshTowEvidence(row); }); qsa('.tow-item-camera,.tow-item-gallery').forEach(input => input.addEventListener('change', () => refreshTowEvidence(input.closest('[data-item-id]')))); }
function renderDetail() { const r = state.current; if (!r) return; $('detail-status').textContent = statusLabel(r.status); $('detail-status').className = `badge ${statusClass(r.status)}`; const groups = groupItems(r.checklist || []); const canEdit = r.canEdit; const present = photoKindsPresent(); $('event-detail').innerHTML = `<section class="card"><div class="event-action-card"><div><span class="eyebrow">${h(r.code)}</span><h2>${h(r.vehiclePlate)} · ${h(r.vehicleModel || 'Modelo não informado')}</h2><p>${h(categoryLabel(r.vehicleCategory))}</p></div><span class="badge ${statusClass(r.status)}">${h(statusLabel(r.status))}</span></div><div class="tow-summary"><div><span>Prestador</span><strong>${h(r.providerName || '—')}</strong></div><div><span>Motorista</span><strong>${h(r.driverName || '—')}</strong></div><div><span>Telefone</span><strong>${h(r.driverPhone || '—')}</strong></div><div><span>Criado por</span><strong>${h(r.createdByName || '—')}</strong></div></div>${r.generalNotes ? `<div class="tow-note"><strong>Observações gerais:</strong> ${h(r.generalNotes)}</div>` : ''}<div class="tow-link-note"><strong>Reconhecimento por placa:</strong> quando uma ocorrência com a placa <strong>${h(r.vehiclePlate)}</strong> for cadastrada, o NH Checklist poderá localizar este atendimento concluído automaticamente.</div></section><section class="tow-checklist">${[...groups.entries()].map(([section, items]) => `<div class="tow-section"><h3>${h(section)}</h3>${items.map(item => towItemRowHtml(item, canEdit)).join('')}</div>`).join('')}</section><section class="card tow-photos-card"><div class="section-head"><div><h2>Fotos do veículo</h2><p>Frente, lateral esquerda, lateral direita e traseira são necessárias para concluir. Você pode <strong>tirar a foto pela câmera</strong> ou <strong>escolher da galeria</strong>, em qualquer ordem.</p></div><span class="badge ${requiredKinds.every(k => present.has(k)) ? 'ok' : 'warn'}">${requiredKinds.filter(k => present.has(k)).length}/4 posições</span></div>${canEdit ? photoUploadHtml(present) : ''}<div id="photo-grid" class="tow-photo-grid"></div></section><section class="card tow-complete-card ${r.status === 'COMPLETED' ? 'completed' : ''}"><div><h3>${r.status === 'COMPLETED' ? 'Checklist concluído' : 'Concluir checklist do reboque'}</h3><p>${r.status === 'COMPLETED' ? `Concluído por ${h(r.completedByName || '—')} em ${dt(r.completedAt)}.` : 'Preencha todos os acessórios e registre as quatro posições do veículo.'}</p></div>${canEdit ? '<button id="complete-tow" class="primary" type="button">Concluir checklist</button>' : ''}</section>`; qsa('.save-tow-item').forEach(btn => btn.addEventListener('click', () => saveItem(btn.closest('[data-item-id]')))); bindTowItemInputs(); qsa('.photo-uploader').forEach(btn => btn.addEventListener('click', () => uploadKind(btn.dataset.kind))); $('complete-tow')?.addEventListener('click', completeTow); }
function photoUploadHtml(present) { const defs = [['FRONT', 'Frente'], ['LEFT_SIDE', 'Lateral esquerda'], ['RIGHT_SIDE', 'Lateral direita'], ['REAR', 'Traseira'], ['OTHER', 'Outra / detalhe']]; return `<div class="tow-photo-positions">${defs.map(([kind, label]) => `<div class="tow-photo-position ${present.has(kind) ? 'done' : ''}"><div><strong>${h(label)}</strong><small>${kind === 'OTHER' ? 'Opcional' : present.has(kind) ? 'Foto recebida' : 'Pendente'}</small></div><div class="tow-file-actions"><label class="tow-file-button">📷 Câmera<input id="file-${kind}" type="file" accept="image/jpeg,image/png,image/webp" capture="environment" hidden /></label><label class="tow-file-button outline-file">🖼️ Galeria<input id="gallery-${kind}" type="file" accept="image/jpeg,image/png,image/webp" ${kind === 'OTHER' ? 'multiple' : ''} hidden /></label></div><input id="notes-${kind}" maxlength="1000" placeholder="Observação da foto (opcional)"/><button class="secondary photo-uploader" type="button" data-kind="${kind}">Enviar ${h(label.toLowerCase())}</button></div>`).join('')}</div>`; }
async function uploadTowItemFiles(recordId, itemId, label, files) { if (!files.length) return state.current; const form = new FormData(); form.set('photoKind', 'OTHER'); form.set('checklistItemId', itemId); form.set('notes', `Evidência do checklist: ${label}`); files.forEach(file => form.append('files', file, file.name)); return api(`/api/tow/records/${recordId}/photos`, { method: 'POST', body: form }); }
async function saveItem(row) { const button = qs('.save-tow-item', row), answer = qs('.tow-answer', row).value, notes = qs('.tow-notes', row).value.trim(), files = answer === 'YES' ? selectedFiles(row, '.tow-item-camera', '.tow-item-gallery') : []; if (answer === 'UNANSWERED') return toast('Marque Sim, Não ou Não se aplica.', 'error'); button.disabled = true; try { state.current = await api(`/api/tow/records/${state.current.id}/items/${row.dataset.itemId}`, { method: 'PATCH', body: { answer, notes } }); if (files.length) state.current = await uploadTowItemFiles(state.current.id, row.dataset.itemId, row.dataset.itemLabel || 'Item', files); toast(files.length ? 'Item e foto(s) salvos.' : 'Item salvo.'); renderDetail(); await renderPhotos(); await loadRecords(); } catch (error) { toast(error.message, 'error'); } finally { if (button && document.body.contains(button)) button.disabled = false; } }
async function uploadKind(kind) { const camera = $(`file-${kind}`), gallery = $(`gallery-${kind}`), files = [...(camera?.files || []), ...(gallery?.files || [])]; if (!files.length) return toast(`Tire ou selecione uma foto de ${photoKindLabel(kind).toLowerCase()}.`, 'error'); const form = new FormData(); form.set('photoKind', kind); form.set('notes', $(`notes-${kind}`)?.value.trim() || ''); files.forEach(file => form.append('files', file, file.name)); const button = qs(`.photo-uploader[data-kind="${kind}"]`); button.disabled = true; button.textContent = 'Enviando...'; try { state.current = await api(`/api/tow/records/${state.current.id}/photos`, { method: 'POST', body: form }); toast(`${files.length} foto(s) enviada(s).`); renderDetail(); await renderPhotos(); await loadRecords(); } catch (error) { toast(error.message, 'error'); } finally { if (button && document.body.contains(button)) { button.disabled = false; button.textContent = 'Enviar'; } } }
async function photoUrl(photo) { if (state.urls.has(photo.id)) return state.urls.get(photo.id); const blob = await apiBlob(`/api/tow/records/${state.current.id}/photos/${photo.id}`); const url = URL.createObjectURL(blob); state.urls.set(photo.id, url); return url; }
async function renderPhotos() { const grid = $('photo-grid'); if (!grid || !state.current) return; clearUrls(); if (!state.current.photos.length) { grid.innerHTML = '<div class="tow-empty">Nenhuma foto enviada ainda.</div>'; return; } const itemNames = new Map((state.current.checklist || []).map(i => [String(i.id), i.label])); grid.innerHTML = state.current.photos.map(photo => { const itemLabel = photo.checklistItemId ? itemNames.get(String(photo.checklistItemId)) : null; return `<article class="tow-photo-card" data-photo-id="${photo.id}"><div class="tow-photo-preview"><span>Carregando foto...</span></div><div class="tow-photo-body"><span class="badge ${requiredKinds.includes(photo.photoKind) && !photo.checklistItemId ? 'ok' : ''}">${h(itemLabel ? `Evidência · ${itemLabel}` : photoKindLabel(photo.photoKind))}</span><strong>${h(photo.originalName)}</strong><small>${bytes(photo.fileSize)} · ${dt(photo.createdAt)}</small>${photo.notes ? `<small>${h(photo.notes)}</small>` : ''}${state.current.canEdit ? '<button class="danger delete-photo" type="button">Excluir</button>' : ''}</div></article>`; }).join(''); await Promise.all(state.current.photos.map(async photo => { const card = qs(`[data-photo-id="${photo.id}"]`, grid); if (!card) return; try { const url = await photoUrl(photo); const preview = qs('.tow-photo-preview', card); preview.innerHTML = `<img src="${url}" alt="${h(photoKindLabel(photo.photoKind))}" />`; preview.addEventListener('click', () => showPhoto(url)); } catch (_) { qs('.tow-photo-preview', card).textContent = 'Não foi possível carregar'; } })); qsa('.delete-photo', grid).forEach(btn => btn.addEventListener('click', () => deletePhoto(btn.closest('[data-photo-id]').dataset.photoId))); }
function showPhoto(url) { $('photo-viewer-image').src = url; $('photo-viewer').hidden = false; }
function closePhoto() { $('photo-viewer').hidden = true; $('photo-viewer-image').src = ''; }
async function deletePhoto(id) { if (!confirm('Excluir esta foto do checklist?')) return; try { state.current = await api(`/api/tow/records/${state.current.id}/photos/${id}`, { method: 'DELETE' }); toast('Foto excluída.'); renderDetail(); await renderPhotos(); await loadRecords(); } catch (error) { toast(error.message, 'error'); } }
async function completeTow() { if (!confirm('Confirmar a conclusão deste checklist de reboque? Após concluir ele ficará disponível para reconhecimento pela placa.')) return; const button = $('complete-tow'); button.disabled = true; try { state.current = await api(`/api/tow/records/${state.current.id}/complete`, { method: 'POST' }); toast('Checklist de reboque concluído.'); renderDetail(); await renderPhotos(); await loadRecords(); } catch (error) { toast(error.message, 'error'); } finally { if (button && document.body.contains(button)) button.disabled = false; } }
function showQueue() { state.current = null; clearUrls(); $('detail-view').hidden = true; $('queue-view').hidden = false; }
function route() { const match = location.hash.match(/^#\/record\/([0-9a-f-]+)$/i); if (match) openRecord(match[1]); else showQueue(); }

$('login-form').addEventListener('submit', login);
$('password-form').addEventListener('submit', changePassword);
$('create-form').addEventListener('submit', createRecord);
$('new-record').addEventListener('click', openCreate);
$('cancel-create').addEventListener('click', () => $('create-dialog').close());
$('close-create-top')?.addEventListener('click', () => $('create-dialog').close());
$('create-plate').addEventListener('input', () => { $('create-plate').value = normalizePlate($('create-plate').value); updateExistingNotice(); });
$('create-plate').addEventListener('blur', checkExistingPlate);
$('logout').addEventListener('click', () => showLogin());
$('refresh').addEventListener('click', loadRecords);
$('search-button').addEventListener('click', loadRecords);
$('status-filter').addEventListener('change', loadRecords);
$('search').addEventListener('keydown', e => { if (e.key === 'Enter') loadRecords(); });
$('back').addEventListener('click', () => { location.hash = ''; });
$('close-photo').addEventListener('click', closePhoto);
$('photo-viewer').addEventListener('click', e => { if (e.target === $('photo-viewer')) closePhoto(); });
window.addEventListener('hashchange', route);
window.addEventListener('beforeunload', clearUrls);
['pointerdown', 'keydown', 'touchstart', 'scroll'].forEach(name => window.addEventListener(name, markActivity, { passive: true }));
boot();
