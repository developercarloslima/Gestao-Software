const DEFAULT_API_BASE_URL = 'https://api.hinova.com.br/api/sga/v2';
const DEFAULT_ERROR_MESSAGE = 'Não foi possível consultar os boletos na API oficial da Hinova.';
const UPDATE_MESSAGE = 'Este boleto está vencido há mais de 6 dias e não está disponível para emissão pelo site. Por favor, regularize no setor financeiro da Novo Horizonte pelo 0800 590 0656, opção 2.';
const INACTIVE_PLATE_MESSAGE = 'Esta placa está inativa por possuir mais de um boleto vencido há mais de 6 dias. Entre em contato com o financeiro da Novo Horizonte pelo 0800 590 0656, opção 2.';
const CANCELED_PLATE_MESSAGE = 'Esta placa está cancelada. Entre em contato com o atendimento financeiro da Novo Horizonte pelo 0800 590 0656, opção 2.';

let cachedUserToken = '';
let cachedUserTokenCreatedAt = 0;

function getCleanInput(value) {
  return String(value || '').trim();
}

function onlyDigits(value) {
  return getCleanInput(value).replace(/\D/g, '');
}

function normalizePlate(value = '') {
  return String(value || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
}

function isTrue(value) {
  return String(value || '').toLowerCase() === 'true';
}

function getEnv(name, fallback = '') {
  return process.env[name] || fallback;
}

function getBaseUrl() {
  return getEnv('HINOVA_API_BASE_URL', DEFAULT_API_BASE_URL).replace(/\/+$/, '');
}

function buildUrl(path = '') {
  const cleanPath = String(path || '').replace(/^\/+/, '');
  return `${getBaseUrl()}/${cleanPath}`;
}

function formatDateBr(date) {
  const day = String(date.getDate()).padStart(2, '0');
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const year = date.getFullYear();
  return `${day}/${month}/${year}`;
}

function addDays(date, days) {
  const next = new Date(date.getFullYear(), date.getMonth(), date.getDate(), 12);
  next.setDate(next.getDate() + days);
  return next;
}

function parseDate(value) {
  if (!value) return null;
  const raw = String(value).trim();

  if (/^\d{2}\/\d{2}\/\d{4}$/.test(raw)) {
    const [day, month, year] = raw.split('/').map(Number);
    return new Date(year, month - 1, day, 12, 0, 0);
  }

  if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) {
    const [year, month, day] = raw.split('-').map(Number);
    return new Date(year, month - 1, day, 12, 0, 0);
  }

  const date = new Date(raw.includes('T') ? raw : `${raw}T12:00:00`);
  return Number.isNaN(date.getTime()) ? null : date;
}

function daysAfterDueDate(dueDate, currentDate = new Date()) {
  const due = new Date(dueDate.getFullYear(), dueDate.getMonth(), dueDate.getDate(), 12);
  const today = new Date(currentDate.getFullYear(), currentDate.getMonth(), currentDate.getDate(), 12);
  if (today <= due) return 0;

  const oneDayMs = 24 * 60 * 60 * 1000;
  return Math.floor((today.getTime() - due.getTime()) / oneDayMs);
}

function getBoletoOverdueDays(boleto = {}) {
  const apiDays = Number(boleto.quantidade_dias_vencidos || 0);
  if (Number.isFinite(apiDays) && apiDays > 0) return apiDays;

  const dueDate = parseDate(boleto.data_vencimento || boleto.data_vencimento_original);
  if (!dueDate) return 0;
  return daysAfterDueDate(dueDate);
}

function getBoletoMaxDaysAfterDue() {
  const value = Number(process.env.BOLETO_MAX_DAYS_AFTER_DUE || process.env.BOLETO_MAX_BUSINESS_DAYS_AFTER_DUE || 6);
  return Number.isFinite(value) && value >= 0 ? value : 6;
}

function getSearchWindowDays(name, fallback) {
  const value = Number(process.env[name] || fallback);
  return Number.isFinite(value) && value >= 0 ? value : fallback;
}

