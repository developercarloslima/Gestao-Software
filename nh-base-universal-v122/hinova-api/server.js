import express from 'express';
import consultar from './consultar-boletos-associado.js';
import baixar from './baixar-boleto.js';

const app = express();
app.disable('x-powered-by');
app.use(express.json({ limit: '1mb' }));
app.get('/health', (_req, res) => res.type('text').send('ok'));
app.post('/api/consultar-boletos-associado', consultar);
app.get('/api/baixar-boleto', baixar);
app.use((error, _req, res, _next) => {
  console.error(error);
  res.status(500).json({ message: 'Erro interno na integração de boletos.' });
});
const port = Number(process.env.PORT || 3001);
app.listen(port, '0.0.0.0', () => console.log(`Hinova proxy ativo na porta ${port}`));
