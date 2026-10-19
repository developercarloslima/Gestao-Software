import { proxyToHinova } from './_proxy.js';

export default async function handler(req, res) {
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST');
    return res.status(405).json({ message: 'Método não permitido.' });
  }

  return proxyToHinova(req, res, '/api/consultar-boletos-associado');
}
