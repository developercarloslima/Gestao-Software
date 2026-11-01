
// Ano automático no footer
const currentYearElements = document.querySelectorAll('.js-current-year');
currentYearElements.forEach((element) => {
  element.textContent = String(new Date().getFullYear());
});

const menuToggle = document.querySelector('.menu-toggle');
const menu = document.querySelector('.main-menu');

if (menuToggle && menu) {
  menuToggle.addEventListener('click', () => {
    const opened = menu.classList.toggle('open');
    menuToggle.classList.toggle('active', opened);
    menuToggle.setAttribute('aria-expanded', String(opened));
    document.body.classList.toggle('menu-open', opened);
  });

  menu.querySelectorAll('a').forEach((link) => {
    link.addEventListener('click', () => {
      menu.classList.remove('open');
      menuToggle.classList.remove('active');
      menuToggle.setAttribute('aria-expanded', 'false');
      document.body.classList.remove('menu-open');
    });
  });
}

const navLinks = document.querySelectorAll('.main-menu a');
const localNavLinks = [...navLinks].filter((link) => {
  const href = link.getAttribute('href') || '';
  return href.startsWith('#') && href.length > 1;
});

const sections = localNavLinks
  .map((link) => document.querySelector(link.getAttribute('href')))
  .filter(Boolean);

function setActiveLink() {
  if (!sections.length) return;

  const current = sections.findLast((section) => section.offsetTop - 130 <= window.scrollY);
  if (!current) return;

  navLinks.forEach((link) => {
    const href = link.getAttribute('href') || '';
    link.classList.toggle('active', href === `#${current.id}`);
  });
}

window.addEventListener('scroll', setActiveLink, { passive: true });
setActiveLink();

const revealElements = document.querySelectorAll(
  '.section-heading, .benefit-card, .proof-strip, .step-card, .app-card, .contact-panel, .contact-form, .boleto-box, .boleto-page-card, .office-card, .footer-grid article'
);

revealElements.forEach((element) => element.classList.add('reveal'));

const observer = new IntersectionObserver(
  (entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        entry.target.classList.add('visible');
      }
    });
  },
  { threshold: 0.12 }
);

revealElements.forEach((element) => observer.observe(element));

const cotacaoModal = document.querySelector('#cotacaoModal');
const cotacaoTriggers = document.querySelectorAll('.cotacao-trigger');
const cotacaoCloseButtons = document.querySelectorAll('[data-close-cotacao]');
const cotacaoForm = document.querySelector('#cotacaoForm');
const cotacaoFormMessage = document.querySelector('#cotacaoFormMessage');
const cotacaoPhone = document.querySelector('#cotacaoPhone');
const cotacaoPlate = document.querySelector('#cotacaoPlate');
const cotacaoPlateRequired = document.querySelector('#cotacaoPlateRequired');
const cotacaoPlateHelp = document.querySelector('#cotacaoPlateHelp');
const cotacaoZeroKmInputs = document.querySelectorAll('input[name="ZeroKm"]');
let lastFocusedCotacaoTrigger = null;

function openCotacaoModal(event) {
  if (!cotacaoModal) return;

  event.preventDefault();
  lastFocusedCotacaoTrigger = event.currentTarget;

  cotacaoModal.classList.add('open');
  cotacaoModal.setAttribute('aria-hidden', 'false');
  document.body.classList.add('cotacao-modal-open');

  const firstInput = cotacaoModal.querySelector('input[name="Nome"]');
  if (firstInput) firstInput.focus();
}

function closeCotacaoModal() {
  if (!cotacaoModal) return;

  cotacaoModal.classList.remove('open');
  cotacaoModal.setAttribute('aria-hidden', 'true');
  document.body.classList.remove('cotacao-modal-open');

  if (lastFocusedCotacaoTrigger) {
    lastFocusedCotacaoTrigger.focus();
  }
}


function publicQuoteIsZeroKm() {
  return document.querySelector('input[name="ZeroKm"]:checked')?.value === 'true';
}

function syncPublicQuoteZeroKm() {
  const zeroKm = publicQuoteIsZeroKm();

  document.querySelectorAll('.cotacao-binary-option').forEach((option) => {
    const input = option.querySelector('input[name="ZeroKm"]');
    option.classList.toggle('selected', Boolean(input?.checked));
  });

  if (!cotacaoPlate) return;

  cotacaoPlate.disabled = zeroKm;
  cotacaoPlate.required = !zeroKm;
  cotacaoPlate.setAttribute('aria-required', String(!zeroKm));
  cotacaoPlate.placeholder = zeroKm ? 'Não necessário para veículo 0 km' : 'ABC1D23';

  if (zeroKm) {
    cotacaoPlate.value = '';
    cotacaoPlate.removeAttribute('aria-invalid');
  }

  if (cotacaoPlateRequired) cotacaoPlateRequired.hidden = zeroKm;
  if (cotacaoPlateHelp) {
    cotacaoPlateHelp.textContent = zeroKm
      ? 'A placa será informada posteriormente, após o emplacamento.'
      : 'Obrigatória para veículos que não são 0 km.';
  }
}

