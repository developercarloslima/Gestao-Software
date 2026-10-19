"""Status persistente, sem expor token de GitHub nem dados da API."""
from __future__ import annotations

import sqlite3
from datetime import datetime, timezone

from app.database import conexao, criar_schema, referencia_atual


def agora() -> str:
    return datetime.now(timezone.utc).isoformat(timespec='seconds')


def status() -> dict:
    vazio = {'estado': 'nao_iniciado', 'verificado_em': None, 'concluido_em': None,
             'atualizado_em': None, 'versao_instalada': None, 'versao_remota': None,
             'erro': None, 'linhas_ultima_importacao': None, 'atualizado_status_em': None,
             'referencia_local': None}
    try:
        with conexao() as c:
            existe = c.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='sincronizacao'").fetchone()
            row = c.execute('SELECT * FROM sincronizacao WHERE id=1').fetchone() if existe else None
            if row:
                vazio.update(dict(row))
                vazio.pop('id', None)
            atual = referencia_atual(c)
            vazio['referencia_local'] = {'ano': atual[0], 'mes': atual[1]} if atual else None
    except (FileNotFoundError, sqlite3.DatabaseError):
        pass
    return vazio


def registrar(**campos) -> None:
    permitidos = {'estado', 'verificado_em', 'concluido_em', 'atualizado_em',
                  'versao_instalada', 'versao_remota', 'erro',
                  'linhas_ultima_importacao', 'atualizado_status_em'}
    if not set(campos).issubset(permitidos):
        raise ValueError('Campo de status inválido.')
    campos['atualizado_status_em'] = agora()
    with conexao(create=True) as c:
        criar_schema(c)
        c.execute("INSERT OR IGNORE INTO sincronizacao (id, estado) VALUES (1, 'nao_iniciado')")
        c.execute('UPDATE sincronizacao SET ' + ', '.join(f'{k}=?' for k in campos) + ' WHERE id=1',
                  tuple(campos.values()))
