"""Integração da API FIPE v1.4.1 com os seletores de versão do NH.
Dados usados são fictícios, conforme fixtures do projeto original.
"""

def test_busca_com_modelo_e_marca(cliente):
    resposta = cliente.get('/buscar-modelos', params={'modelo':'Toyota Corolla', 'ano':2022, 'limite':70})
    assert resposta.status_code == 200
    dados = resposta.json()
    assert dados['referencia'] == {'ano':2026, 'mes':10}
    assert dados['total'] == 5
    assert all('Corolla' in x['nome_modelo'] for x in dados['veiculos'])


def test_busca_sem_marca(cliente):
    dados = cliente.get('/buscar-modelos', params={'modelo':'Corolla XEi', 'ano':2022}).json()
    assert dados['total'] == 4


def test_busca_nao_mistura_anos(cliente):
    dados = cliente.get('/buscar-modelos', params={'modelo':'Corolla', 'ano':2023}).json()
    assert dados['total'] == 1
    assert dados['veiculos'][0]['ano_modelo'] == 2023


def test_busca_sem_injecao_de_like(cliente):
    assert cliente.get('/buscar-modelos', params={'modelo':'Toy%', 'ano':2022}).json()['total'] == 0
    assert cliente.get('/buscar-modelos', params={'modelo':'%', 'ano':2022}).status_code == 422


def test_busca_exige_chave_quando_configurada(cliente, monkeypatch):
    monkeypatch.setenv('API_KEY','teste-seguro')
    q={'modelo':'Corolla','ano':2022}
    assert cliente.get('/buscar-modelos',params=q).status_code == 401
    assert cliente.get('/buscar-modelos',params=q,headers={'X-API-Key':'teste-seguro'}).status_code == 200


def test_ano_tecnico_zero_km_na_busca_integrada(base_populada, registros, tmp_path):
    """NH pode passar ano corrente enquanto a FIPE usa o ano técnico 32000."""
    import csv
    from scripts.importar import importar
    from fastapi.testclient import TestClient
    from app.main import app
    fixture = {**registros[0], 'nome_modelo':'Corolla 0 KM', 'ano_modelo':'32000',
               'zero_km':'true', 'mes_referencia':'11'}
    arquivo = tmp_path / 'zero_novembro.tsv'
    with arquivo.open('w', encoding='utf-8', newline='') as out:
        writer=csv.DictWriter(out, fieldnames=list(fixture), delimiter='\t')
        writer.writeheader();writer.writerow(fixture)
    importar(arquivo, referencia_esperada=(2026,11),minimo_registros=1)
    cli=TestClient(app)
    resposta=cli.get('/buscar-modelos',params={'modelo':'Toyota Corolla','ano':2026,'zero_km':True})
    assert resposta.status_code==200
    assert any(v['ano_modelo']==32000 for v in resposta.json()['veiculos'])