function formatPhone(value) {
  const digits = value.replace(/\D/g, '').slice(0, 11);

  if (digits.length <= 2) return digits ? `(${digits}` : '';
  if (digits.length <= 6) return `(${digits.slice(0, 2)}) ${digits.slice(2)}`;
  if (digits.length <= 10) {
    return `(${digits.slice(0, 2)}) ${digits.slice(2, 6)}-${digits.slice(6)}`;
  }
  return `(${digits.slice(0, 2)}) ${digits.slice(2, 7)}-${digits.slice(7)}`;
}

function setFormMessage(messageElement, text, type = '') {
  if (!messageElement) return;
  messageElement.textContent = text;
  messageElement.dataset.status = type;
}

async function sendStaticForm(form, messageElement, successMessage) {
  const submitButton = form.querySelector('button[type="submit"]');
  const originalButtonText = submitButton ? submitButton.textContent : '';

  setFormMessage(messageElement, 'Enviando...', 'loading');
  if (submitButton) {
    submitButton.disabled = true;
    submitButton.textContent = 'Enviando...';
  }

  try {
    const response = await fetch(form.action, {
      method: 'POST',
      body: new FormData(form),
      headers: {
        Accept: 'application/json',
      },
    });

    const result = await response.json().catch(() => ({}));

    if (!response.ok) {
      throw new Error(result.message || 'Não foi possível enviar o formulário.');
    }

    form.reset();
    setFormMessage(messageElement, successMessage, 'success');
  } catch (error) {
    setFormMessage(
      messageElement,
      'Não foi possível enviar agora. Verifique se o e-mail já foi confirmado no FormSubmit ou tente novamente.',
      'error'
    );
  } finally {
    if (submitButton) {
      submitButton.disabled = false;
      submitButton.textContent = originalButtonText;
    }
  }
}

if (cotacaoPhone) {
  cotacaoPhone.addEventListener('input', () => {
    cotacaoPhone.value = formatPhone(cotacaoPhone.value);
  });
}

if (cotacaoPlate) {
  cotacaoPlate.addEventListener('input', () => {
    cotacaoPlate.value = cotacaoPlate.value
      .toUpperCase()
      .replace(/[^A-Z0-9]/g, '')
      .slice(0, 8);
  });
}

cotacaoZeroKmInputs.forEach((input) => {
  input.addEventListener('change', syncPublicQuoteZeroKm);
});
syncPublicQuoteZeroKm();

if (cotacaoForm && cotacaoFormMessage) {
  cotacaoForm.addEventListener('submit', (event) => {
    event.preventDefault();
    const data = new FormData(cotacaoForm);
    const params = new URLSearchParams({
      origem: 'site',
      nome: String(data.get('Nome') || '').trim(),
      whatsapp: String(data.get('Telefone') || '').replace(/\D/g, ''),
      placa: publicQuoteIsZeroKm() ? '' : String(data.get('Placa') || '').trim().toUpperCase(),
      zeroKm: String(publicQuoteIsZeroKm())
    });
    setFormMessage(cotacaoFormMessage, 'Abrindo sua cotação...', 'loading');
    window.location.href = `/cota/?${params.toString()}`;
  });
}

cotacaoTriggers.forEach((trigger) => {
  trigger.addEventListener('click', openCotacaoModal);
});

cotacaoCloseButtons.forEach((button) => {
  button.addEventListener('click', closeCotacaoModal);
});

document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && cotacaoModal?.classList.contains('open')) {
    closeCotacaoModal();
  }
});

const contactForm = document.querySelector('#contactForm');
const contactFormMessage = document.querySelector('#contactFormMessage');

if (contactForm && contactFormMessage) {
  contactForm.addEventListener('submit', (event) => {
    event.preventDefault();
    sendStaticForm(
      contactForm,
      contactFormMessage,
      'Dados enviados com sucesso! Nossa equipe entrará em contato em breve.'
    );
  });
}

