"""Migra índices de uma base v1.2 existente sem apagar os preços.

Pode executar a primeira vez na manutenção. ANALYZE melhora planos das consultas;
CREATE INDEX pode demorar em bases grandes e consome disco temporário.
"""
from __future__ import annotations
from app.database import conexao, criar_schema, referencia_atual


def otimizar() -> None:
    with conexao(create=True) as c:
        criar_schema(c)
        c.execute('ANALYZE')
        c.execute('PRAGMA optimize')
        ref = referencia_atual(c)
        print(f'Índices preparados. Última referência: {ref or "sem preços"}.')


if __name__ == '__main__':
    otimizar()
