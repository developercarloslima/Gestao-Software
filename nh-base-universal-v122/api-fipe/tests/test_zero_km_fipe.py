"""Regressão: na FIPE, o ano técnico 32000 significa veículo zero km."""
import csv

import pytest
from fastapi.testclient import TestClient

from app.database import COLUNAS, conexao
from app.main import app
from scripts.importar import converter, importar


def _registro(registros, *, ano='32000', zero_km='true'):
    return {**registros[0], 'codigo_fipe': '999999-9',
            'nome_modelo': 'Corolla Zero KM', 'ano_modelo': ano, 'zero_km': zero_km}


def test_ano_32000_zero_km_aceito(registros):
    r = converter(_registro(registros))
    assert r[6] == 32000
    assert r[7] == 1


def test_ano_32000_sem_zero_km_rejeitado(registros):
    with pytest.raises(ValueError, match='intervalo'):
        converter(_registro(registros, zero_km='false'))


def test_ano_arbitrario_acima_2200_rejeitado(registros):
    with pytest.raises(ValueError, match='intervalo'):
        converter(_registro(registros, ano='2500'))


def test_importacao_32000_protecao_rigida_e_consulta(base_populada, registros, tmp_path):
    _, antigo = base_populada
    csv_file = tmp_path / 'snapshot-zero.csv'
    with csv_file.open('w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=COLUNAS, delimiter='\t')
        w.writeheader()
        w.writerows([{**_registro(registros), 'mes_referencia': '11'},
                     {**registros[0], 'mes_referencia': '11'}])
    resultado = importar(csv_file, referencia_esperada=(2026, 11),
                         minimo_registros=2, limite_invalidos_proporcao=0,
                         substituir_mes=True, proporcao_minima_anterior=0)
    assert resultado == {'linhas_lidas': 2, 'linhas_validas': 2, 'linhas_ignoradas': 0}
    with conexao() as c:
        assert c.execute('SELECT COUNT(*) FROM precos WHERE ano_modelo=32000').fetchone()[0] == 1
    with TestClient(app) as client:
        anos = client.get('/anos', params={'marca': 'Toyota', 'modelo': 'Corolla'}).json()
        assert 32000 in anos['anos']
        r = client.get('/consulta', params={'marca': 'Toyota', 'modelo': 'Corolla', 'ano': 32000})
        assert r.status_code == 200
        assert r.json()['total'] == 1
        item = r.json()['veiculos'][0]
        assert item['ano_modelo'] == 32000
        assert item['zero_km'] is True
        cod = item['codigo_fipe']
        h = client.get('/historico', params={'codigo_fipe': cod, 'ano': 32000})
        assert h.status_code == 200 and h.json()['total'] == 1
        assert client.get('/consulta', params={'marca': 'Toyota', 'modelo': 'Corolla', 'ano': 2500}).status_code == 422
        assert client.get('/historico', params={'codigo_fipe': cod, 'ano': 2500}).status_code == 422


def test_ano_32000_incorreto_faz_rollback(base_populada, registros, tmp_path):
    destino = tmp_path / 'snapshot-incorreto.csv'
    with destino.open('w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=COLUNAS, delimiter='\t')
        w.writeheader()
        w.writerow({**_registro(registros, zero_km='false'), 'mes_referencia': '11'})
    with pytest.raises(ValueError, match='Nenhum registro'):
        importar(destino, referencia_esperada=(2026, 11),
                 minimo_registros=1, limite_invalidos_proporcao=0, substituir_mes=True)
    with conexao() as c:
        assert c.execute('SELECT COUNT(*) FROM precos WHERE ano_referencia=2026 AND mes_referencia=10').fetchone()[0] == 8
        assert c.execute('SELECT COUNT(*) FROM precos WHERE mes_referencia=11').fetchone()[0] == 0
