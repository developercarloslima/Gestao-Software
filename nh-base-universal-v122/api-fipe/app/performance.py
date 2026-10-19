"""Conexões SQLite reutilizáveis por thread e cache LRU com TTL curto.

O cache é apenas uma aceleração. O banco SQLite continua sendo a fonte da verdade;
como o importador roda em OUTRO processo, entradas expiram naturalmente em poucos
segundos, inclusive em republicações de uma referência já existente.
"""
from __future__ import annotations

import os
import sqlite3
import threading
import time
from collections import OrderedDict
from contextlib import contextmanager
from typing import Any, Callable, Iterator

from app.database import database_path

_local = threading.local()


def _int_env(name: str, default: int, minimum: int = 0) -> int:
    valor = int(os.environ.get(name, str(default)))
    if valor < minimum:
        raise ValueError(f'{name} precisa ser >= {minimum}')
    return valor


def conexao_leitura() -> sqlite3.Connection:
    """Uma conexão read-only por thread, reaberta se o caminho do DB mudar.

    isolation_level=None evita transações persistentes de leitura que atrasariam
    checkpoints ou ocultariam snapshots novos.
    """
    path = database_path()
    if not path.is_file():
        antiga = getattr(_local, 'con', None)
        if antiga is not None:
            antiga.close()
            _local.con = None
        raise FileNotFoundError('Banco de dados ainda não foi importado.')
    if getattr(_local, 'db_path', None) != str(path) or getattr(_local, 'con', None) is None:
        antiga = getattr(_local, 'con', None)
        if antiga is not None:
            antiga.close()
        c = sqlite3.connect(path.as_uri() + '?mode=ro', uri=True,
                            timeout=15, isolation_level=None, cached_statements=256)
        c.row_factory = sqlite3.Row
        c.execute('PRAGMA query_only=ON')
        c.execute('PRAGMA busy_timeout=15000')
        c.execute('PRAGMA cache_size=-2048')
        c.execute('PRAGMA temp_store=MEMORY')
        c.execute('PRAGMA mmap_size=268435456')
        _local.con, _local.db_path = c, str(path)
    return _local.con


@contextmanager
def leitura() -> Iterator[sqlite3.Connection]:
    """Contexto que não fecha a conexão, pois ela será reutilizada pela thread."""
    yield conexao_leitura()


class CacheConsultas:
    def __init__(self, max_entries: int = 4000, ttl: float = 3.0,
                 clock: Callable[[], float] = time.monotonic):
        if max_entries < 0 or ttl < 0:
            raise ValueError('Cache deve ter entradas e TTL não negativos')
        self.max_entries = max_entries
        self.ttl = ttl
        self.clock = clock
        self._items: OrderedDict[tuple, tuple[float, Any]] = OrderedDict()
        self._lock = threading.Lock()
        self.hits = 0
        self.misses = 0

    def get_or_load(self, key: tuple, loader: Callable[[], Any]) -> Any:
        if not self.max_entries or self.ttl == 0:
            return loader()
        with self._lock:
            now = self.clock()
            value = self._items.get(key)
            if value is not None and value[0] > now:
                self._items.move_to_end(key)
                self.hits += 1
                return value[1]
            if value is not None:
                del self._items[key]
            self.misses += 1
        result = loader()
        # Respostas com centenas/milhares de registros não devem dominar RAM.
        if isinstance(result, tuple) and len(result) > 200:
            return result
        with self._lock:
            self._items[key] = (self.clock() + self.ttl, result)
            self._items.move_to_end(key)
            while len(self._items) > self.max_entries:
                self._items.popitem(last=False)
        return result

    def clear(self) -> None:
        with self._lock:
            self._items.clear()
            self.hits = self.misses = 0


cache = CacheConsultas(max_entries=_int_env('QUERY_CACHE_MAX_ENTRIES', 4000),
                      ttl=float(os.environ.get('QUERY_CACHE_TTL_SECONDS', '3.0')))


def consulta_cacheada(c: sqlite3.Connection, sql: str, args: list | tuple, *, unica: bool = False):
    """Memoriza snapshots de SELECT por poucos segundos; mantém tipos sqlite3.Row."""
    # O caminho protege contra contaminação ao trocar o banco nos testes ou na VPS.
    key = (str(database_path()), sql, tuple(args), unica)

    def carregar():
        cursor = c.execute(sql, args)
        return cursor.fetchone() if unica else tuple(cursor.fetchall())

    return cache.get_or_load(key, carregar)


class CacheHTTP:
    """Cache de corpos JSON prontos, limitado e com expiração curta.

    Chaves incluem caminho do banco e todos os filtros, e a checagem de API Key
    acontece SEMPRE antes de consultar o cache. Diferentes workers têm seus
    próprios caches; reimportações aparecem após o TTL (padrão 3 segundos).
    """
    def __init__(self, max_entries: int = 1024, ttl: float = 3.0,
                 max_bytes_per_entry: int = 131072,
                 clock: Callable[[], float] = time.monotonic):
        if max_entries < 0 or ttl < 0 or max_bytes_per_entry < 0:
            raise ValueError('Configuração inválida do cache HTTP.')
        self.max_entries, self.ttl = max_entries, ttl
        self.max_bytes_per_entry = max_bytes_per_entry
        self.clock = clock
        self._lock = threading.Lock()
        self._items: OrderedDict[tuple, tuple[float, bytes]] = OrderedDict()
        self.hits = 0
        self.misses = 0

    def get(self, key: tuple) -> bytes | None:
        if not self.max_entries or not self.ttl:
            return None
        with self._lock:
            item = self._items.get(key)
            if item is None:
                self.misses += 1
                return None
            if item[0] <= self.clock():
                del self._items[key]
                self.misses += 1
                return None
            self._items.move_to_end(key)
            self.hits += 1
            return item[1]

    def put(self, key: tuple, body: bytes) -> None:
        if (not self.max_entries or not self.ttl or
                len(body) > self.max_bytes_per_entry):
            return
        with self._lock:
            self._items[key] = (self.clock() + self.ttl, body)
            self._items.move_to_end(key)
            while len(self._items) > self.max_entries:
                self._items.popitem(last=False)

    def clear(self) -> None:
        with self._lock:
            self._items.clear()
            self.hits = self.misses = 0


cache_http = CacheHTTP(
    max_entries=_int_env('HTTP_CACHE_MAX_ENTRIES', 1024),
    ttl=float(os.getenv('HTTP_CACHE_TTL_SECONDS', '3')),
    max_bytes_per_entry=_int_env('HTTP_CACHE_MAX_RESPONSE_BYTES', 131072),
)
