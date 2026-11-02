import express from 'express';

const app = express();
app.disable('x-powered-by');
app.use(express.json({ limit: '1mb' }));
app.get('/health', (_req, res) => res.type('text').send('ok'));
// Rotas legadas removidas: consulta somente por CPF e proxy de PDF sem autenticação.
// A segunda via protegida reside no backend Java da Base Universal.
app.all('/api/consultar-boletos-associado', (_req, res) => res.status(410).json({ message: 'Use a Área do Associado autenticada.' }));
app.all('/api/baixar-boleto', (_req, res) => res.status(410).json({ message: 'Use a Área do Associado autenticada.' }));
app.use((error, _req, res, _next) => {
  console.error(error);
  res.status(500).json({ message: 'Erro interno na integração de boletos.' });
});
const port = Number(process.env.PORT || 3001);
app.listen(port, '0.0.0.0', () => console.log(`Hinova proxy ativo na porta ${port}`));
