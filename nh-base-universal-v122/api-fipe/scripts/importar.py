"""Importa TSV FIPEX (extensão .csv) em lotes, validando seus 12 campos."""
from __future__ import annotations

import argparse
import csv
from collections import Counter
import os
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.database import COLUNAS, conexao, criar_schema, normalizar  # noqa: E402

INSERIR = '''INSERT INTO precos (
 tipo_veiculo,codigo_fipe,nome_modelo,nome_marca,nome_combustivel,
 sigla_combustivel,ano_modelo,zero_km,valor_centavos,valor_formatado,
 mes_referencia,ano_referencia,marca_busca,modelo_busca,tipo_busca,combustivel_busca
) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT(tipo_veiculo,codigo_fipe,ano_modelo,zero_km,sigla_combustivel,
ano_referencia,mes_referencia) DO UPDATE SET
 nome_modelo=excluded.nome_modelo, nome_marca=excluded.nome_marca,
 nome_combustivel=excluded.nome_combustivel, valor_centavos=excluded.valor_centavos,
 valor_formatado=excluded.valor_formatado, marca_busca=excluded.marca_busca,
 modelo_busca=excluded.modelo_busca, tipo_busca=excluded.tipo_busca,
 combustivel_busca=excluded.combustivel_busca'''


def bool_csv(s: str) -> int:
    texto = str(s).strip().lower()
    if texto in ('true', '1', 'sim'): return 1
    if texto in ('false', '0', 'nao', 'não'): return 0
    raise ValueError(f'zero_km inválido: {s}')


def _inteiro_obrigatorio(valor: str, nome: str) -> int:
    """Não confunde campo ausente com zero nem perde o nome da coluna no log."""
    if not valor:
        raise ValueError(f'Campo {nome} vazio')
    try:
        return int(valor)
    except (TypeError, ValueError) as exc:
        raise ValueError(f'Campo {nome} não é inteiro válido: {valor[:50]!r}') from exc


def converter(r: dict) -> tuple:
    if not all(k in r for k in COLUNAS):
        raise ValueError(f'Colunas obrigatórias ausentes: {set(COLUNAS) - set(r)}')
    # DictReader usa None quando faltam células. Nenhuma célula deve virar a
    # string literal "None", pois isso mascara erros de formatação.
    info = ['' if r[k] is None else str(r[k]).strip() for k in COLUNAS]
    for campo in (0, 1, 2, 3, 4, 5):
        if not info[campo]:
            raise ValueError(f'Campo {COLUNAS[campo]} vazio')
    if info[7] not in ('0', '1'):
        info[7] = bool_csv(info[7])
    else:
        info[7] = int(info[7])

    # Nos snapshots FIPEX, um veículo 0 km pode não ter ano-modelo numérico.
    # Para nossa API (coluna INTEGER NOT NULL), usa-se o código técnico FIPE
    # 32000 SOMENTE se a própria fonte marcar explicitamente zero_km=true.
    # Não alterar ano preenchido, e nunca inventar preço/referência ausentes.
    if not info[6] and info[7] == 1:
        info[6] = 32000
    else:
        info[6] = _inteiro_obrigatorio(info[6], 'ano_modelo')
    for campo in (8, 10, 11):
        info[campo] = _inteiro_obrigatorio(info[campo], COLUNAS[campo])
    ano_valido = (0 <= info[6] <= 2200) or (info[6] == 32000 and info[7] == 1)
    if not (1 <= info[10] <= 12 and 1900 <= info[11] <= 2200 and ano_valido and info[8] >= 0):
        raise ValueError(f'Ano, mês ou preço fora do intervalo (ano_modelo={info[6]}, zero_km={info[7]})')
    return (*info, normalizar(info[3]), normalizar(info[2]), normalizar(info[0]), normalizar(info[4]))


