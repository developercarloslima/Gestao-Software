from fastapi.testclient import TestClient
from app.main import app


def test_health_sem_dados(tmp_path, monkeypatch):
    monkeypatch.setenv('DATABASE_PATH', str(tmp_path/'nada.db'))
    with TestClient(app) as client:
        assert client.get('/health').json()['base_carregada'] is False
        assert client.get('/consulta', params={'marca':'x','modelo':'y','ano':2022}).status_code == 503


def test_importa_e_metadados(cliente):
    assert cliente.get('/health').json()['status'] == 'ok'
    r = cliente.get('/metadados').json()
    assert r['referencia_mais_recente'] == {'ano': 2026, 'mes': 10}
    assert r['total_registros'] == 9
    assert r['registros_na_referencia'] == 8


def test_lista_marcas_modelos_anos(cliente):
    assert cliente.get('/tipos').json()['tipos'] == ['Carros','Motos']
    assert cliente.get('/marcas').json()['marcas'] == ['Toyota','Volkswagen']
    assert cliente.get('/modelos', params={'marca':'TOYOTA','busca':'corolla'}).json()['modelos'] == ['Corolla Cross XRE', 'Corolla GLi 1.8', 'Corolla XEi 2.0']
    assert cliente.get('/anos', params={'marca':'Toyota', 'modelo':'Corolla'}).json()['anos'] == [2023,2022]


def test_consulta_retorna_multiplas_versoes(cliente):
    r = cliente.get('/consulta', params={'marca':'TOYOTA', 'modelo':'Corolla', 'ano':2022})
    assert r.status_code == 200
    j = r.json()
    assert j['total'] == 5
    assert j['multiplas_versoes'] is True
    assert {v['codigo_fipe'] for v in j['veiculos']} == {'111111-1','222222-2','333333-3','777777-7','888888-8'}
    assert all(v['mes_referencia'] == 10 for v in j['veiculos'])


def test_filtro_categoria_combustivel_e_zero(cliente):
    params = {'marca':'Toyota','modelo':'Corolla','ano':2022, 'tipo':'Carros', 'combustivel':'Gasolina', 'zero_km':False}
    j = cliente.get('/consulta', params=params).json()
    assert j['total'] == 2
    assert all(not v['zero_km'] for v in j['veiculos'])
    assert j['veiculos'][0]['valor_centavos'] in (9000000,10000000)


def test_filtro_mes_historico(cliente):
    j = cliente.get('/consulta', params={'marca':'Toyota','modelo':'Corolla','ano':2022, 'mes_referencia':9, 'ano_referencia':2026}).json()
    assert j['total'] == 1
    assert j['veiculos'][0]['valor_centavos'] == 10100000
    r = cliente.get('/historico', params={'codigo_fipe':'111111-1','ano':2022}).json()
    assert r['total'] == 2
    assert [v['mes_referencia'] for v in r['precos']] == [10,9]


def test_limit_offset(cliente):
    params = {'marca':'Toyota','modelo':'Corolla','ano':2022, 'limite':2, 'offset':2}
    j = cliente.get('/consulta', params=params).json()
    assert j['total'] == 5
    assert len(j['veiculos']) == 2


def test_validacao(cliente):
    p = {'marca':'Toyota','modelo':'Corolla','ano':2022,'ano_referencia':2026}
    assert cliente.get('/consulta', params=p).status_code == 422
    assert cliente.get('/consulta', params={**p, 'mes_referencia':13}).status_code == 422
    assert cliente.get('/consulta', params={**p, 'mes_referencia':10, 'limite':999999}).status_code == 422


def test_api_key(cliente, monkeypatch):
    monkeypatch.setenv('API_KEY','chave-secreta')
    assert cliente.get('/consulta', params={'marca':'Toyota','modelo':'Corolla','ano':2022}).status_code == 401
    r = cliente.get('/marcas', headers={'x-api-key':'chave-secreta'})
    assert r.status_code == 200


def test_sem_injecao_like(cliente):
    j = cliente.get('/consulta', params={'marca':'Toyota','modelo':'%','ano':2022}).json()
    assert j['total'] == 0
    assert cliente.get('/consulta', params={'marca':'Toyota','modelo':'','ano':2022}).status_code == 422


def test_docs(cliente):
    assert cliente.get('/openapi.json').status_code == 200
