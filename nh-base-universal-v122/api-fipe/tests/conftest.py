from __future__ import annotations

import csv
import pytest
from fastapi.testclient import TestClient

from scripts.importar import importar
from app.database import COLUNAS
from app.main import app


@pytest.fixture
def registros():
    # Dados FICTÍCIOS para teste da API. Não são preços FIPE reais.
    base = dict(tipo_veiculo='Carros', nome_marca='Toyota',
                nome_combustivel='Gasolina', sigla_combustivel='g',
                ano_modelo='2022', zero_km='false',
                ano_referencia='2026', mes_referencia='10')
    return [
        {**base, 'codigo_fipe':'111111-1', 'nome_modelo':'Corolla XEi 2.0', 'valor_centavos':'10000000', 'valor_formatado':'R$ 100.000,00'},
        {**base, 'codigo_fipe':'222222-2', 'nome_modelo':'Corolla GLi 1.8', 'valor_centavos':'9000000', 'valor_formatado':'R$ 90.000,00'},
        {**base, 'codigo_fipe':'111111-1', 'nome_modelo':'Corolla XEi 2.0', 'valor_centavos':'10100000', 'valor_formatado':'R$ 101.000,00', 'mes_referencia':'9'},
        {**base, 'codigo_fipe':'333333-3', 'nome_modelo':'Corolla XEi 2.0', 'valor_centavos':'10500000', 'valor_formatado':'R$ 105.000,00', 'zero_km':'true'},
        {**base, 'codigo_fipe':'444444-4', 'nome_modelo':'Yaris XL', 'valor_centavos':'7000000', 'valor_formatado':'R$ 70.000,00'},
        {**base, 'codigo_fipe':'555555-5', 'nome_modelo':'T-Cross', 'nome_marca':'Volkswagen', 'valor_centavos':'8000000', 'valor_formatado':'R$ 80.000,00'},
        {**base, 'codigo_fipe':'666666-6', 'nome_modelo':'Corolla Cross XRE', 'ano_modelo':'2023', 'valor_centavos':'11000000', 'valor_formatado':'R$ 110.000,00'},
        {**base, 'codigo_fipe':'777777-7', 'nome_modelo':'Corolla XEi 2.0', 'ano_modelo':'2022', 'valor_centavos':'9800000', 'valor_formatado':'R$ 98.000,00', 'nome_combustivel':'Flex', 'sigla_combustivel':'f'},
        {**base, 'codigo_fipe':'888888-8', 'nome_modelo':'Corolla XEi 2.0', 'ano_modelo':'2022', 'valor_centavos':'2000000', 'valor_formatado':'R$ 20.000,00', 'tipo_veiculo':'Motos'},
    ]


@pytest.fixture
def base_populada(tmp_path, monkeypatch, registros):
    monkeypatch.setenv('DATABASE_PATH', str(tmp_path/'fipe.sqlite3'))
    arquivo = tmp_path/'dados.csv'
    with arquivo.open('w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=COLUNAS, delimiter='\t')
        w.writeheader()
        w.writerows(registros)
    return importar(arquivo), arquivo


@pytest.fixture
def cliente(base_populada, monkeypatch):
    monkeypatch.delenv('API_KEY', raising=False)
    with TestClient(app) as c:
        yield c