// Consulta protegida: CPF/CNPJ + senha, sem revelar cobranças a terceiros.
const boletoBuscaForm = document.querySelector('#boletoBuscaForm');
const boletoDocumento = document.querySelector('#boletoDocumento');
const boletoSenha = document.querySelector('#boletoSenha');
const boletoFormMessage = document.querySelector('#boletoFormMessage');
const boletoAssociadoArea = document.querySelector('#boletoAssociadoArea');
const boletoAssociadoNome = document.querySelector('#boletoAssociadoNome');
const boletoBoletosArea = document.querySelector('#boletoBoletosArea');
const boletoResumo = document.querySelector('#boletoResumo');
const boletoList = document.querySelector('#boletoList');
const boletoNovaBusca = document.querySelector('#boletoNovaBusca');

let boletoState = {
  documento: '',
  associado: null,
  boletos: [],
};

function onlyDigits(value) {
  return String(value || '').replace(/\D/g, '');
}

function formatDocument(value) {
  const digits = onlyDigits(value).slice(0, 14);

  if (digits.length <= 11) {
    return digits
      .replace(/^(\d{3})(\d)/, '$1.$2')
      .replace(/^(\d{3})\.(\d{3})(\d)/, '$1.$2.$3')
      .replace(/\.(\d{3})(\d)/, '.$1-$2');
  }

  return digits
    .replace(/^(\d{2})(\d)/, '$1.$2')
    .replace(/^(\d{2})\.(\d{3})(\d)/, '$1.$2.$3')
    .replace(/\.(\d{3})(\d)/, '.$1/$2')
    .replace(/(\d{4})(\d)/, '$1-$2');
}

function setBoletoMessage(text, type = '') {
  if (!boletoFormMessage) return;
  boletoFormMessage.textContent = text;
  boletoFormMessage.dataset.status = type;
}

function setElementHidden(element, hidden = true) {
  if (!element) return;
  element.hidden = hidden;
}

function resetBoletoFlow() {
  boletoState = {
    documento: '',
    associado: null,
    boletos: [],
  };

  if (boletoBuscaForm) boletoBuscaForm.reset();
  if (boletoList) boletoList.innerHTML = '';
  if (boletoAssociadoNome) boletoAssociadoNome.textContent = 'Associado';
  if (boletoResumo) boletoResumo.textContent = 'Selecione o boleto desejado para abrir/imprimir.';

  setElementHidden(boletoAssociadoArea, true);
  setElementHidden(boletoBoletosArea, true);
  setBoletoMessage('', '');
  boletoDocumento?.focus();
}

function parseCurrencyNumber(value) {
  if (value === undefined || value === null || value === '') return null;

  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : null;
  }

  const raw = String(value)
    .trim()
    .replace(/R\$/gi, '')
    .replace(/BRL/gi, '')
    .replace(/\s/g, '');

  if (!raw) return null;

  const hasComma = raw.includes(',');
  const hasDot = raw.includes('.');
  let normalized = raw.replace(/[^0-9,.-]/g, '');

  if (hasComma && hasDot) {
    // Decide pelo último separador: 1.234,56 = pt-BR | 1234.56 = en-US/API
    if (normalized.lastIndexOf(',') > normalized.lastIndexOf('.')) {
      normalized = normalized.replace(/\./g, '').replace(',', '.');
    } else {
      normalized = normalized.replace(/,/g, '');
    }
  } else if (hasComma) {
    normalized = normalized.replace(',', '.');
  } else if (hasDot) {
    const parts = normalized.split('.');
    const last = parts.at(-1) || '';

    if (parts.length > 2) {
      // 1.234.567 sem vírgula: trata como separador de milhar.
      normalized = normalized.replace(/\./g, '');
    } else if (last.length === 3 && parts[0].length <= 3) {
      // 8.009 provavelmente veio como milhar, não decimal.
      normalized = normalized.replace(/\./g, '');
    }
    // 80.09 ou 8009.00 continuam como decimal da API.
  }

  const number = Number(normalized);
  return Number.isNaN(number) ? null : number;
}

function formatCurrency(value) {
  if (value === undefined || value === null || value === '') return '';

  const raw = String(value).trim();
  if (!raw) return '';

  const number = parseCurrencyNumber(value);
  if (number === null) return raw;

  return number.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}

function formatDate(value) {
  if (!value) return '';
  const raw = String(value).trim();

  if (/^\d{2}\/\d{2}\/\d{4}$/.test(raw)) return raw;

  const date = new Date(raw.includes('T') ? raw : `${raw}T12:00:00`);
  if (Number.isNaN(date.getTime())) return raw;

  return date.toLocaleDateString('pt-BR');
}

