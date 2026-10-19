/* V83.128 — CEP estruturado da vistoria. Sem credenciais nem dados pessoais externos. */
(function () {
  const CEP_RE = /\D/g;
  const ids = { cep: 'residence-cep', street: 'residence-street', number: 'residence-number', complement: 'residence-complement', neighborhood: 'residence-neighborhood', city: 'residence-city', state: 'residence-state' };
  const $ = name => document.getElementById(ids[name]);
  let controller = null;
  let debounce = null;
  let lastFetched = '';
  let auto = {};
  let requestSeq = 0;
  const digits = v => String(v || '').replace(CEP_RE, '').slice(0, 8);
  const mask = v => { const s = digits(v); return s.length > 5 ? `${s.slice(0,5)}-${s.slice(5)}` : s; };
  const values = () => Object.fromEntries(Object.keys(ids).map(k => [k, ($(k)?.value || '').trim()]));
  const status = (text, kind = 'info') => {
    const node = document.getElementById('residence-cep-status');
    if (node) { node.textContent = text; node.dataset.kind = kind; }
  };
  function cancel() { ++requestSeq; clearTimeout(debounce); controller?.abort(); controller = null; }
  function clearOldAutofill() {
    for (const [key, val] of Object.entries(auto)) if ($(key)?.value === val) $(key).value = '';
    auto = {};
  }
  function compose(data = values()) {
    const cep = digits(data.cep);
    if (cep.length !== 8) throw new Error('Informe um CEP válido com 8 números.');
    for (const key of ['street','number','neighborhood','city','state']) {
      if (!data[key]) throw new Error(`Preencha o campo ${({street:'Rua / avenida',number:'Número',neighborhood:'Bairro',city:'Cidade',state:'UF'})[key]}.`);
    }
    const str = [data.street, `nº ${data.number}`, data.complement || null, data.neighborhood, `${data.city}/${data.state}`, `CEP ${mask(cep)}`].filter(Boolean).join(', ');
    if (str.length > 600) throw new Error('O endereço não pode ultrapassar 600 caracteres.');
    return str;
  }
  function parse(address) {
    const source = String(address || '').trim();
    const ending = source.match(/(?:^|,\s*)CEP\s+(\d{5})-?(\d{3})\s*$/i);
    if (!ending) return null;
    const parts = source.slice(0, ending.index).replace(/,\s*$/, '').split(/,\s*/).filter(Boolean);
    const townIndex = parts.findIndex(p => /^[^,]+\/[A-Z]{2}$/.test(p));
    if (parts.length < 4 || !/^n[ºo°.]?\s+/i.test(parts[1] || '') || townIndex < 3) return null;
    const town = parts[townIndex].match(/^(.*)\/([A-Z]{2})$/);
    const middle = parts.slice(2,townIndex);
    return {cep:`${ending[1]}-${ending[2]}`,street:parts[0],number:parts[1].replace(/^n[ºo°.]?\s+/i,''),complement:middle.slice(0,-1).join(', '),neighborhood:middle.at(-1)||'',city:town[1],state:town[2]};
  }
  async function fetchCep(cep, signal) {
    for (const url of [`https://opencep.com/v1/${cep}`, `https://viacep.com.br/ws/${cep}/json/`]) {
      try {
        const res = await fetch(url, { signal, headers: { Accept: 'application/json' }, cache: 'no-store' });
        if (!res.ok) continue;
        const body = await res.json();
        if (body && body.erro !== true && body.erro !== 'true' && body.localidade && body.uf) return body;
      } catch(e) { if (signal.aborted) throw e; }
    }
    return null;
  }
  async function lookup(force = false) {
    const cep = digits($('cep')?.value);
    if (cep.length !== 8) { status('CEP obrigatório: digite os 8 números.', 'error'); return false; }
    if (!force && lastFetched === cep) return true;
    cancel();
    const seq = requestSeq, ac = new AbortController(); controller = ac;
    const button = document.getElementById('residence-search-cep');
    if (button) { button.disabled = true; button.textContent = 'Buscando...'; }
    status('Buscando CEP e preenchendo endereço...', 'loading');
    const timeout = setTimeout(() => ac.abort(), 7000);
    try {
      const data = await fetchCep(cep, ac.signal);
      if (requestSeq !== seq || digits($('cep')?.value) !== cep) return false;
      if (!data) { status('CEP não localizado ou serviço indisponível. Confira o CEP e preencha os demais dados manualmente.', 'error'); return false; }
      clearOldAutofill();
      for (const [field,prop] of Object.entries({street:'logradouro',neighborhood:'bairro',city:'localidade',state:'uf'})) {
        const input = $(field), val = String(data[prop] || '').trim();
        if (!input || !val) continue;
        const v = field === 'state' ? val.toUpperCase() : val;
        if (field === 'state' && ![...input.options].some(option => option.value === v)) continue;
        input.value = v; auto[field] = v;
      }
      lastFetched = cep;
      status('Endereço encontrado. Confira rua, bairro, cidade e UF; complete o número e o complemento, se houver.', 'ok');
      sync();
      return true;
    } catch(e) {
      if (requestSeq === seq) status('Não foi possível consultar o CEP. Confira os dados e preencha manualmente, mantendo o CEP obrigatório.', 'error');
      return false;
    } finally {
      clearTimeout(timeout);
      if (requestSeq === seq) {
        controller = null;
        if (button) { button.disabled = false; button.textContent = 'Buscar CEP'; }
      }
    }
  }
  function sync() {
    try { document.getElementById('residence-address').value = compose(); }
    catch (_) { /* Não guardar endereço incompleto como confirmado. */ document.getElementById('residence-address').value = ''; }
    document.getElementById('residence-address')?.dispatchEvent(new Event('input'));
  }
  function restore(fields, legacyAddress = '') {
    const parsed = fields && digits(fields.cep).length === 8 ? fields : parse(legacyAddress);
    if (!parsed) return;
    cancel(); auto = {}; lastFetched = '';
    for (const key of Object.keys(ids)) if ($(key)) $(key).value = parsed[key] || '';
    sync();
    status('Endereço recuperado. Confira os dados antes de concluir.', 'info');
  }
  function required(enabled) {
    for (const key of ['cep','street','number','neighborhood','city','state']) if ($(key)) $(key).required = Boolean(enabled);
  }
  function init(onDraftChanged) {
    const cep = $('cep');
    if (!cep) return;
    cep.addEventListener('input', () => {
      cep.value = mask(cep.value);
      cancel();
      if (digits(cep.value) !== lastFetched) { lastFetched = ''; clearOldAutofill(); }
      status(digits(cep.value).length === 8 ? 'Consultando CEP...' : 'Digite os 8 números do CEP.');
      sync(); onDraftChanged?.();
      if (digits(cep.value).length === 8) debounce = setTimeout(() => lookup(), 380);
    });
    cep.addEventListener('blur', () => { if (digits(cep.value).length === 8 && digits(cep.value) !== lastFetched) lookup(); });
    document.getElementById('residence-search-cep')?.addEventListener('click', () => lookup(true));
    Object.keys(ids).filter(k => k !== 'cep').forEach(key => $(key)?.addEventListener('input', () => {
      if (auto[key] !== $(key).value) delete auto[key];
      sync(); onDraftChanged?.();
    }));
  }
  window.NHResidenceAddress = { init, values, compose, parse, restore, required, lookup, digits, mask };
})();
