function isAllowedBoletoUrl(rawUrl = '') {
  try {
    const url = new URL(String(rawUrl));
    return url.protocol === 'https:' && (
      url.hostname === 'short.hinova.com.br' ||
      url.hostname === 'api.hinova.com.br' ||
      url.hostname.endsWith('.hinova.com.br')
    );
  } catch {
    return false;
  }
}

export default async function handler(req, res) {
  if (req.method !== 'GET') {
    return res.status(405).json({ message: 'Método não permitido.' });
  }

  const boletoUrl = req.query?.url;

  if (!boletoUrl || !isAllowedBoletoUrl(boletoUrl)) {
    return res.status(400).json({ message: 'URL de boleto inválida.' });
  }

  return res.redirect(302, boletoUrl);
}
