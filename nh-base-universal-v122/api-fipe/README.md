# API FIPE independente v1.4 — melhoria de throughput (Windows / 1 worker)

**Contexto real:** em 16/10/2026, após importar 51.247 veículos FIPEX,
os testes no Windows com apenas 1 worker produziram **85,81 req/s** com 32
clientes e **79,26 req/s** com 100 clientes, sem erros HTTP. Esses valores
mostram saturação nesse equipamento e não são um problema na qualidade do dataset.

## Melhorias da v1.4

- A rota `/consulta` agora é assíncrona e retorna bytes JSON serializados com
  `orjson`, evitando reserialização de respostas repetidas.
- Cache HTTP LRU thread-safe com prazo de 3 segundos, até 1.024 entradas de
  no máximo 128 KiB cada. As consultas SQL não cacheadas continuam executando
  num threadpool, sem bloquear o event loop com consultas frias.
- `autorizar` é uma dependência assíncrona. Ela é avaliada **antes** do cache
  HTTP para evitar bypass de `X-API-Key`.
- A resposta de `/consulta` inclui `X-FIPE-Cache: HIT` ou `MISS`, útil para
  medir consultas em cache. O cabeçalho `Cache-Control: no-store` impede cache
  em navegadores e proxies, já que o cache só deve existir no serviço.
- Retirado `ORJSONResponse` como padrão, eliminando avisos de depreciação do
  FastAPI. O formato de `/consulta`, filtros, versão do dataset, sincronizador,
  índices SQLite e a política de rollback foram mantidos.

O cache HTTP pode apresentar valores antigos **por até três segundos** depois
que o sincronizador atualizar um mês. Para desativá-lo, defina
`HTTP_CACHE_TTL_SECONDS=0` em `.env` ou no ambiente **antes** de iniciar o
Uvicorn. Outras opções: `HTTP_CACHE_MAX_ENTRIES` (padrão 1024) e
`HTTP_CACHE_MAX_RESPONSE_BYTES` (padrão 131072).

## Como instalar no Windows (sem substituir o banco)

1. Pare o servidor com `Ctrl+C`.
2. Extraia o patch sobre a **raiz da pasta do projeto**, substituindo
   `app/main.py` e `app/performance.py`. **Não apague `data/` nem `.env`.**
3. Execute na mesma pasta:

```powershell
python -m pytest -q
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1 --no-access-log
```

> Neste computador, use **1 worker no Windows**. A v1.3 mostrou `WinError
> 10022` ao usar 2 workers. Isso não exige reimportar os dados nem recriar os
> índices. Os dois serviços de sincronização continuam independentes.

Abra **outro** terminal PowerShell, na pasta do projeto, e faça:

```powershell
# Resposta da API e indicador do cache HTTP (duas chamadas para testar HIT):
$r1 = Invoke-WebRequest 'http://127.0.0.1:8000/consulta?marca=Toyota&modelo=Corolla&ano=2022&limite=20'
$r2 = Invoke-WebRequest 'http://127.0.0.1:8000/consulta?marca=Toyota&modelo=Corolla&ano=2022&limite=20'
$r1.Headers['X-FIPE-Cache']
$r2.Headers['X-FIPE-Cache']

# Compare o custo HTTP sem consulta SQL com a rota real:
python -m benchmarks.carga --rota /health --duracao 20 --concorrencia 32 --meta 100
python -m benchmarks.carga --duracao 30 --concorrencia 32 --meta 100
python -m benchmarks.carga --duracao 30 --concorrencia 100 --meta 100 --variar-anos
```

O valor de `--meta` é **apenas o critério de aprovação do benchmark**, não
um limite de requisições. Executar benchmark na mesma máquina do servidor
compete por CPU e pode produzir números menores do que testes remotos.

### Evidência de desempenho nesta execução (não no Windows do usuário)

Linux, Python 3.13, base **fictícia** de 51.247 linhas, 1 worker, 32 clientes,
6 segundos por teste. **Medições ilustrativas, não garantias**:

| Cenário | v1.3.2 | v1.4 |
|---|---:|---:|
| Consulta Toyota / Corolla / 2022 repetida | 150,15 req/s | 227,33 req/s |
| Rota `/health` | 213,95 req/s | 214,40 req/s |

O teste local não substitui o benchmark do seu computador com o snapshot FIPEX
real. Consulte `X-FIPE-Cache` para distinguir respostas repetidas e não
interprete essa aceleração como desempenho garantido para todas as marcas,
modelos e anos.

---

## Correção v1.3.2 — Ano-modelo em branco nos registros zero-quilômetro

No log real de 16/10/2026, 1.948/51.247 registros foram rejeitados com
`invalid literal for int() with base 10: ''`. A v1.3.1 reconhecia o código
`ano_modelo=32000`, mas ainda tentava `int('')` se o arquivo trouxesse um
campo numérico em branco.

**O que mudou**

- Se `ano_modelo` estiver vazio E `zero_km=true`, normaliza para o identificador
  técnico `32000`. Isso **não é um ano de fabricação**. Se vier um ano numérico
  para 0 km, ele é preservado.
- Se `ano_modelo` estiver vazio num veículo **não zero km**, o registro continua
  inválido.
- Campos `valor_centavos`, `mes_referencia` e `ano_referencia` vazios continuam
  inválidos: não fabricamos valores FIPE, preços ou referências.
- Mensagens de erro agora mostram **o nome da coluna** problemática.
- Relatório mostra quantos registros 0 km foram normalizados.
- Mantidos o rollback transacional, os limites de qualidade e todas as
  funcionalidades da v1.3.1, incluindo atualização diária e múltiplos workers.

**Validação:** 52 testes automatizados aprovados. Um teste de regressão usa
51.247 registros **fictícios**, 1.948 com ano-modelo vazio e 0 km, e confirma
51.247 registros importados e zero rejeições. **O snapshot real do Windows não
está disponível neste ambiente**, portanto a causa das 1.948 linhas só será
confirmada após repetir a sincronização e verificar os logs.

### Atualizar no Windows com o patch

1. Pare o Uvicorn e o atualizador com `Ctrl+C` (se estiverem rodando).
2. Extraia `patch-api-fipe-v1.3.2-ano-vazio-zero-km.zip` sobre a **raiz** da
   sua pasta `C:\Users\Taylo\Downloads\PROJETOS\api-fipe-independente`.
   Confirme que houve substituição de `scripts\importar.py`.
3. Preserve `.env` e `data\` integralmente.
4. Execute no PowerShell, a partir da pasta do projeto:

```powershell
python -m pytest -q
python -m scripts.autoatualizar --uma-vez
```

Se o arquivo real contiver apenas ausências de `ano_modelo` em veículos
`zero_km=true`, a saída poderá indicar:

```
FIPEX: 1948 veículo(s) zero km sem ano_modelo normalizado(s) para código técnico 32000.
... 51247 linhas válidas ...
```

Se existir outro campo ausente, o novo erro mostrará o nome dele, por exemplo
`Campo valor_centavos vazio`. Não aumente `AUTO_SYNC_MAX_INVALIDOS_FRACAO`
para contornar isso: examine os registros antes de aceitá-los.

Para iniciar a API após importação:

```powershell
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 2 --no-access-log
```

No outro terminal, para habilitar verificação diária enquanto estiver aberto:

```powershell
python -m scripts.autoatualizar
```

Endereços: `http://127.0.0.1:8000/health` e
`http://127.0.0.1:8000/metadados` (o segundo pode exigir `X-API-Key`).

---

## Correção v1.3.1 — Código de ano 32000 (FIPE zero km)