function getBoletoTitle(boleto, index) {
  if (boleto.tipoExibicao === 'placa_cancelada') {
    return boleto.placa ? `Placa ${boleto.placa}` : (boleto.veiculo || `Placa ${index + 1}`);
  }

  const placa = boleto.placa ? `Placa ${boleto.placa}` : `Boleto ${index + 1}`;
  const veiculo = boleto.veiculo && !String(boleto.veiculo).includes(placa)
    ? ` — ${boleto.veiculo}`
    : '';
  return `${placa}${veiculo}`;
}

function getBoletoLabel(boleto) {
  if (boleto.status === 'Pago') return 'Pagamento confirmado';
  if (boleto.status === 'Vencido') return 'Boleto vencido';
  if (boleto.tipoExibicao === 'placa_cancelada') return 'Placa cancelada';
  if (boleto.tipoExibicao === 'placa_inativa') return 'Placa inativa';
  if (boleto.tipoExibicao === 'boleto_vencido') return 'Boleto vencido';
  return boleto.disponivel === false ? 'Necessário atualizar' : 'Boleto disponível';
}

async function copyToClipboard(text) {
  if (!text) throw new Error('Código de barras não disponível.');

  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }

  const input = document.createElement('textarea');
  input.value = text;
  input.setAttribute('readonly', '');
  input.style.position = 'fixed';
  input.style.left = '-9999px';
  document.body.appendChild(input);
  input.select();
  document.execCommand('copy');
  input.remove();
}

function createBoletoItem(boleto, index) {
  const item = document.createElement('article');
  item.className = `boleto-item${boleto.disponivel === false ? ' boleto-item--blocked' : ''}`;

  const info = document.createElement('div');
  info.className = 'boleto-item__info';

  const label = document.createElement('small');
  label.textContent = getBoletoLabel(boleto);

  const title = document.createElement('strong');
  title.textContent = getBoletoTitle(boleto, index);

  const details = document.createElement('span');
  const parts = [];
  if (boleto.valor) parts.push(`Valor: ${formatCurrency(boleto.valor)}`);
  if (boleto.vencimento) parts.push(`Vencimento: ${formatDate(boleto.vencimento)}`);
  if (boleto.status) parts.push(`Status: ${boleto.status}`);
  details.textContent = parts.length ? parts.join(' | ') : 'Informações disponíveis para consulta.';

  info.append(label, title, details);

  if (boleto.codigoBarras || boleto.linhaDigitavel) {
    const code = document.createElement('code');
    code.textContent = boleto.linhaDigitavel || boleto.codigoBarras;
    info.appendChild(code);
  }

  if (boleto.disponivel === false) {
    const blocked = document.createElement('p');
    blocked.className = 'boleto-block-message';
    blocked.textContent = boleto.mensagem || 'Este boleto não está disponível para emissão pelo site. Entre em contato com o setor financeiro para atualizar a segunda via.';
    info.appendChild(blocked);
  }

  const actions = document.createElement('div');
  actions.className = 'boleto-item__actions';

  const boletoUrl = (() => {
    try { const u = new URL(String(boleto.pdf || '')); return u.protocol === 'https:' ? u.href : null; }
    catch (_) { return null; }
  })();
  if (boletoUrl && boleto.disponivel !== false) {
    const openLink = document.createElement('a');
    openLink.className = 'primary-button';
    // URL entregue pela financeira: nunca usar proxy de download com URL arbitrária (SSRF).
    openLink.href = boletoUrl;
    openLink.target = '_blank';
    openLink.rel = 'noopener';
    openLink.textContent = 'Baixar boleto';
    actions.appendChild(openLink);
  }

  if ((boleto.codigoBarras || boleto.linhaDigitavel) && boleto.disponivel !== false) {
    const copyButton = document.createElement('button');
    copyButton.className = 'secondary-button';
    copyButton.type = 'button';
    copyButton.textContent = 'Copiar código';
    copyButton.addEventListener('click', async () => {
      try {
        await copyToClipboard(boleto.linhaDigitavel || boleto.codigoBarras);
        setBoletoMessage('Código copiado com sucesso.', 'success');
      } catch (error) {
        setBoletoMessage(error.message || 'Não foi possível copiar o código.', 'error');
      }
    });
    actions.appendChild(copyButton);
  }

  if (boleto.disponivel === false) {
    const callLink = document.createElement('a');
    callLink.className = 'primary-button boleto-call-button';
    callLink.href = 'tel:08005900656';
    callLink.setAttribute('aria-label', 'Ligar para o financeiro da Empresa Cliente no 0800 590 0656');
    callLink.innerHTML = '<i class="fa-solid fa-phone" aria-hidden="true"></i> Ligar 0800 590 0656 - opção 2';
    actions.appendChild(callLink);
  }

  if (!actions.children.length && boleto.disponivel !== false) {
    const empty = document.createElement('span');
    empty.className = 'boleto-empty-state';
    empty.textContent = 'A API retornou o boleto, mas não enviou PDF ou código de barras reconhecido.';
    actions.appendChild(empty);
  }

  item.append(info, actions);
  return item;
}

