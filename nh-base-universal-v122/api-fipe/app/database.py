"""Persistência SQLite e normalização de campos FIPEX."""
from __future__ import annotations

import os
import re
import sqlite3
import unicodedata
from pathlib import Path

COLUNAS = (
    'tipo_veiculo', 'codigo_fipe', 'nome_modelo', 'nome_marca',
    'nome_combustivel', 'sigla_combustivel', 'ano_modelo', 'zero_km',
    'valor_centavos', 'valor_formatado', 'mes_referencia', 'ano_referencia',
)


def database_path() -> Path:
    return Path(os.environ.get('DATABASE_PATH', './data/fipe.sqlite3')).resolve()


def normalizar(texto: str) -> str:
    texto = unicodedata.normalize('NFKD', str(texto or ''))
    texto = ''.join(c for c in texto if not unicodedata.combining(c))
    return re.sub(r'\s+', ' ', texto.casefold().strip())


def conexao(*, create: bool = False) -> sqlite3.Connection:
    caminho = database_path()
    if not create and not caminho.is_file():
        raise FileNotFoundError('Banco de dados ainda não foi importado. Execute o script de sincronização.')
    if create:
        caminho.parent.mkdir(parents=True, exist_ok=True)
    c = sqlite3.connect(str(caminho), timeout=60, check_same_thread=False)
    c.row_factory = sqlite3.Row
    c.execute('PRAGMA busy_timeout=60000')
    # WAL mantém as consultas funcionando durante imports longos.
    if create:
        c.execute('PRAGMA journal_mode=WAL')
    return c


def criar_schema(c: sqlite3.Connection) -> None:
    c.executescript('''
    CREATE TABLE IF NOT EXISTS precos (
      id INTEGER PRIMARY KEY,
      tipo_veiculo TEXT NOT NULL,
      codigo_fipe TEXT NOT NULL,
      nome_modelo TEXT NOT NULL,
      nome_marca TEXT NOT NULL,
      nome_combustivel TEXT NOT NULL,
      sigla_combustivel TEXT NOT NULL,
      ano_modelo INTEGER NOT NULL,
      zero_km INTEGER NOT NULL CHECK (zero_km IN (0, 1)),
      valor_centavos INTEGER NOT NULL CHECK (valor_centavos >= 0),
      valor_formatado TEXT NOT NULL,
      mes_referencia INTEGER NOT NULL CHECK (mes_referencia BETWEEN 1 AND 12),
      ano_referencia INTEGER NOT NULL,
      marca_busca TEXT NOT NULL,
      modelo_busca TEXT NOT NULL,
      tipo_busca TEXT NOT NULL,
      combustivel_busca TEXT NOT NULL,
      UNIQUE(tipo_veiculo, codigo_fipe, ano_modelo, zero_km,
             sigla_combustivel, ano_referencia, mes_referencia)
    );
    CREATE INDEX IF NOT EXISTS ix_precos_ref
      ON precos(ano_referencia DESC, mes_referencia DESC);
    CREATE INDEX IF NOT EXISTS ix_precos_busca
      ON precos(marca_busca, ano_modelo, ano_referencia DESC, mes_referencia DESC);
    CREATE INDEX IF NOT EXISTS ix_precos_codigo
      ON precos(codigo_fipe, ano_modelo, ano_referencia, mes_referencia);
    -- Índices de leitura focados na referência publicada e filtros combinados.
    CREATE INDEX IF NOT EXISTS ix_precos_consulta_rapida
      ON precos(ano_referencia DESC, mes_referencia DESC, marca_busca, ano_modelo, modelo_busca);
    CREATE INDEX IF NOT EXISTS ix_precos_catalogo_rapido
      ON precos(ano_referencia DESC, mes_referencia DESC, marca_busca, tipo_busca, nome_modelo);
    CREATE INDEX IF NOT EXISTS ix_precos_marcas_rapida
      ON precos(ano_referencia DESC, mes_referencia DESC, tipo_busca, marca_busca);
    CREATE TABLE IF NOT EXISTS sincronizacao (
       id INTEGER PRIMARY KEY CHECK (id = 1),
       estado TEXT NOT NULL DEFAULT 'nao_iniciado',
       verificado_em TEXT,
       concluido_em TEXT,
       atualizado_em TEXT,
       versao_instalada TEXT,
       versao_remota TEXT,
       erro TEXT,
       linhas_ultima_importacao INTEGER,
       atualizado_status_em TEXT
    );
    CREATE TABLE IF NOT EXISTS importacoes (
       id INTEGER PRIMARY KEY,
       arquivo TEXT NOT NULL,
       data_importacao TEXT NOT NULL,
       linhas_lidas INTEGER NOT NULL,
       linhas_validas INTEGER NOT NULL,
       linhas_ignoradas INTEGER NOT NULL
    );
    ''')


def referencia_atual(c: sqlite3.Connection) -> tuple[int, int] | None:
    r = c.execute('''SELECT ano_referencia, mes_referencia FROM precos
                     ORDER BY ano_referencia DESC, mes_referencia DESC LIMIT 1''').fetchone()
    return (r['ano_referencia'], r['mes_referencia']) if r else None


def pronto() -> bool:
    try:
        with conexao() as c:
            return referencia_atual(c) is not None
    except (sqlite3.DatabaseError, FileNotFoundError):
        return False