Em outubro de 2026, a versão v1.3 podia rejeitar registros válidos porque
interpretava `ano_modelo=32000` como um ano fora do intervalo. Na FIPE,
`32000` é um identificador técnico de **veículo zero-quilômetro**, e não
um ano calendário. Esta versão importa `ano_modelo=32000` quando `zero_km=true`,
sem alterar o valor, e permite `ano=32000` em `/consulta` e `/historico`.
A rota `/anos` também passa a listar `32000` quando disponível. A proteção
contra importações incompletas permanece: não ampliamos a porcentagem máxima
de erros permitida; agora as linhas ignoradas mostram suas causas no log.

Atualização no Windows: pare o servidor, substitua apenas os arquivos do código
pelos deste ZIP e **preserve integralmente `.env` e a pasta `data`**.
Com o servidor já iniciado em outro terminal, execute:

```powershell
python -m scripts.autoatualizar --uma-vez
Invoke-RestMethod http://127.0.0.1:8000/health
Invoke-RestMethod http://127.0.0.1:8000/metadados
# Exemplo: consulta de versões novas (substitua pelos modelos da sua base):
Invoke-RestMethod 'http://127.0.0.1:8000/consulta?marca=Toyota&modelo=Corolla&ano=32000'
```

Se a API utilizar `API_KEY`, envie `-Headers @{ 'X-API-Key'='SUA_CHAVE' }`
nas consultas protegidas.

Para atualização contínua sem Docker, execute **em um segundo terminal**:
`python -m scripts.autoatualizar`. O worker não fica embutido no Uvicorn.

Se a primeira sincronização ainda falhar, observe os *motivos* agora
exibidos ao lado das linhas; as alterações só são efetivadas se a importação
passar em todas as verificações. O lote remoto completo não pôde ser obtido
neste ambiente para confirmação end-to-end.

---

# API FIPE Independente — v1.3.0 (alta capacidade de consultas e atualização automática)

API REST independente para consultar dados de veículos FIPE por marca, modelo e ano, incluindo todas as versões, códigos, combustíveis, valores e referências disponíveis. **Não integra, modifica nem depende do NH.**

