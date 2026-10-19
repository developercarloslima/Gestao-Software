"""Os números reportados pelo benchmark não são valores de produção inventados."""
from benchmarks.carga import extrair_tempo_servidor


def test_server_timing_ausente_ou_malformado():
    assert extrair_tempo_servidor(None) is None
    assert extrair_tempo_servidor('db;dur=5') is None
    assert extrair_tempo_servidor('consulta;dur=nan') is None


def test_server_timing_valido():
    assert extrair_tempo_servidor('consulta;dur=0.025') == 0.025
    assert extrair_tempo_servidor('db;dur=8, consulta;dur=23.5') == 23.5