function buildDateRanges() {
  const pastDays = getSearchWindowDays('BOLETO_SEARCH_DAYS_PAST', 180);
  const futureDays = getSearchWindowDays('BOLETO_SEARCH_DAYS_FUTURE', 420);
  // A API oficial aceita no máximo 200 dias por consulta.
  // Usamos 180 dias para deixar margem de segurança e evitar 406 por intervalo inválido.
  const configuredChunkDays = Number(process.env.BOLETO_SEARCH_CHUNK_DAYS || 180);
  const chunkDays = Math.min(Math.max(configuredChunkDays, 1), 180);

  const today = new Date();
  const start = addDays(today, -pastDays);
  const end = addDays(today, futureDays);
  const ranges = [];
  let current = start;

  while (current <= end) {
    const rangeEnd = addDays(current, chunkDays);
    const finalEnd = rangeEnd > end ? end : rangeEnd;
    ranges.push({ start: new Date(current), end: new Date(finalEnd) });
    current = addDays(finalEnd, 1);
  }

  return ranges;
}

function normalizeArray(value) {
  if (Array.isArray(value)) return value;
  if (!value) return [];
  if (Array.isArray(value.boletos)) return value.boletos;
  if (Array.isArray(value.data)) return value.data;
  if (typeof value === 'object') return [value];
  return [];
}

function getInitialApiToken() {
  return process.env.HINOVA_API_TOKEN || process.env.HINOVA_TOKEN || '';
}

function hasPlaceholder(value = '') {
  return /COLE_|SEU_|SUA_|TOKEN_AQUI|USUARIO_AQUI|SENHA_AQUI/i.test(String(value || ''));
}

async function parseJsonResponse(response) {
  const text = await response.text();
  let data = null;

  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }

  if (!response.ok) {
    const error = new Error(data?.mensagem || data?.message || `API Hinova retornou HTTP ${response.status}.`);
    error.status = response.status;
    error.data = data;
    error.details = data ? JSON.stringify(data) : text.slice(0, 3000);
    throw error;
  }

  return data;
}