**Fonte:** [fipex-labs/dataset](https://github.com/fipex-labs/dataset), licença CC0-1.0. O projeto FIPEX é independente da Fundação FIPE. As publicações são mensais; nossa aplicação verifica diariamente se há uma nova release. Assim que a atualização é detectada, ela é importada automaticamente, *sem atualização em tempo real* da tabela oficial.


## Novidades v1.3 — mais de 100 consultas por segundo

Esta versão é **independente do NH** e mantém 100% dos endpoints/atualizador da v1.2. Não impõe limite artificial de RPS; o limite real é CPU, I/O, memória, carga concorrente, volume da resposta e recursos da máquina.

Otimizações implementadas:

- **SQLite em modo WAL** com **conexões somente leitura reutilizadas por thread**. As conexões não mantêm transações abertas entre requisições; atualizações mensais continuam visíveis sem reiniciar a API.
- **Três índices de consulta adicionais** para referência, marca, ano, tipo e modelo. Banco existente é migrado sem excluir nenhum registro, via `python -m scripts.otimizar`.
- **Cache LRU thread-safe em memória com TTL de 3 segundos**, 4.000 entradas por processo, com proteção contra armazenar respostas com mais de 200 linhas. Resultados de uma republicação do mesmo mês podem levar até 3 segundos para aparecer no cache.
- **ORJSON** para respostas JSON e **múltiplos processos Uvicorn** em produção, mantendo a sincronização em processo separado.
- **Benchmark HTTP reproduzível**, que mede req/s, p50/p95/p99, códigos HTTP e erros. Inclui gerador de **dados 100% fictícios** para benchmarks. Os dados fictícios nunca devem ser usados como Tabela FIPE real.

### Resultados medidos aqui (não são garantia para sua VPS)

Ambiente deste teste: base fictícia de **100.000 registros**, Python 3.13, Uvicorn com **2 workers**, 32 clientes HTTP concorrentes, `GET /consulta?marca=Toyota&modelo=Corolla&ano=2022&limite=20`:

| Cenário | Sucesso (req/s) | Respostas válidas | p95 |
|---|---:|---:|---:|
| Consulta repetida, cache 3 s | **328,21** | 2.322/2.322 (7 s) | 280,27 ms |
| Consulta repetida, cache desligado | **226,57** | 1.152/1.152 (5 s) | 432,97 ms |
| Variando anos de 2015 a 2025, cache 3 s | **329,57** | 1.667/1.667 (5 s) | 280,89 ms |

A medição foi feita em **um ambiente de teste**, com dados simulados e sem chamadas ao GitHub, e não prova o mesmo throughput na sua máquina. Testes variando ano não substituem um teste de produção com distribuição real de marcas, modelos e histórico. Use os comandos abaixo na sua VPS e ajuste `API_WORKERS` à CPU e RAM disponíveis.

### Windows PowerShell: migrar, iniciar e medir

> Na atualização, pare o Uvicorn com `Ctrl+C`. Copie os novos arquivos, **preserve `data/` e `.env`**. Não substitua seu banco pelos dados sintéticos do benchmark.

```powershell
python -m pip install -r requirements-dev.txt
python -m scripts.otimizar
# Sem --reload para medir capacidade; teste com 2 processos.
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 2 --no-access-log
```

Em **outro terminal PowerShell**:

```powershell
python -m benchmarks.carga --duracao 10 --concorrencia 32 --meta 100
python -m benchmarks.carga --duracao 10 --concorrencia 32 --meta 100 --variar-anos
```

O script utiliza a API em `http://127.0.0.1:8000`, com uma consulta por Toyota Corolla 2022. Se a base ainda não contém esses dados, as requisições podem retornar listas vazias; nesse caso adapte os filtros usando `--marca`, `--modelo`, `--ano` e `--limite`. Se `API_KEY` estiver ativa, informe `--api-key SUA_CHAVE`. Erros 401/503/429 **não contam como sucesso**.

Para gerar uma base *fictícia isolada*, caso queira fazer testes antes de importar o FIPEX real:

```powershell
python -m benchmarks.gerar_base_teste --registros 100000 --arquivo .\data\fipe-benchmark.sqlite3
$env:DATABASE_PATH = (Resolve-Path .\data\fipe-benchmark.sqlite3).Path
python -m uvicorn app.main:app --port 8000 --workers 2 --no-access-log
```

**Nunca** rode a API de produção apontando para `fipe-benchmark.sqlite3`. Após o teste, feche o Uvicorn, remova a variável temporária com `Remove-Item Env:DATABASE_PATH` e reinicie utilizando o banco real. O script de geração nunca sobrescreve bancos existentes.

### Docker Compose

```bash
cp .env.example .env
# Edite .env (API_KEY forte, API_WORKERS=2 ou 4, conforme hardware).
docker compose -p api-fipe-independente up -d --build
# Uma única vez, para adicionar índices ao banco já importado da v1.2:
docker compose -p api-fipe-independente exec api-fipe python -m scripts.otimizar
```

- `API_WORKERS=2`: ponto de partida para VPS pequenas (ex.: 2 vCPU); com 4+ vCPUs e RAM suficiente, teste `4` e compare o resultado antes de aumentar. Mais workers nem sempre significa mais RPS.
- `QUERY_CACHE_TTL_SECONDS=3`: duração máxima das respostas em cache. Use `0` para desativar. Um TTL curto evita informação antiga após sincronização; dados de preços são mensais.
- `QUERY_CACHE_MAX_ENTRIES=4000`: limite de entradas **por worker**, com exclusão LRU. Não é um limite de consultas por segundo.
- No Docker, o serviço de atualização diária continua independente dos workers HTTP.
- Não exponha diretamente a porta 8000: mantenha acesso por proxy HTTPS, autenticação e controles antiabuso na borda.
- Para escalar além de uma VPS (milhares de req/s), considere Postgres com réplicas de leitura, cache Redis e balanceador. Essa arquitetura não foi implantada nesta versão.

## Correção v1.2 — HTTP 415 no Windows e Git LFS

- Corrigido **HTTP 415 Unsupported Media Type** ao ler a última release do GitHub: o endpoint REST recebe agora `Accept: application/vnd.github+json`, enquanto o CSV usa cabeçalho apropriado para arquivos.
- Se o GitHub retornar um **ponteiro Git LFS** em vez do CSV real, o importador resolve o arquivo pelo armazenamento configurado pelo próprio FIPEX em `.lfsconfig` (Hugging Face), verificando **tamanho e SHA-256** antes de importar.
- Adicionados 7 testes de regressão: cabeçalhos HTTP, CSV direto, ponteiro LFS, fallback, integridade e URL de download.
- A instalação v1.0 não tinha serviço automático; substitua pela versão completa v1.2 para ativá-lo.

### Atualizar instalações antigas (v1.0 até v1.2) no PowerShell (Windows)

1. **Encerre o Uvicorn** com `Ctrl+C` no terminal em que ele está rodando.
2. Extraia o ZIP v1.3 e **substitua os arquivos do código** em `C:\Users\Taylo\Downloads\api-fipe-independente`. Mantenha a pasta `data` existente e qualquer arquivo `.env` configurado; **não apague o banco `.sqlite3`**.
3. Abra o PowerShell nessa pasta e execute:

```powershell
python -m pip install -r requirements.txt
python -m scripts.autoatualizar --uma-vez
```

Se o comando de sincronização terminar sem erro, inicie a API no **terminal 1**:

```powershell
python -m uvicorn app.main:app --reload
```

Para **atualização diária automática sem Docker**, abra um **terminal 2** na mesma pasta e deixe rodando:

```powershell
python -m scripts.autoatualizar
```

O worker verifica a fonte imediatamente e depois a cada 24 horas. Se você fechar o terminal 2, a sincronização automática para; para rodar continuamente use Docker Compose ou um serviço/Agendador de Tarefas do Windows. O endereço local é `http://127.0.0.1:8000/docs`. Se a versão anterior criou uma base em `data/fipe.sqlite3`, ela é preservada ao extrair os arquivos.

## O que há na atualização automática (desde a v1.1)

- `atualizador-fipe`: processo independente iniciado automaticamente com `docker compose up -d`, verifica o GitHub Releases **na inicialização e a cada 24 horas** (configurável).
- Detecta **release nova** ou republicação de uma referência (tag, ex.: `v2026.10.1`). Compara a versão instalada antes de baixar arquivos.
- Na primeira instalação baixa apenas o **mês mais novo**, para oferecer consultas mais rapidamente; depois importa também todos os meses perdidos desde a última referência local caso o serviço tenha ficado offline.
- Download fixado à **tag da release** e limite de tamanho do arquivo mensal; valida cabeçalho, quantidade mínima de linhas, referência de cada registro e proporção máxima de registros inválidos e queda anormal do total em relação à base local.
- Cada mês é importado com **uma transação atômica**. Em caso de erro, o mês anterior permanece intacto e não se atualiza a marca da versão instalada. Republicações substituem somente a referência republicada, **sem apagar os meses anteriores**.
- SQLite em **WAL**, permitindo leituras de consulta durante a transação de importação, sem desligar a API.
- `filelock` evita dois processos atualizando ao mesmo tempo; falhas são registradas e haverá nova tentativa na próxima execução programada.
- Novo endpoint `GET /sincronizacao/status` (respeita `X-API-Key`) e painel web em `GET /painel/sincronizacao`.
- Os endpoints da versão 1.0 continuam funcionando.

> **Atenção:** a atualização só acontece enquanto o processo `atualizador-fipe` está em execução (ou agendado na sua infraestrutura). O ZIP é código, não um serviço já instalado/rodando. É necessário iniciar o Docker Compose ou configurar um agendador equivalente em seu próprio computador/servidor.

## 1. Docker Compose — recomendado para rodar continuamente

Pré-requisito: Docker Engine + Compose v2, conectado à internet. Em Linux:

```bash
cp .env.example .env
# Edite .env e configure API_KEY com um segredo forte.
docker compose -p api-fipe-independente up -d --build
```

**Não é necessário rodar o importador manualmente.** A API começa em `http://127.0.0.1:8000`; em paralelo, o serviço `atualizador-fipe` consulta o GitHub e importa o mês publicado mais recente. **Até a primeira importação, as consultas de veículos devolvem HTTP 503 (sem dados),** que é o comportamento correto para não mostrar preços inventados.

Para acompanhar:

- Painel de sincronização: http://127.0.0.1:8000/painel/sincronizacao (informe a API Key na página, se configurada).
- Documentação Swagger: http://127.0.0.1:8000/docs
- Status JSON: `curl -H "X-API-Key: SUA_CHAVE" http://127.0.0.1:8000/sincronizacao/status`
- Logs do atualizador: `docker compose -p api-fipe-independente logs -f atualizador-fipe`
- Logs da API: `docker compose -p api-fipe-independente logs -f api-fipe`
- Rodar verificação agora (opcional):

```bash
docker compose -p api-fipe-independente run --rm atualizador-fipe python -m scripts.autoatualizar --uma-vez
```

**Segurança:** a porta externa publica apenas em `127.0.0.1` no host. Para acesso de outros dispositivos ou publicação na internet, configure proxy reverso HTTPS, controle de acesso, rate limiting e backups do volume `dados-fipe`. O campo `API_KEY` protege endpoints de consulta e status; mantenha-o secreto. O painel é público como HTML estático, mas não mostra o status sem autenticação se a chave estiver configurada. A chave informada no painel fica somente na memória da página, não é salva no navegador.

## 2. Executar sem Docker (desenvolvimento)

Requer Python 3.11+ e internet no computador. Execute no diretório raiz do projeto:

```bash
python -m pip install -r requirements.txt
# Terminal 1: API
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
# Terminal 2: sincronização contínua
python -m scripts.autoatualizar
```

Sem Docker, **não use o caminho Linux** `/app/data/fipe.sqlite3` de `.env.example`; deixe `DATABASE_PATH` desconfigurado para usar `./data/fipe.sqlite3`, ou defina um caminho local válido. Sem configurar `API_KEY`, acesso é sem chave (apenas ambiente de desenvolvimento).

Para sincronizar somente uma vez:

```bash
python -m scripts.autoatualizar --uma-vez
```

Para mudar o intervalo, defina a variável de ambiente `AUTO_SYNC_INTERVAL_SECONDS` (padrão 86400, mínimo 3600). A configuração é aplicada no reinício do worker.

**Recomendação operacional:** em produção, use o Docker Compose com `restart: unless-stopped` ou configure um serviço gerenciado pelo sistema para reiniciar a tarefa em caso de falha. Sem isso, fechar o terminal interrompe a verificação automática.

## 3. Endpoints HTTP

| Rota | O que retorna |
|---|---|
| `GET /health` | `ok` se já existe base de preços; `sem_dados` se não existe |
| `GET /sincronizacao/status` | Última verificação, importação, versão local/remota, estado e erro |
| `GET /painel/sincronizacao` | Painel simples de acompanhamento com atualização visual por minuto |
| `GET /metadados` | Referência mais recente e contagens |
| `GET /tipos` | Tipos disponíveis |
| `GET /marcas` | Marcas com paginação e filtros |
| `GET /modelos?marca=Toyota&busca=Corolla` | Modelos da marca (busca por trecho) |
| `GET /anos?marca=Toyota&modelo=Corolla` | Anos disponíveis |
| `GET /consulta?marca=Toyota&modelo=Corolla&ano=2022` | Versões da marca/modelo/ano, com código FIPE e preço |
| `GET /historico?codigo_fipe=...&ano=2022` | Histórico dos meses armazenados |
| `GET /docs` | Swagger UI |

A busca de modelos procura um **trecho do nome**, não uma versão exata. Logo, `modelo=Corolla` pode retornar Corolla sedã, Corolla Cross e outros, conforme o dataset. Consulte `codigo_fipe` e os campos de identificação antes de tomar qualquer decisão. A API **não** retorna placa, RENAVAM, chassi, potência, quilometragem nem itens de série: essas informações não pertencem aos 12 campos disponíveis no dataset FIPEX.

### Exemplo de status

```json
{
  "estado": "atualizado",
  "verificado_em": "2026-10-16T12:00:00+00:00",
  "concluido_em": "2026-10-16T12:00:05+00:00",
  "atualizado_em": "2026-10-16T12:00:05+00:00",
  "versao_instalada": "v2026.10.0",
  "versao_remota": "v2026.10.0",
  "erro": null,
  "linhas_ultima_importacao": 55000,
  "atualizado_status_em": "2026-10-16T12:00:05+00:00",
  "referencia_local": { "ano": 2026, "mes": 10 }
}
```

**Exemplo fictício:** a contagem e os horários acima são ilustrativos. Campos reais refletem a sincronização executada em seu servidor.

### Estados da sincronização

`nao_iniciado` → `verificando` → `baixando` → `importando` → `atualizado`; se ocorrer erro, o estado passa para `erro` e o valor `erro` contém a causa. Quando não há release nova, a verificação termina em `atualizado` sem novo download. O campo `atualizado_em` indica a **última importação**, e não a última verificação. O estado `erro` **não significa que a API esteja indisponível**: ela segue atendendo à base anterior.

## 4. Importação manual e dados históricos

O formato de CSV do dataset é TSV (colunas separadas por tabulação), e o ZIP do código do FIPEX contém ponteiros Git LFS, **não** os CSVs de preços reais. O worker tenta baixar o CSV pelo GitHub e, se receber um ponteiro Git LFS, resolve o objeto no armazenamento público do FIPEX (Hugging Face) usando o SHA-256 do ponteiro. O download é fixado na tag correspondente.

Para importar um mês manualmente sem apagar outros meses:

```bash
python scripts/sincronizar.py --ano 2026 --mes 10
```

Para importar um CSV/TSV já obtido (incluindo histórico completo via CSV merged/original):

```bash
python scripts/importar.py --arquivo ./fipex-prices-latest-merged.csv
```

**Atenção:** `--substituir` no importador manual remove todos os meses anteriores, o que não ocorre no atualizador automático. Para dados históricos completos anteriores à primeira inicialização, importe o arquivo merged/original da [última release](https://github.com/fipex-labs/dataset/releases/latest). O arquivo histórico pode ocupar ~1 GB em CSV e requer tempo/espaço extra.

## 5. Testes e limitações

```bash
python -m pip install -r requirements-dev.txt
python -m pytest -q
```

Os testes automatizados não usam a internet e nunca incluem preços reais. Validam consultas, segurança da API, importação transacional, referência inválida, reimportação, checagem sem novidades, republicação, falha de conexão, recuperação de meses e status do worker. A rotina de conexão ao GitHub e ao LFS real **precisa ser verificada na máquina/servidor com internet** após a implantação; os testes simulam a resposta da origem.

### Configurações no `.env`

| Variável | Padrão | Descrição |
|---|---|---|
| `DATABASE_PATH` | `/app/data/fipe.sqlite3` com Docker | Banco local compartilhado |
| `API_KEY` | vazio | Chave opcional; configure em produção |
| `CORS_ORIGINS` | vazio | Origens web habilitadas |
| `AUTO_SYNC_INTERVAL_SECONDS` | `86400` | Verificação diária (mínimo `3600`) |
| `AUTO_SYNC_MIN_REGISTROS` | `1000` | Rejeita arquivos mensalmente suspeitos/curtos |
| `AUTO_SYNC_MAX_INVALIDOS_FRACAO` | `0.005` | Rejeita importação com mais de 0,5% de linhas inválidas |
| `AUTO_SYNC_MIN_RATIO` | `0.70` | Exige pelo menos 70% das linhas do último mês equivalente/local; evita substituir dados por arquivo truncado |
| `LOG_LEVEL` | `INFO` | Log do worker |

### Estrutura

```text
fipe-independente/
  app/
    performance.py     # Conexões read-only reutilizadas, cache LRU TTL
    database.py       # SQLite em WAL, schema de preços e sincronização
    main.py           # API REST + painel HTML
    sync_state.py     # status persistente da sincronização
  scripts/
    otimizar.py        # Criar índices/migrar banco antigo sem apagar dados
    importar.py       # TSV, validação e transações SQL
    sincronizar.py    # descoberta da release e download FIPEX
    autoatualizar.py # worker periódico, lock e recuperação de meses
  benchmarks/         # Gerador de base fictícia e carga HTTP real
  tests/              # testes automatizados independentes da internet
  data/               # base local, excluída do pacote
  Dockerfile
  docker-compose.yml
  .env.example
  requirements.txt
  requirements-dev.txt
```

### Limites conhecidos

- A publicação mensal depende da disponibilidade da fonte FIPEX/GitHub. Se o FIPEX atrasar uma publicação, a API não antecipa o dado.
- O controle da sincronização compara **releases publicadas**, não cada commit avulso no GitHub. Para novas referências publicadas em uma release, uma verificação diária é suficiente para eventual consistência.
- Uma verificação pode ocorrer até 24 horas após a publicação, conforme o intervalo configurado.
- Se a fonte modificar retroativamente um mês anterior sem nova publicação/tag, **esse mês não é automaticamente reprocessado**; uma nova tag para o mesmo mês provoca reimportação do último mês.
- Em falhas consecutivas, a aplicação mantém o último snapshot disponível e registra o erro; a próxima tentativa ocorre no próximo intervalo.
- A rotina **não** executa backups externos do volume, não tem e-mail/alerta de incidente e não foi testada com download real neste ambiente. Antes de colocar em produção pública, valide conectividade e espaço em disco (inclusive diretório temporário), configure backup periódico e HTTPS.


## v1.4.1 — diagnóstico de vazão HTTP no Windows

A rota `/consulta` agora inclui `Server-Timing: consulta;dur=<milissegundos>`,
medido no servidor **dentro da função da rota** (não inclui validação anterior
do FastAPI, comunicação de rede nem espera no cliente). Mantém os cabeçalhos
`X-FIPE-Cache` (HIT/MISS), a autenticação e o TTL de cache.

Execute os testes em um PowerShell separado do Uvicorn:

```powershell
python -m pytest -q
python -m benchmarks.carga --duracao 20 --concorrencia 8 --meta 100
python -m benchmarks.carga --duracao 20 --concorrencia 16 --meta 100
python -m benchmarks.carga --duracao 20 --concorrencia 32 --meta 100
python -m benchmarks.carga --duracao 20 --concorrencia 32 --meta 100 --variar-anos
python -m benchmarks.carga --duracao 10 --concorrencia 32 --meta 100 --rota /health
```

O relatório apresenta `cache_respostas`, `tempo_interno_medio_ms` e
`tempo_interno_p95_ms` quando o servidor está na versão 1.4.1.
A comparação com `latencia_media_ms` ajuda a separar execução da rota
de outros custos de processamento, rede e do próprio gerador de carga.
O teste `/health` serve como linha de base HTTP; **não conta como consulta FIPE**.

Para confirmar se o benchmark exercita registros reais, abra no navegador:
`http://127.0.0.1:8000/consulta?marca=Toyota&modelo=Corolla&ano=2022&limite=20`
e verifique que o campo `total` é maior que zero. Se a API exigir chave,
faça a requisição com `X-API-Key`.

**Cuidados:** não execute otimizadores em loop nem desative validações para
forçar um número de req/s. Mantenha `data/` e `.env` ao aplicar o patch.
