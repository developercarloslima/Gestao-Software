export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  return res.status(410).json({ message: 'Use a Área do Associado autenticada para consultar a segunda via.' });
}