function renderBoletos(boletos = []) {
  if (!boletoList) return;
  boletoList.innerHTML = '';

  if (!boletos.length) {
    const empty = document.createElement('p');
    empty.className = 'boleto-empty-state';
    empty.textContent = 'Nenhum boleto disponível foi encontrado para este CPF/CNPJ.';
    boletoList.appendChild(empty);
    return;
  }

  boletos.forEach((boleto, index) => {
    boletoList.appendChild(createBoletoItem(boleto, index));
  });
}

if (boletoDocumento) {
  boletoDocumento.addEventListener('input', () => {
    boletoDocumento.value = formatDocument(boletoDocumento.value);
  });
}

if (boletoNovaBusca) {
  boletoNovaBusca.addEventListener('click', resetBoletoFlow);
}

if (boletoBuscaForm) {
  boletoBuscaForm.addEventListener('submit', async (event) => {
    event.preventDefault();

    const submitButton = boletoBuscaForm.querySelector('button[type="submit"]');
    const originalButtonText = submitButton ? submitButton.textContent : 'Consultar boletos';
    const documento = onlyDigits(boletoDocumento?.value || '');

    if (documento.length !== 11 && documento.length !== 14) {
      setBoletoMessage('Informe um CPF ou CNPJ válido.', 'error');
      boletoDocumento?.focus();
      return;
    }

    try {
      boletoState.documento = documento;
      setBoletoMessage('Consultando boletos do associado...', 'loading');
      setElementHidden(boletoAssociadoArea, true);
      setElementHidden(boletoBoletosArea, true);
      if (boletoList) boletoList.innerHTML = '';

      if (submitButton) {
        submitButton.disabled = true;
        submitButton.textContent = 'Consultando...';
      }

      const endpoint = path => window.NH_API?.backend ? window.NH_API.backend(path) : path;
      const loginResponse = await fetch(endpoint('/api/auth/login'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: documento, password: boletoSenha?.value || '' }),
      });
      if (boletoSenha) boletoSenha.value = '';
      const login = await loginResponse.json().catch(() => ({}));
      if (!loginResponse.ok || login.role !== 'ASSOCIATE' || !login.token) {
        throw new Error('CPF/CNPJ ou senha inválidos. Entre pela Área do Associado caso seja seu primeiro acesso.');
      }
      if (login.passwordChangeRequired) {
        throw new Error('Altere sua senha no primeiro acesso à Área do Associado antes de consultar boletos.');
      }
      const response = await fetch(endpoint('/api/associate-portal/second-copy'), {
        method: 'GET',
        headers: { Authorization: `Bearer ${login.token}` },
        cache: 'no-store'
      });

      const data = await response.json().catch(() => ({}));

      if (!response.ok) {
        throw new Error(data.message || 'Não foi possível consultar os boletos do associado.');
      }

      boletoState.associado = data.associado || null;
      boletoState.boletos = Array.isArray(data.boletos) ? data.boletos : [];

      if (boletoAssociadoNome) {
        boletoAssociadoNome.textContent = data.associado?.nome || 'Associado localizado';
      }

      if (boletoResumo) {
        boletoResumo.textContent = boletoState.boletos.length
          ? `${boletoState.boletos.length} resultado(s) encontrado(s). Confira placa, valor e vencimento antes de baixar.`
          : (data.financeiraConfigurada
            ? 'Nenhum boleto emitido foi encontrado para este cadastro.'
            : 'A financeira ainda não está conectada. Nenhum boleto emitido está disponível.');
      }

      renderBoletos(boletoState.boletos);
      setElementHidden(boletoAssociadoArea, false);
      setElementHidden(boletoBoletosArea, false);
      setBoletoMessage('Consulta realizada com sucesso.', 'success');
    } catch (error) {
      setBoletoMessage(
        error.message || 'Não foi possível buscar os boletos agora. Tente novamente em instantes.',
        'error'
      );
    } finally {
      if (submitButton) {
        submitButton.disabled = false;
        submitButton.textContent = originalButtonText;
      }
    }
  });
}
