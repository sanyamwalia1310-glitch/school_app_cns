"""Small DB-API compatibility layer for SQLite development and PostgreSQL.

Application SQL continues to use DB-API ``?`` parameters.  Parameters are
always passed separately from SQL; this module only adapts their placeholder
syntax for psycopg and never interpolates values into a query.
"""

from __future__ import annotations

import sqlite3
from collections.abc import Iterable, Mapping


def is_postgres_url(database_url: str | None) -> bool:
    return bool(database_url and database_url.lower().startswith(("postgres://", "postgresql://")))


def integrity_errors():
    """Return database integrity exception classes available in this runtime."""
    try:
        from psycopg import IntegrityError as PostgresIntegrityError
    except ImportError:  # pragma: no cover - psycopg is a production dependency.
        return (sqlite3.IntegrityError,)
    return (sqlite3.IntegrityError, PostgresIntegrityError)


def _postgres_sql(sql: str) -> str:
    """Translate DB-API qmark placeholders without touching quoted text."""
    result: list[str] = []
    quote: str | None = None
    index = 0
    while index < len(sql):
        char = sql[index]
        if quote:
            result.append(char)
            if char == quote:
                # SQL escapes a quote by doubling it.
                if index + 1 < len(sql) and sql[index + 1] == quote:
                    result.append(sql[index + 1])
                    index += 1
                else:
                    quote = None
        elif char in ("'", '"'):
            quote = char
            result.append(char)
        elif char == "?":
            result.append("%s")
        else:
            result.append(char)
        index += 1
    return "".join(result)


class CompatRow(dict):
    """Dictionary row that also supports the sqlite3.Row numeric convention."""

    def __init__(self, values: Mapping):
        super().__init__(values)
        self._values = tuple(values.values())

    def __getitem__(self, key):
        if isinstance(key, int):
            return self._values[key]
        return super().__getitem__(key)


class CursorAdapter:
    def __init__(self, cursor, postgres: bool):
        self._cursor = cursor
        self._postgres = postgres

    @property
    def rowcount(self):
        return self._cursor.rowcount

    def fetchone(self):
        row = self._cursor.fetchone()
        return CompatRow(row) if self._postgres and row is not None else row

    def fetchall(self):
        rows = self._cursor.fetchall()
        return [CompatRow(row) for row in rows] if self._postgres else rows

    def __iter__(self):
        return iter(self.fetchall())


class ConnectionAdapter:
    def __init__(self, connection, postgres: bool):
        self._connection = connection
        self.postgres = postgres

    def execute(self, sql: str, parameters: Iterable | None = None):
        cursor = self._connection.cursor()
        cursor.execute(_postgres_sql(sql) if self.postgres else sql, parameters or ())
        return CursorAdapter(cursor, self.postgres)

    def executemany(self, sql: str, parameters):
        cursor = self._connection.cursor()
        cursor.executemany(_postgres_sql(sql) if self.postgres else sql, parameters)
        return CursorAdapter(cursor, self.postgres)

    def commit(self):
        self._connection.commit()

    def rollback(self):
        self._connection.rollback()

    def close(self):
        self._connection.close()


def connect(database_path: str, database_url: str | None) -> ConnectionAdapter:
    if is_postgres_url(database_url):
        import psycopg
        from psycopg.rows import dict_row

        return ConnectionAdapter(psycopg.connect(database_url, row_factory=dict_row), postgres=True)

    connection = sqlite3.connect(database_path)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    return ConnectionAdapter(connection, postgres=False)