async function authenticateUser() {
  if (process.env.HINOVA_API_USER_TOKEN && !hasPlaceholder(process.env.HINOVA_API_USER_TOKEN)) {
    return process.env.HINOVA_API_USER_TOKEN;
  }

  const ttlMs = Number(process.env.HINOVA_API_TOKEN_CACHE_MS || 24 * 60 * 60 * 1000);
  if (cachedUserToken && Date.now() - cachedUserTokenCreatedAt < ttlMs) {
    return cachedUserToken;
  }

  const apiToken = getInitialApiToken();
  const usuario = process.env.HINOVA_API_USER || process.env.HINOVA_LOGIN_USER;
  const senha = process.env.HINOVA_API_PASSWORD || process.env.HINOVA_LOGIN_PASSWORD;

  if (!apiToken || hasPlaceholder(apiToken)) {
    const error = new Error('Configure HINOVA_API_TOKEN com o token oficial gerado no SGA.');
    error.status = 500;
    throw error;
  }

  if (!usuario || !senha || hasPlaceholder(usuario) || hasPlaceholder(senha)) {
    const error = new Error('Configure HINOVA_API_USER e HINOVA_API_PASSWORD com o usuário de integração da API.');
    error.status = 500;
    throw error;
  }

  const response = await fetch(buildUrl('/usuario/autenticar'), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${apiToken}`,
    },
    body: JSON.stringify({ usuario, senha }),
  });

  const data = await parseJsonResponse(response);
  const token = data?.token_usuario || data?.token || data?.access_token || '';

  if (!token) {
    const error = new Error('A API autenticou, mas não retornou token_usuario.');
    error.status = 401;
    error.details = JSON.stringify(data).slice(0, 1600);
    throw error;
  }

  cachedUserToken = token;
  cachedUserTokenCreatedAt = Date.now();
  return token;
}

async function apiRequest(path, { method = 'GET', body = undefined } = {}) {
  const token = await authenticateUser();
  const response = await fetch(buildUrl(path), {
    method,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  });

  return parseJsonResponse(response);
}

function makePathFromTemplate(template, values) {
  return String(template || '')
    .replace(/\{documento\}/g, encodeURIComponent(values.documento || ''))
    .replace(/\{cpf\}/g, encodeURIComponent(values.documento || ''))
    .replace(/\{cnpj\}/g, encodeURIComponent(values.documento || ''))
    .replace(/\{senha\}/g, encodeURIComponent(values.senha || ''));
}

async function fetchAssociadoByConfiguredEndpoint(documento) {
  const attempts = [];
  const customPath = process.env.HINOVA_ASSOCIADO_CPF_PATH;

  if (customPath && !hasPlaceholder(customPath)) {
    attempts.push(makePathFromTemplate(customPath, { documento }));
  }

  // Rota oficial da documentação que busca o associado por CPF validando as permissões do usuário/token.
  // Ela ajuda a trazer todas as placas do associado, inclusive placas canceladas sem boleto no período.
  attempts.push(`/associado/buscar-por-permissao/${encodeURIComponent(documento)}/cpf`);

  const senha = process.env.HINOVA_ASSOCIADO_DEFAULT_PASSWORD || process.env.HINOVA_ASSOCIADO_SENHA;
  if (senha && !hasPlaceholder(senha)) {
    attempts.push(`/associado/buscar-por-cpf-senha/${encodeURIComponent(documento)}/${encodeURIComponent(senha)}`);
  }

  let lastError = null;
  for (const path of [...new Set(attempts.filter(Boolean))]) {
    try {
      return await apiRequest(path);
    } catch (error) {
      lastError = error;
      if (isTrue(process.env.HINOVA_DEBUG_RESPONSE)) {
        console.warn(`Falha ao buscar associado em ${path}:`, error.message);
      }
    }
  }

  if (isTrue(process.env.HINOVA_DEBUG_RESPONSE) && lastError) {
    console.warn('Nenhuma rota de associado retornou dados:', lastError.message);
  }

  return null;
}

function flattenText(value, output = []) {
  if (value === null || value === undefined) return output;

  if (typeof value === 'string') {
    output.push(value);
    try {
      const parsed = JSON.parse(value);
      if (parsed !== value) flattenText(parsed, output);
    } catch {
      // String comum, sem JSON interno.
    }
    return output;
  }

  if (Array.isArray(value)) {
    value.forEach((item) => flattenText(item, output));
    return output;
  }

  if (typeof value === 'object') {
    Object.values(value).forEach((item) => flattenText(item, output));
    return output;
  }

  output.push(String(value));
  return output;
}

function normalizeSearchText(value = '') {
  return String(value || '')
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '');
}

function getErrorSearchText(error) {
  const pieces = flattenText([
    error?.message,
    error?.details,
    error?.data,
    error?.stack,
  ]);

  return normalizeSearchText(pieces.join(' '));
}

function isNoBoletoInRangeError(error) {
  const text = getErrorSearchText(error);

  return Number(error?.status) === 406 && (
    text.includes('nao foram encontrados boletos') ||
    text.includes('nao foi encontrado boleto') ||
    text.includes('dentro dos parametros enviados')
  );
}

async function fetchBoletosByCpf(documento) {
  const ranges = buildDateRanges();
  const results = [];
  const ignoredEmptyRanges = [];

  for (const range of ranges) {
    const payload = {
      cpf_associado: documento,
      data_vencimento_inicial: formatDateBr(range.start),
      data_vencimento_final: formatDateBr(range.end),
      link_boleto: true,
    };

    try {
      const data = await apiRequest('/listar/boleto-associado-veiculo', {
        method: 'POST',
        body: payload,
      });

      results.push(...normalizeArray(data));
    } catch (error) {
      if (isNoBoletoInRangeError(error)) {
        ignoredEmptyRanges.push({
          inicio: payload.data_vencimento_inicial,
          fim: payload.data_vencimento_final,
        });
        continue;
      }

      throw error;
    }
  }

  if (isTrue(process.env.HINOVA_DEBUG_RESPONSE) && ignoredEmptyRanges.length) {
    console.warn('Intervalos sem boletos ignorados:', ignoredEmptyRanges);
  }

  return dedupeBoletos(results);
}

function boletoUniqueKey(boleto = {}) {
  const vehicle = normalizeArray(boleto.veiculos)[0] || {};
  return [
    boleto.codigo_boleto || boleto.nosso_numero || '',
    vehicle.codigo_veiculo || vehicle.placa || '',
    boleto.data_vencimento || boleto.data_vencimento_original || '',
    boleto.valor_boleto || '',
  ].join('|');
}

function dedupeBoletos(boletos = []) {
  const map = new Map();
  for (const boleto of boletos) {
    const key = boletoUniqueKey(boleto);
    if (!key.replace(/\|/g, '')) continue;
    if (!map.has(key)) map.set(key, boleto);
  }
  return [...map.values()];
}

function getAssociadoFromData(documento, associadoData, boletos) {
  const firstBoleto = boletos[0] || {};
  const firstVehicle = normalizeArray(firstBoleto.veiculos)[0] || {};
  const source = Array.isArray(associadoData) ? associadoData[0] : associadoData;

  return {
    id: source?.codigo_associado || firstBoleto.codigo_associado || firstVehicle.codigo_associado || '',
    nome: source?.nome || firstBoleto.nome_associado || firstVehicle.nome || 'Associado localizado',
    documento: onlyDigits(source?.cpf || firstBoleto.cpf || documento),
    status: source?.descricao_situacao || source?.situacao || '',
  };
}

function extractVehiclesFromAssociado(associadoData) {
  const source = Array.isArray(associadoData) ? associadoData[0] : associadoData;
  return normalizeArray(source?.veiculos);
}

function extractVehiclesFromBoletos(boletos = []) {
  const map = new Map();

  for (const boleto of boletos) {
    for (const vehicle of normalizeArray(boleto.veiculos)) {
      const plate = normalizePlate(vehicle.placa);
      const id = String(vehicle.codigo_veiculo || '');
      const key = plate || id;
      if (!key) continue;

      if (!map.has(key)) {
        map.set(key, {
          ...vehicle,
          placa: plate || vehicle.placa,
          codigo_veiculo: id || vehicle.codigo_veiculo,
          descricao_situacao: vehicle.situacao_veiculo || vehicle.descricao_situacao_veiculo || vehicle.descricao_situacao || '',
        });
      }
    }
  }

  return [...map.values()];
}

function mergeVehicles(...vehicleLists) {
  const map = new Map();

  for (const list of vehicleLists) {
    for (const vehicle of normalizeArray(list)) {
      const plate = normalizePlate(vehicle.placa);
      const id = String(vehicle.codigo_veiculo || '');
      const key = plate || id;
      if (!key) continue;

      const current = map.get(key) || {};
      map.set(key, {
        ...current,
        ...vehicle,
        placa: plate || current.placa || vehicle.placa,
        codigo_veiculo: id || current.codigo_veiculo || vehicle.codigo_veiculo,
        descricao_situacao: vehicle.descricao_situacao || vehicle.descricao_situacao_veiculo || vehicle.situacao_veiculo || current.descricao_situacao || '',
      });
    }
  }

  return [...map.values()];
}

function statusText(value = '') {
  return String(value || '').toUpperCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
}

function isCanceledVehicle(vehicle = {}) {
  const text = statusText([
    vehicle.descricao_situacao,
    vehicle.descricao_situacao_veiculo,
    vehicle.situacao_veiculo,
    vehicle.situacao,
    vehicle.status,
  ].filter(Boolean).join(' '));

  return /CANCEL|EXCLUID|REMOVID|BAIXAD/.test(text);
}

function isBoletoPagoOuCancelado(boleto = {}) {
  const text = statusText([
    boleto.situacao_boleto,
    boleto.descricao_situacao_boleto,
    boleto.status,
  ].filter(Boolean).join(' '));

  if (/PAGO|BAIXAD|CANCEL|EXCLUID|LIQUIDAD/.test(text)) return true;
  if (boleto.data_pagamento && String(boleto.data_pagamento).trim()) return true;
  if (Number(boleto.valor_pagamento || 0) > 0 && /PAGO|BAIXAD|LIQUIDAD/.test(text)) return true;
  return false;
}

function getBoletoVehicleRecords(boleto = {}) {
  const vehicles = normalizeArray(boleto.veiculos);
  if (!vehicles.length) return [{ boleto, vehicle: {} }];
  return vehicles.map((vehicle) => ({ boleto, vehicle }));
}

function convertBoletoToView(boleto = {}, vehicle = {}, overrides = {}) {
  const plate = normalizePlate(vehicle.placa) || normalizePlate(overrides.placa);
  const line = boleto.linha_digitavel || boleto.linhaDigitavel || boleto.codigo_barras || '';
  const pdf = boleto.link_boleto || boleto.link_pdf || boleto.pdf || '';
  const status = boleto.situacao_boleto || boleto.descricao_situacao_boleto || boleto.status || '';
  const modelo = vehicle.modelo || vehicle.descricao_modelo || '';
  const marca = vehicle.marca || '';
  const veiculo = [marca, modelo].filter(Boolean).join(' ').trim();

  return {
    id: String(boleto.codigo_boleto || boleto.nosso_numero || overrides.id || ''),
    numero: String(boleto.nosso_numero || boleto.codigo_boleto || ''),
    nossoNumero: String(boleto.nosso_numero || ''),
    valor: boleto.valor_boleto_multa_mora || boleto.valor_boleto || boleto.valor || '',
    vencimento: boleto.data_vencimento || boleto.data_vencimento_original || '',
    vencimentoOriginal: boleto.data_vencimento_original || '',
    emissao: boleto.data_emissao || '',
    status,
    tipo: boleto.tipo_boleto || boleto.descricao_tipo_boleto || '',
    parcela: boleto.parcela_paga && boleto.qtde_parcela_carne ? `${boleto.parcela_paga}/${boleto.qtde_parcela_carne}` : '',
    placa: plate,
    veiculo,
    linhaDigitavel: line,
    codigoBarras: line,
    pdf,
    pix: boleto.pix || null,
    disponivel: overrides.disponivel ?? true,
    tipoExibicao: overrides.tipoExibicao || '',
    mensagem: overrides.mensagem || '',
    diasVencidos: getBoletoOverdueDays(boleto),
  };
}

function createCanceledPlateItem(vehicle = {}) {
  const plate = normalizePlate(vehicle.placa);
  const modelo = vehicle.modelo || vehicle.descricao_modelo || '';
  const marca = vehicle.marca || '';

  return {
    id: `placa-cancelada-${plate || vehicle.codigo_veiculo || Math.random().toString(36).slice(2)}`,
    placa: plate,
    veiculo: [marca, modelo].filter(Boolean).join(' ').trim(),
    status: vehicle.descricao_situacao || vehicle.descricao_situacao_veiculo || vehicle.situacao_veiculo || 'CANCELADA',
    disponivel: false,
    tipoExibicao: 'placa_cancelada',
    mensagem: CANCELED_PLATE_MESSAGE,
  };
}

function sortBoletosByDueDate(a, b) {
  const dateA = parseDate(a.boleto?.data_vencimento || a.boleto?.data_vencimento_original);
  const dateB = parseDate(b.boleto?.data_vencimento || b.boleto?.data_vencimento_original);
  const timeA = dateA ? dateA.getTime() : Number.MAX_SAFE_INTEGER;
  const timeB = dateB ? dateB.getTime() : Number.MAX_SAFE_INTEGER;
  return timeA - timeB;
}

function applyPlateRules({ boletos = [], vehicles = [] } = {}) {
  const maxDays = getBoletoMaxDaysAfterDue();
  const vehicleMap = new Map();
  const groups = new Map();

  for (const vehicle of vehicles) {
    const plate = normalizePlate(vehicle.placa) || String(vehicle.codigo_veiculo || '');
    if (!plate) continue;
    vehicleMap.set(plate, vehicle);
    if (!groups.has(plate)) groups.set(plate, []);
  }

  for (const boleto of boletos) {
    if (isBoletoPagoOuCancelado(boleto)) continue;

    for (const record of getBoletoVehicleRecords(boleto)) {
      const plate = normalizePlate(record.vehicle.placa) || String(record.vehicle.codigo_veiculo || 'sem-placa');
      if (!groups.has(plate)) groups.set(plate, []);
      groups.get(plate).push(record);

      if (!vehicleMap.has(plate) && (record.vehicle.placa || record.vehicle.codigo_veiculo)) {
        vehicleMap.set(plate, record.vehicle);
      }
    }
  }

  const visible = [];
  const regraExibicao = {
    bloqueadoPorBoletoVencido: false,
    placasBloqueadas: [],
    placasCanceladas: [],
    placasInativas: [],
  };

  for (const [plate, records] of groups.entries()) {
    const vehicle = vehicleMap.get(plate) || records[0]?.vehicle || {};

    if (isCanceledVehicle(vehicle)) {
      visible.push(createCanceledPlateItem({ ...vehicle, placa: normalizePlate(vehicle.placa) || plate }));
      regraExibicao.placasCanceladas.push(normalizePlate(vehicle.placa) || plate);
      continue;
    }

    const overdueRecords = records
      .filter((record) => getBoletoOverdueDays(record.boleto) > maxDays)
      .sort(sortBoletosByDueDate);

    if (overdueRecords.length >= 2) {
      const oldest = overdueRecords[0];
      visible.push(convertBoletoToView(oldest.boleto, oldest.vehicle, {
        disponivel: false,
        tipoExibicao: 'placa_inativa',
        mensagem: INACTIVE_PLATE_MESSAGE,
      }));
      regraExibicao.bloqueadoPorBoletoVencido = true;
      regraExibicao.placasInativas.push(normalizePlate(oldest.vehicle.placa) || plate);
      continue;
    }

    if (overdueRecords.length === 1) {
      const overdue = overdueRecords[0];
      visible.push(convertBoletoToView(overdue.boleto, overdue.vehicle, {
        disponivel: false,
        tipoExibicao: 'boleto_vencido',
        mensagem: UPDATE_MESSAGE,
      }));
      regraExibicao.bloqueadoPorBoletoVencido = true;
      regraExibicao.placasBloqueadas.push(normalizePlate(overdue.vehicle.placa) || plate);
      continue;
    }

    records
      .sort(sortBoletosByDueDate)
      .forEach((record) => {
        visible.push(convertBoletoToView(record.boleto, record.vehicle, {
          disponivel: true,
        }));
      });
  }

  const clean = (value) => [...new Set(value.filter(Boolean))];
  regraExibicao.placasBloqueadas = clean(regraExibicao.placasBloqueadas);
  regraExibicao.placasCanceladas = clean(regraExibicao.placasCanceladas);
  regraExibicao.placasInativas = clean(regraExibicao.placasInativas);

  return { boletos: visible, regraExibicao };
}

export default async function handler(req, res) {
  if (req.method !== 'POST') {
    return res.status(405).json({ message: 'Método não permitido.' });
  }

  const documento = onlyDigits(req.body?.documento || req.body?.cpf || req.body?.cnpj);

  if (documento.length !== 11 && documento.length !== 14) {
    return res.status(400).json({ message: 'Informe um CPF ou CNPJ válido.' });
  }

  try {
    const [associadoData, boletosApi] = await Promise.all([
      fetchAssociadoByConfiguredEndpoint(documento).catch((error) => {
        if (isTrue(process.env.HINOVA_DEBUG_RESPONSE)) {
          console.warn('Falha ao buscar associado na API oficial:', error.message);
        }
        return null;
      }),
      fetchBoletosByCpf(documento),
    ]);

    const vehicles = mergeVehicles(
      extractVehiclesFromAssociado(associadoData),
      extractVehiclesFromBoletos(boletosApi)
    );

    const associado = getAssociadoFromData(documento, associadoData, boletosApi);
    const { boletos, regraExibicao } = applyPlateRules({ boletos: boletosApi, vehicles });

    if (!boletos.length && !vehicles.length) {
      return res.status(404).json({
        message: 'Nenhum associado, veículo ou boleto foi localizado para o CPF/CNPJ informado.',
      });
    }

    return res.status(200).json({
      message: 'Consulta realizada com sucesso.',
      origem: 'api-oficial-hinova-sga-v2',
      associado,
      boletos,
      regraExibicao,
    });
  } catch (error) {
    const status = error.status && Number(error.status) >= 400 ? Number(error.status) : 500;
    return res.status(status).json({
      message: error.message || DEFAULT_ERROR_MESSAGE,
      ...(isTrue(process.env.HINOVA_DEBUG_RESPONSE) ? { details: error.details || error.stack } : {}),
    });
  }
}
