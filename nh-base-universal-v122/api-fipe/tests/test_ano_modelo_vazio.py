"""Regressões para snapshots FIPEX com ano_modelo vazio em veículos 0 km."""
from __future__ import annotations

import csv

import pytest
from fastapi.testclient import TestClient

from app.database import COLUNAS, conexao
from app.main import app
from scripts.importar import converter, importar


def _escrever(destino, rows):
    with destino.open('w', encoding='utf-8', newline='') as arq:
        writer = csv.DictWriter(arq, fieldnames=COLUNAS, delimiter='\t')
        writer.writeheader()
        writer.writerows(rows)
    return destino


def test_ano_modelo_vazio_so_e_aceito_com_zero_km_true(registros):
    exemplo = {**registros[0], 'ano_modelo': '', 'zero_km': 'true'}
    registro = converter(exemplo)
    assert registro[6] == 32000
    assert registro[7] == 1
    for valor in ('false', '0', ''):
        with pytest.raises(ValueError, match='ano_modelo|zero_km'):
            converter({**exemplo, 'zero_km': valor})


def test_ano_modelo_informado_nao_sofre_normalizacao(registros):
    exemplo = {**registros[0], 'ano_modelo': '2026', 'zero_km': 'true'}
    assert converter(exemplo)[6] == 2026


@pytest.mark.parametrize('campo', ['valor_centavos', 'mes_referencia', 'ano_referencia'])
def test_outros_inteiros_vazios_continuam_invalidos(registros, campo):
    exemplo = {**registros[0], 'ano_modelo': '', 'zero_km': 'true', campo: ''}
    with pytest.raises(ValueError, match=f'Campo {campo} vazio'):
        converter(exemplo)


def test_importacao_com_ano_zero_km_vazio_e_consulta(base_populada, registros, tmp_path, capsys):
    arquivo = _escrever(tmp_path / 'outubro.csv', [
        {**registros[0], 'ano_modelo': '', 'zero_km': 'true', 'mes_referencia': '11'},
        {**registros[1], 'mes_referencia': '11'},
    ])
    resultado = importar(arquivo, referencia_esperada=(2026, 11), minimo_registros=2,
                        limite_invalidos_proporcao=0, substituir_mes=True,
                        proporcao_minima_anterior=0)
    assert resultado == {'linhas_lidas': 2, 'linhas_validas': 2, 'linhas_ignoradas': 0}
    assert '1 veículo(s) zero km' in capsys.readouterr().err
    with conexao() as conn:
        # Importação da nova referência não apaga histórico anterior
        assert conn.execute('SELECT COUNT(*) FROM precos WHERE mes_referencia=10').fetchone()[0] == 8
        assert conn.execute('SELECT COUNT(*) FROM precos WHERE mes_referencia=11').fetchone()[0] == 2
    with TestClient(app) as client:
        consulta = client.get('/consulta', params={'marca': 'Toyota', 'modelo': 'Corolla', 'ano': 32000})
        assert consulta.status_code == 200
        assert consulta.json()['total'] == 1
        assert consulta.json()['veiculos'][0]['zero_km'] is True


def test_sem_ano_em_usado_mantem_rollback(base_populada, registros, tmp_path):
    arquivo = _escrever(tmp_path / 'invalidos.csv', [
        {**registros[0], 'ano_modelo': '', 'zero_km': 'false', 'mes_referencia': '11'},
        {**registros[1], 'mes_referencia': '11'},
    ])
    with pytest.raises(ValueError, match='Snapshot suspeito'):
        importar(arquivo, referencia_esperada=(2026, 11), minimo_registros=1,
                 limite_invalidos_proporcao=0, substituir_mes=True)
    with conexao() as conn:
        assert conn.execute('SELECT COUNT(*) FROM precos WHERE ano_referencia=2026 AND mes_referencia=10').fetchone()[0] == 8
        assert conn.execute('SELECT COUNT(*) FROM precos WHERE mes_referencia=11').fetchone()[0] == 0


def test_snapshot_simulado_51247_linhas_1948_zero_km(tmp_path, monkeypatch, registros):
    """Reproduz contagens do log, com dados fictícios, não o arquivo FIPEX real."""
    monkeypatch.setenv('DATABASE_PATH', str(tmp_path / 'fipe-simulacao.sqlite3'))
    arquivo = tmp_path / 'snapshot-simulado.tsv'
    with arquivo.open('w', encoding='utf-8', newline='') as arq:
        writer = csv.DictWriter(arq, fieldnames=COLUNAS, delimiter='\t')
        writer.writeheader()
        for i in range(51247):
            writer.writerow({**registros[0],
                             'codigo_fipe': f'{i:06d}-0',
                             'ano_modelo': '' if i < 1948 else '2022',
                             'zero_km': 'true' if i < 1948 else 'false'})
    resultado = importar(arquivo, referencia_esperada=(2026, 10),
                        minimo_registros=1000, limite_invalidos_proporcao=0.005,
                        substituir_mes=True)
    assert resultado == {'linhas_lidas': 51247, 'linhas_validas': 51247, 'linhas_ignoradas': 0}
    with conexao() as conn:
        assert conn.execute('SELECT COUNT(*) FROM precos').fetchone()[0] == 51247
        assert conn.execute('SELECT COUNT(*) FROM precos WHERE ano_modelo=32000').fetchone()[0] == 1948
