const $ = id => document.getElementById(id);
const token = new URLSearchParams(location.search).get('token');
const brl = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
let state = null;

const apiUrl = path => window.NH_API?.backend(path) || path;
const money = value => value == null ? '—' : brl.format(Number(value));
const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;'
}[char]));

function showMessage(text, type = 'error') {
  const box = $('message');
  box.className = `message ${type}`;
  box.textContent = text;
  box.hidden = !text;
}

async function request(path, options = {}) {
  const response = await fetch(apiUrl(path), {
    cache: 'no-store',
    ...options,
    headers: { 'Accept': 'application/json', ...(options.headers || {}) }
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(body?.message || body?.error || 'Não foi possível concluir a solicitação.');
  }
  return body;
}

function selectedKeepOptionals() {
  const selected = document.querySelector('input[name="keep-optionals"]:checked');
  if (!selected) return null;
  return selected.value === 'true';
}

function updateDecisionTotal() {
  if (!state) return;
  const hasOptionals = Array.isArray(state.selectedOptionals) && state.selectedOptionals.length > 0;
  if (!hasOptionals) {
    $('decision-total').textContent = money(state.proposedMonthlyWithOptionals);
    return;
  }
  const keep = selectedKeepOptionals();
  $('decision-total').textContent = keep === null
    ? 'Escolha se deseja manter os adicionais'
    : money(keep ? state.proposedMonthlyWithOptionals : state.proposedMonthlyWithoutOptionals);
}

function render(data) {
  state = data;
  if (!data?.pending) {
    $('contract-card').hidden = true;
    showMessage('Não existe alteração contratual aguardando confirmação neste link.', 'success');
    return;
  }

  $('contract-card').hidden = false;
  showMessage('Confira todos os valores antes de confirmar.', 'success');
  $('associate-name').textContent = data.associateName || 'Associado';
  $('vehicle-plan').textContent = `${data.vehicleLabel || 'Veículo'} · ${data.planName || 'Plano contratado'}`;
  $('current-fipe').textContent = money(data.currentFipeValue);
  $('proposed-fipe').textContent = money(data.proposedFipeValue);
  $('current-monthly').textContent = money(data.currentMonthlyValue);
  $('proposed-monthly').textContent = money(data.proposedMonthlyWithOptionals);

  const optionals = Array.isArray(data.selectedOptionals) ? data.selectedOptionals : [];
  const hasOptionals = optionals.length > 0;
  $('optionals-section').hidden = !hasOptionals;
  $('optionals-list').innerHTML = optionals.map(item => `
    <li class="optional-row">
      <div><strong>${esc(item.name || item.code || 'Adicional')}</strong>${item.detail ? `<small>${esc(item.detail)}</small>` : ''}</div>
      <strong>${money(item.monthlyPrice)}</strong>
    </li>`).join('');
  $('optionals-total').textContent = money(data.optionalsMonthlyValue);
  $('with-optionals-label').textContent = `Manter os adicionais e confirmar ${money(data.proposedMonthlyWithOptionals)} por mês.`;
  $('without-optionals-label').textContent = `Retirar os adicionais e confirmar ${money(data.proposedMonthlyWithoutOptionals)} por mês.`;
  updateDecisionTotal();
}

async function decide(accepted) {
  if (!state?.pending) return;
  const hasOptionals = Array.isArray(state.selectedOptionals) && state.selectedOptionals.length > 0;
  const keepOptionals = hasOptionals ? selectedKeepOptionals() : null;
  if (accepted && hasOptionals && keepOptionals === null) {
    showMessage('Escolha se deseja manter ou retirar os adicionais já contratados.');
    document.querySelector('input[name="keep-optionals"]')?.focus();
    return;
  }

  const button = accepted ? $('accept-change') : $('reject-change');
  const original = button.textContent;
  button.disabled = true;
  $('accept-change').disabled = true;
  $('reject-change').disabled = true;
  button.textContent = 'Confirmando...';

  try {
    const result = await request(`/api/public/inspections/${encodeURIComponent(token)}/contract-change/decision`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accepted, keepOptionals })
    });
    $('contract-card').hidden = true;
    showMessage(result.message || (accepted ? 'Novo valor confirmado com sucesso.' : 'Alteração recusada.'), 'success');
  } catch (error) {
    showMessage(error.message);
    button.disabled = false;
    $('accept-change').disabled = false;
    $('reject-change').disabled = false;
    button.textContent = original;
  }
}

document.querySelectorAll('input[name="keep-optionals"]').forEach(input => {
  input.addEventListener('change', updateDecisionTotal);
});
$('accept-change').addEventListener('click', () => decide(true));
$('reject-change').addEventListener('click', () => {
  if (window.confirm('Confirma que você não concorda com esta alteração de valor?')) decide(false);
});

(async () => {
  if (!token) {
    showMessage('Link inválido: token de confirmação não informado.');
    return;
  }
  try {
    const data = await request(`/api/public/inspections/${encodeURIComponent(token)}/contract-change`);
    render(data);
  } catch (error) {
    showMessage(error.message);
  }
})();
