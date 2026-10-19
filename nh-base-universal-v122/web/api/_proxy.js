const ALLOWED_RESPONSE_HEADERS = [
  'cache-control',
  'content-disposition',
  'content-length',
  'content-type',
  'location'
];

function getHinovaProxyBaseUrl() {
  const value = String(process.env.HINOVA_RENDER_URL || '')
    .trim()
    .replace(/\/+$/, '');

  if (!value) {
    const error = new Error('HINOVA_RENDER_URL não foi configurada na Vercel.');
    error.status = 500;
    throw error;
  }

  let url;
  try {
    url = new URL(value);
  } catch {
    const error = new Error('HINOVA_RENDER_URL possui formato inválido.');
    error.status = 500;
    throw error;
  }

  if (url.protocol !== 'https:') {
    const error = new Error('HINOVA_RENDER_URL precisa começar com https://.');
    error.status = 500;
    throw error;
  }

  return value;
}

function serializeRequestBody(req) {
  if (req.body === undefined || req.body === null) return undefined;
  if (Buffer.isBuffer(req.body)) return req.body;
  if (typeof req.body === 'string') return req.body;
  return JSON.stringify(req.body);
}

function copyResponseHeaders(upstream, res) {
  for (const name of ALLOWED_RESPONSE_HEADERS) {
    const value = upstream.headers.get(name);
    if (value) res.setHeader(name, value);
  }
}

export async function proxyToHinova(req, res, upstreamPath) {
  try {
    const baseUrl = getHinovaProxyBaseUrl();
    const incomingUrl = new URL(req.url || '/', 'https://nh-plataforma.local');
    const targetUrl = new URL(upstreamPath, `${baseUrl}/`);
    targetUrl.search = incomingUrl.search;

    const method = String(req.method || 'GET').toUpperCase();
    const headers = {
      accept: req.headers.accept || '*/*'
    };

    if (req.headers['content-type']) {
      headers['content-type'] = req.headers['content-type'];
    }

    const upstream = await fetch(targetUrl, {
      method,
      headers,
      body: method === 'GET' || method === 'HEAD' ? undefined : serializeRequestBody(req),
      redirect: 'manual',
      signal: AbortSignal.timeout(55_000)
    });

    copyResponseHeaders(upstream, res);
    res.status(upstream.status);

    if (method === 'HEAD' || upstream.status === 204 || upstream.status === 304) {
      return res.end();
    }

    const payload = Buffer.from(await upstream.arrayBuffer());
    return res.send(payload);
  } catch (error) {
    console.error('Falha no proxy Vercel → Hinova Render:', error);
    const status = Number(error?.status) >= 400 ? Number(error.status) : 502;
    return res.status(status).json({
      message: status === 500
        ? error.message
        : 'Não foi possível acessar o serviço de boletos. Tente novamente em instantes.'
    });
  }
}
