"""Gera banco fictício separado para testar carga. Não contém preços FIPE reais."""
from __future__ import annotations
import argparse
import os
from pathlib import Path


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--registros', type=int, default=100000)
    p.add_argument('--arquivo', type=Path, default=Path('./data/fipe-benchmark.sqlite3'))
    args = p.parse_args()
    if not 1 <= args.registros <= 2_000_000:
        p.error('Registros deve estar entre 1 e 2 milhões.')
    arquivo = args.arquivo.resolve()
    if 'benchmark' not in arquivo.name.lower() or arquivo.exists():
        p.error('Use arquivo contendo "benchmark" no nome e que ainda não exista; não substituímos bancos existentes.')
    os.environ['DATABASE_PATH'] = str(arquivo)
    from app.database import conexao, criar_schema
    from scripts.importar import INSERIR
    with conexao(create=True) as c:
        criar_schema(c)
        lote = []
        marcas = ('Toyota', 'Honda', 'Volkswagen', 'Chevrolet', 'Fiat', 'Ford', 'Renault', 'Hyundai')
        for i in range(args.registros):
            marca = marcas[i % len(marcas)]
            modelo = 'Corolla Versão ' + str(i % 240) if marca == 'Toyota' else 'Modelo ' + str(i % 500)
            ano = 2015 + ((i // len(marcas)) % 11)
            codigo = f'{100000 + i:07d}-{i % 10}'
            cts = 5000000 + (i % 1500000)
            lote.append(('Carros', codigo, modelo, marca, 'Gasolina', 'g', ano,
                         0, cts, f'R$ {cts/100:.2f}', 10, 2026,
                         marca.casefold(), modelo.casefold(), 'carros', 'gasolina'))
            if len(lote) == 2000:
                c.executemany(INSERIR, lote)
                lote.clear()
        if lote:
            c.executemany(INSERIR, lote)
        c.execute('ANALYZE')
    print(f'Banco de TESTE criado: {arquivo} ({args.registros} linhas fictícias).')
    print('NUNCA use este banco para consulta FIPE real.')


if __name__ == '__main__':
    main()