def importar(arquivo: Path, *, substituir: bool = False,
             referencia_esperada: tuple[int, int] | None = None,
             minimo_registros: int = 1, limite_invalidos_proporcao: float = 1.0,
             substituir_mes: bool = False,
             proporcao_minima_anterior: float = 0.0) -> dict:
    """Importação numa só transação: erro faz rollback sem perder preços anteriores.

    Em modo automático, exige referência coerente e volume mínimo de linhas.
    substituir_mes evita versões obsoletas numa republicação do mesmo mês.
    """
    if not 0.0 <= proporcao_minima_anterior <= 1.0:
        raise ValueError('proporcao_minima_anterior deve estar entre 0 e 1.')
    if not arquivo.is_file():
        raise FileNotFoundError(str(arquivo))
    with arquivo.open('r', encoding='utf-8-sig', newline='') as f:
        inicio = f.read(256)
        if inicio.startswith('version https://git-lfs.github.com/spec/v1'):
            raise ValueError('Arquivo Git LFS é apenas um ponteiro, não contém preços. Baixe o CSV real usando scripts/sincronizar.py.')
        f.seek(0)
        leitor = csv.DictReader(f, delimiter='\t')
        if not leitor.fieldnames or not set(COLUNAS).issubset(set(leitor.fieldnames)):
            raise ValueError('Arquivo não corresponde ao TSV FIPEX de 12 colunas. Confira o formato do dataset.')
        with conexao(create=True) as c:
            criar_schema(c)
            if substituir:
                c.execute('DELETE FROM precos')
            comparacao = 0
            if referencia_esperada and proporcao_minima_anterior:
                # Preferir mesma referência já instalada; se não houver, comparar com
                # o último mês para evitar sobrepor dados por um dump truncado.
                comparacao = c.execute(
                    'SELECT COUNT(*) FROM precos WHERE ano_referencia=? AND mes_referencia=?',
                    referencia_esperada).fetchone()[0]
                if comparacao == 0:
                    ref = c.execute('''SELECT ano_referencia, mes_referencia FROM precos
                                       ORDER BY ano_referencia DESC, mes_referencia DESC LIMIT 1''').fetchone()
                    if ref:
                        comparacao = c.execute(
                            'SELECT COUNT(*) FROM precos WHERE ano_referencia=? AND mes_referencia=?',
                            (ref[0], ref[1])).fetchone()[0]
            if substituir_mes:
                if referencia_esperada is None:
                    raise ValueError('substituir_mes exige referencia_esperada.')
                c.execute('DELETE FROM precos WHERE ano_referencia=? AND mes_referencia=?',
                          referencia_esperada)
            lidas = validas = ignoradas = normalizados_zero_km = 0
            motivos_invalidos = Counter()
            lote = []
            for linha in leitor:
                lidas += 1
                try:
                    registro = converter(linha)
                    if referencia_esperada is not None and (registro[11], registro[10]) != referencia_esperada:
                        raise ValueError('Referência diferente da esperada')
                    lote.append(registro)
                    if (registro[6] == 32000 and registro[7] == 1
                            and not str(linha.get('ano_modelo') or '').strip()):
                        normalizados_zero_km += 1
                except (ValueError, TypeError) as exc:
                    ignoradas += 1
                    motivo = str(exc)[:180] or type(exc).__name__
                    motivos_invalidos[motivo] += 1
                    if ignoradas <= 5:
                        print(f'Aviso: linha {lidas + 1} inválida: {motivo}.', file=sys.stderr)
                    continue
                validas += 1
                if len(lote) >= 2000:
                    c.executemany(INSERIR, lote)
                    lote = []
            if lote:
                c.executemany(INSERIR, lote)
            if normalizados_zero_km:
                print(f'FIPEX: {normalizados_zero_km} veículo(s) zero km sem ano_modelo ' 
                      'normalizado(s) para código técnico 32000.', file=sys.stderr)
            if validas == 0:
                raise ValueError('Nenhum registro FIPE válido encontrado. Importação cancelada.')
            if validas < minimo_registros:
                raise ValueError(f'Snapshot suspeito: apenas {validas} linhas válidas; esperado >= {minimo_registros}.')
            if ignoradas:
                resumo = '; '.join(f'{total}x {motivo}' for motivo, total in motivos_invalidos.most_common(5))
                print(f'Aviso: {ignoradas}/{lidas} linhas não importadas; motivos: {resumo}.', file=sys.stderr)
            if ignoradas / max(lidas, 1) > limite_invalidos_proporcao:
                raise ValueError(
                    f'Snapshot suspeito: {ignoradas} linhas inválidas entre {lidas} '
                    f'({ignoradas / lidas:.2%}; limite {limite_invalidos_proporcao:.2%}). '
                    f'Motivos: {resumo}.')
            if comparacao:
                nova_contagem = c.execute(
                    'SELECT COUNT(*) FROM precos WHERE ano_referencia=? AND mes_referencia=?',
                    referencia_esperada).fetchone()[0]
                if nova_contagem < comparacao * proporcao_minima_anterior:
                    raise ValueError(
                        f'Snapshot suspeito/truncado: {nova_contagem} registros novos frente a '
                        f'{comparacao} anteriores (mínimo {proporcao_minima_anterior:.0%}).')
            c.execute('''INSERT INTO importacoes
                      (arquivo,data_importacao,linhas_lidas,linhas_validas,linhas_ignoradas)
                      VALUES (?,?,?,?,?)''',
                      (arquivo.name,datetime.now(timezone.utc).isoformat(),lidas,validas,ignoradas))
        return {'linhas_lidas': lidas, 'linhas_validas': validas, 'linhas_ignoradas': ignoradas}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description='Importação de CSV/TSV FIPEX para SQLite')
    p.add_argument('--arquivo', required=True, type=Path)
    p.add_argument('--substituir', action='store_true', help='Apaga os preços anteriores antes de importar. Não use ao adicionar histórico.')
    args = p.parse_args()
    print(importar(args.arquivo, substituir=args.substituir))
