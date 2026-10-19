"""Medição REAL via HTTP de throughput/latência; nunca presume capacidade da VPS.

Instale requirements-dev e execute com API já iniciada e dados carregados:
python -m benchmarks.carga --duracao 10 --concorrencia 32 --meta 100
"""
from __future__ import annotations

import argparse
import asyncio
import math
import itertools
import re
import os
import statistics
import time
from collections import Counter
from urllib.parse import urlencode

import httpx


def percentil(valores: list[float], p: float) -> float:
    if not valores:
        return 0.0
    ordenados = sorted(valores)
    indice = min(len(ordenados) - 1, max(0, math.ceil(p * len(ordenados)) - 1))
    return ordenados[indice]


def extrair_tempo_servidor(cabecalho: str | None) -> float | None:
    """Obtém o tempo interno declarado por Server-Timing, quando disponível."""
    if not cabecalho:
        return None
    match = re.search(r'(?:^|,)\s*consulta\s*;\s*dur\s*=\s*([0-9]+(?:\.[0-9]+)?)', cabecalho)
    return float(match.group(1)) if match else None


async def executar(url: str, duracao: float, concorrencia: int, chave: str,
                   caminho: str, aquecimento: float, variacoes: list[str] | None = None) -> dict:
    limites = httpx.Limits(max_connections=concorrencia + 5, max_keepalive_connections=concorrencia + 5)
    amostras: list[float] = []
    tempos_servidor: list[float] = []
    cache_status = Counter()
    status = Counter()
    erros = Counter()
    proxima_variacao = itertools.count()
    async with httpx.AsyncClient(base_url=url, timeout=10, limits=limites,
                                 headers={'X-API-Key': chave} if chave else {}) as client:
        # Warm-up da conexão HTTP, buffer cache do SQLite e rotas antes da amostra.
        fim_aquecimento = time.perf_counter() + aquecimento
        async def aquecer():
            while time.perf_counter() < fim_aquecimento:
                try:
                    await client.get(caminho)
                except httpx.RequestError:
                    pass
        await asyncio.gather(*(aquecer() for _ in range(min(concorrencia, 8))))
        inicio = time.perf_counter()
        fim = inicio + duracao

        async def operador():
            while time.perf_counter() < fim:
                t0 = time.perf_counter()
                try:
                    # Distribui as variações entre clientes, sem alinhar 32
                    # conexões no mesmo ano a cada iteração.
                    rota = variacoes[next(proxima_variacao) % len(variacoes)] if variacoes else caminho
                    response = await client.get(rota)
                    status[response.status_code] += 1
                    if response.status_code == 200:
                        amostras.append((time.perf_counter() - t0) * 1000)
                        estado = response.headers.get('X-FIPE-Cache')
                        if estado:
                            cache_status[estado.upper()] += 1
                        ms_servidor = extrair_tempo_servidor(response.headers.get('Server-Timing'))
                        if ms_servidor is not None:
                            tempos_servidor.append(ms_servidor)
                except httpx.RequestError as e:
                    erros[type(e).__name__] += 1

        await asyncio.gather(*(operador() for _ in range(concorrencia)))
        tempo_real = time.perf_counter() - inicio
    total = sum(status.values()) + sum(erros.values())
    ok = status[200]
    return {'duracao_real_s': round(tempo_real, 2), 'requisicoes': total,
            'http_200': ok, 'status_http': dict(status), 'falhas_rede': dict(erros),
            'rps_total': round(total / tempo_real, 2),
            'rps_sucesso': round(ok / tempo_real, 2),
            'latencia_media_ms': round(statistics.mean(amostras), 2) if amostras else None,
            'latencia_p50_ms': round(percentil(amostras, .50), 2) if amostras else None,
            'latencia_p95_ms': round(percentil(amostras, .95), 2) if amostras else None,
            'latencia_p99_ms': round(percentil(amostras, .99), 2) if amostras else None,
            'cache_respostas': dict(cache_status),
            'tempo_interno_medio_ms': round(statistics.mean(tempos_servidor), 3) if tempos_servidor else None,
            'tempo_interno_p95_ms': round(percentil(tempos_servidor, .95), 3) if tempos_servidor else None,
            'amostras_tempo_interno': len(tempos_servidor)}


def main() -> int:
    p = argparse.ArgumentParser(description='Benchmark HTTP da API FIPE, sem valores de desempenho inventados')
    p.add_argument('--url', default='http://127.0.0.1:8000')
    p.add_argument('--duracao', type=float, default=10)
    p.add_argument('--concorrencia', type=int, default=32)
    p.add_argument('--aquecimento', type=float, default=1)
    p.add_argument('--meta', type=int, default=100)
    p.add_argument('--api-key', default=os.getenv('API_KEY', ''))
    p.add_argument('--rota', default='/consulta')
    p.add_argument('--marca', default='Toyota')
    p.add_argument('--modelo', default='Corolla')
    p.add_argument('--ano', type=int, default=2022)
    p.add_argument('--limite', type=int, default=20)
    p.add_argument('--variar-anos', action='store_true', help='Alterna o ano entre 2015 e 2025 (carga menos repetitiva).')
    args = p.parse_args()
    if args.duracao < 1 or not 1 <= args.concorrencia <= 500 or args.aquecimento < 0:
        p.error('Use duração >= 1, concorrência entre 1 e 500 e aquecimento >= 0.')
    caminho = args.rota
    if args.rota == '/consulta':
        caminho += '?' + urlencode({'marca': args.marca, 'modelo': args.modelo,
                                     'ano': args.ano, 'limite': args.limite})
    variacoes = None
    if args.variar_anos and args.rota == '/consulta':
        variacoes = [args.rota + '?' + urlencode({'marca': args.marca, 'modelo': args.modelo,
                     'ano': ano, 'limite': args.limite}) for ano in range(2015, 2026)]
    print(f'Benchmark: {args.url}{caminho}, concorrência={args.concorrencia}, meta={args.meta} req/s')
    if variacoes:
        print('  Modo: consultas variando ano 2015–2025')
    resultado = asyncio.run(executar(args.url, args.duracao, args.concorrencia,
                                     args.api_key, caminho, args.aquecimento, variacoes))
    for k, v in resultado.items():
        print(f'  {k}: {v}')
    if args.rota != '/consulta':
        print('  AVISO: esta rota mede o HTTP básico, não a velocidade de consultas FIPE.')
    elif not resultado['amostras_tempo_interno']:
        print('  AVISO: sem Server-Timing: confirme que o servidor usa a versão 1.4.1.')
    else:
        print('  NOTA: tempo_interno_* mede apenas a execução da rota; a latência HTTP inclui espera, FastAPI, TCP e cliente.')
    aprovado = resultado['rps_sucesso'] >= args.meta and resultado['requisicoes'] == resultado['http_200']
    print('META ATINGIDA' if aprovado else 'META NÃO ATINGIDA (ajuste hardware/índices/tráfego e rode novamente)')
    return 0 if aprovado else 1


if __name__ == '__main__':
    raise SystemExit(main())
