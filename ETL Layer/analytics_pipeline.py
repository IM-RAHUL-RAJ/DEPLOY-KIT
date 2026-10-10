import argparse
import os
import re
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

import duckdb
import psycopg2
from dotenv import load_dotenv
from psycopg2.extras import RealDictCursor


VALID_SIDES = {"BUY", "SELL"}
VALID_STATUS = {"FILLED", "REJECTED", "CANCELLED", "PENDING", "NEW"}


@dataclass
class PipelineResult:
    extracted: int
    inserted: int
    dead_lettered: int


class PostgresSourceAdapter:
    def __init__(self, host: str, port: str, database: str, username: str, password: str):
        self.host = host
        self.port = port
        self.database = database
        self.username = username
        self.password = password

    def _connect(self):
        conn = psycopg2.connect(
            host=self.host,
            port=self.port,
            database=self.database,
            user=self.username,
            password=self.password,
        )
        schema = _get_env("PG_SCHEMA") or shared_config().get("database.schema", "trading")
        if not re.fullmatch(r"[A-Za-z0-9_ ,.\-]+", schema):
            raise ValueError(f"Unsafe PG_SCHEMA value: {schema!r}")
        with conn.cursor() as cur:
            cur.execute(f"SET search_path TO {schema}")
        return conn

    def _table_exists(self, conn, table_name: str) -> bool:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = current_schema()
                      AND table_name = %s
                )
                """,
                [table_name],
            )
            return bool(cur.fetchone()[0])

    def _orders_timestamp_column(self, conn) -> str:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'orders'
                """
            )
            columns = {row[0] for row in cur.fetchall()}

        for candidate in ["created_on", "received_at", "created_at"]:
            if candidate in columns:
                return candidate

        raise RuntimeError("orders timestamp column is missing")

    def _orders_columns(self, conn) -> set:
        return self._table_columns(conn, "orders")

    def _table_columns(self, conn, table_name: str) -> set:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = %s
                """
                ,
                [table_name],
            )
            return {row[0] for row in cur.fetchall()}

    def _pick_column(self, columns: set, candidates: Sequence[str], label: str) -> str:
        for candidate in candidates:
            if candidate in columns:
                return candidate

        raise RuntimeError(f"orders.{label} is missing (tried: {', '.join(candidates)})")

    def _optional_column(self, columns: set, candidates: Sequence[str]) -> Optional[str]:
        for candidate in candidates:
            if candidate in columns:
                return candidate

        return None

    def get_order_range(self) -> Tuple[Optional[date], Optional[date]]:
        conn = self._connect()
        try:
            if not self._table_exists(conn, "orders"):
                return None, None

            ts_col = self._orders_timestamp_column(conn)

            with conn.cursor() as cur:
                cur.execute(
                    f"SELECT MIN({ts_col})::date, MAX({ts_col})::date FROM orders"
                )
                min_date, max_date = cur.fetchone()
                return min_date, max_date
        finally:
            conn.close()

    def get_instruments_for_orders(self) -> List[dict]:
        conn = self._connect()
        try:
            if not self._table_exists(conn, "orders"):
                return []

            if self._table_exists(conn, "instrument") and "instrument_id" in self._orders_columns(conn):
                instrument_cols = self._table_columns(conn, "instrument")
                symbol_col = self._pick_column(instrument_cols, ["symbol", "ticker"], "instrument symbol")

                name_col = self._optional_column(instrument_cols, ["name", "ticker", "symbol"])
                name_sql = f"i.{name_col}" if name_col is not None else f"i.{symbol_col}"

                asset_col = self._optional_column(instrument_cols, ["asset_class"])
                asset_sql = f"i.{asset_col}" if asset_col is not None else "'UNKNOWN'"

                currency_col = self._optional_column(instrument_cols, ["quote_currency", "currency"])
                currency_sql = f"i.{currency_col}" if currency_col is not None else "'USD'"

                exchange_col = self._optional_column(instrument_cols, ["exchange"])
                if exchange_col is None:
                    exchange_sql = f"CASE WHEN i.{symbol_col} LIKE '%.NS' THEN 'NSE' WHEN i.{symbol_col} LIKE '%.BO' THEN 'BSE' WHEN i.{symbol_col} LIKE 'FX:%' THEN 'FX' WHEN i.{symbol_col} LIKE 'X:%' THEN 'CRYPTO' ELSE 'US' END"
                else:
                    exchange_sql = f"i.{exchange_col}"

                tradable_col = self._optional_column(instrument_cols, ["tradable"])
                if tradable_col is not None:
                    tradable_sql = f"i.{tradable_col}"
                elif "status" in instrument_cols:
                    tradable_sql = "COALESCE(i.status, 'ACTIVE') NOT IN ('DELISTED', 'INACTIVE')"
                else:
                    tradable_sql = "TRUE"

                query = f"""
                    SELECT DISTINCT
                        i.{symbol_col}::varchar AS symbol,
                        COALESCE({name_sql}::varchar, i.{symbol_col}::varchar) AS name,
                        COALESCE({asset_sql}::varchar, 'UNKNOWN') AS asset_class,
                        COALESCE({currency_sql}::varchar, 'USD') AS currency,
                        {exchange_sql}::varchar AS exchange,
                        COALESCE({tradable_sql}, TRUE) AS tradable
                    FROM orders o
                    JOIN instrument i
                      ON i.instrument_id = o.instrument_id
                    WHERE i.{symbol_col} IS NOT NULL
                """
            elif self._table_exists(conn, "instrument"):
                instrument_cols = self._table_columns(conn, "instrument")
                symbol_col = self._pick_column(instrument_cols, ["symbol", "ticker"], "instrument symbol")
                orders_symbol_col = self._pick_column(
                    self._orders_columns(conn), ["ticker", "symbol", "instrument_symbol"], "instrument symbol"
                )

                name_col = self._optional_column(instrument_cols, ["name", "ticker", "symbol"])
                name_sql = f"i.{name_col}" if name_col is not None else f"i.{symbol_col}"

                asset_col = self._optional_column(instrument_cols, ["asset_class"])
                asset_sql = f"i.{asset_col}" if asset_col is not None else "'UNKNOWN'"

                currency_col = self._optional_column(instrument_cols, ["quote_currency", "currency"])
                currency_sql = f"i.{currency_col}" if currency_col is not None else "'USD'"

                exchange_col = self._optional_column(instrument_cols, ["exchange"])
                if exchange_col is None:
                    exchange_sql = f"CASE WHEN i.{symbol_col} LIKE '%.NS' THEN 'NSE' WHEN i.{symbol_col} LIKE '%.BO' THEN 'BSE' WHEN i.{symbol_col} LIKE 'FX:%' THEN 'FX' WHEN i.{symbol_col} LIKE 'X:%' THEN 'CRYPTO' ELSE 'US' END"
                else:
                    exchange_sql = f"i.{exchange_col}"

                tradable_col = self._optional_column(instrument_cols, ["tradable"])
                if tradable_col is not None:
                    tradable_sql = f"i.{tradable_col}"
                elif "status" in instrument_cols:
                    tradable_sql = "COALESCE(i.status, 'ACTIVE') NOT IN ('DELISTED', 'INACTIVE')"
                else:
                    tradable_sql = "TRUE"

                query = f"""
                    SELECT DISTINCT
                        i.{symbol_col}::varchar AS symbol,
                        COALESCE({name_sql}::varchar, i.{symbol_col}::varchar) AS name,
                        COALESCE({asset_sql}::varchar, 'UNKNOWN') AS asset_class,
                        COALESCE({currency_sql}::varchar, 'USD') AS currency,
                        {exchange_sql}::varchar AS exchange,
                        COALESCE({tradable_sql}, TRUE) AS tradable
                    FROM orders o
                    JOIN instrument i
                      ON (i.symbol = o.{orders_symbol_col} OR i.ticker = o.{orders_symbol_col})
                    WHERE i.{symbol_col} IS NOT NULL
                """
            else:
                columns = self._orders_columns(conn)
                symbol_col = self._pick_column(columns, ["instrument_symbol", "symbol", "ticker"], "instrument symbol")

                name_col = self._optional_column(columns, ["instrument_name", "name", "ticker", "symbol"])
                name_sql = f"o.{name_col}" if name_col is not None else f"o.{symbol_col}"

                asset_col = self._optional_column(columns, ["instrument_asset_class", "asset_class"])
                asset_sql = f"o.{asset_col}" if asset_col is not None else "'UNKNOWN'"

                currency_col = self._optional_column(columns, ["instrument_currency", "currency", "quote_currency"])
                currency_sql = f"o.{currency_col}" if currency_col is not None else "'USD'"

                exchange_col = self._optional_column(columns, ["instrument_exchange", "exchange"])
                if exchange_col is None:
                    exchange_sql = f"CASE WHEN o.{symbol_col} LIKE '%.NS' THEN 'NSE' WHEN o.{symbol_col} LIKE '%.BO' THEN 'BSE' WHEN o.{symbol_col} LIKE 'FX:%' THEN 'FX' WHEN o.{symbol_col} LIKE 'X:%' THEN 'CRYPTO' ELSE 'US' END"
                else:
                    exchange_sql = f"o.{exchange_col}"

                tradable_col = self._optional_column(columns, ["instrument_tradable", "tradable"])
                tradable_sql = f"o.{tradable_col}" if tradable_col is not None else "TRUE"

                query = f"""
                    SELECT DISTINCT
                        o.{symbol_col}::varchar AS symbol,
                        COALESCE({name_sql}::varchar, o.{symbol_col}::varchar) AS name,
                        COALESCE({asset_sql}::varchar, 'UNKNOWN') AS asset_class,
                        COALESCE({currency_sql}::varchar, 'USD') AS currency,
                        {exchange_sql}::varchar AS exchange,
                        COALESCE({tradable_sql}, TRUE) AS tradable
                    FROM orders o
                    WHERE o.{symbol_col} IS NOT NULL
                """

            with conn.cursor(cursor_factory=RealDictCursor) as cur:
                cur.execute(query)
                return [dict(row) for row in cur.fetchall()]
        finally:
            conn.close()

    def get_accounts_for_orders(self) -> List[dict]:
        conn = self._connect()
        try:
            if not self._table_exists(conn, "orders"):
                return []

            ts_col = self._orders_timestamp_column(conn)
            orders_columns = self._orders_columns(conn)
            account_id_col = self._pick_column(orders_columns, ["account_id"], "account id")

            if self._table_exists(conn, "account"):
                account_cols = self._table_columns(conn, "account")
                holder_col = self._optional_column(account_cols, ["holder_name", "account_name", "name"])
                holder_sql = f"a.{holder_col}" if holder_col is not None else f"('ACCOUNT-' || a.account_id::varchar)"

                status_col = self._optional_column(account_cols, ["status"])
                account_status_sql = f"a.{status_col}" if status_col is not None else "'ACTIVE'"

                effective_col = self._optional_column(account_cols, ["effective_date", "created_on", "created_at", "opened_at"])
                if effective_col is not None:
                    effective_sql = f"COALESCE(a.{effective_col}::date, MAX(o.{ts_col})::date)"
                else:
                    effective_sql = f"MAX(o.{ts_col})::date"

                source_col = self._optional_column(account_cols, ["source_id", "id", "account_id"])
                source_sql = f"a.{source_col}"

                query = f"""
                    SELECT
                        a.account_id::varchar AS account_id,
                        COALESCE({holder_sql}::varchar, ('ACCOUNT-' || a.account_id::varchar)) AS holder_name,
                        COALESCE({account_status_sql}::varchar, 'ACTIVE') AS status,
                        {effective_sql} AS effective_date,
                        {source_sql}::bigint AS source_id
                    FROM account a
                    JOIN orders o
                      ON o.account_id = a.account_id
                    GROUP BY a.account_id, {holder_sql}, {account_status_sql}, {source_sql}
                """
            else:
                holder_col = self._optional_column(orders_columns, ["holder_name", "account_name", "account_holder_name"])
                holder_sql = f"o.{holder_col}" if holder_col is not None else f"('ACCOUNT-' || o.{account_id_col}::varchar)"

                account_status_col = self._optional_column(orders_columns, ["account_status", "holder_status"])
                account_status_sql = f"o.{account_status_col}" if account_status_col is not None else "'ACTIVE'"

                effective_col = self._optional_column(orders_columns, ["account_effective_date"])
                effective_sql = f"COALESCE(o.{effective_col}::date, o.{ts_col}::date)" if effective_col else f"o.{ts_col}::date"

                source_col = self._optional_column(orders_columns, ["account_source_id"])
                source_sql = f"o.{source_col}" if source_col is not None else f"o.{account_id_col}::bigint"

                query = f"""
                    SELECT DISTINCT
                        o.{account_id_col}::varchar AS account_id,
                        COALESCE({holder_sql}::varchar, ('ACCOUNT-' || o.{account_id_col}::varchar)) AS holder_name,
                        COALESCE({account_status_sql}::varchar, 'ACTIVE') AS status,
                        {effective_sql} AS effective_date,
                        {source_sql}::bigint AS source_id
                    FROM orders o
                    WHERE o.{account_id_col} IS NOT NULL
                """

            with conn.cursor(cursor_factory=RealDictCursor) as cur:
                cur.execute(query)
                return [dict(row) for row in cur.fetchall()]
        finally:
            conn.close()

    def get_orders_since(self, watermark: datetime) -> List[dict]:
        return self._select_orders("o.{ts} > %s", [watermark])

    def get_orders_by_ids(self, source_order_ids: Sequence[str]) -> List[dict]:
        """Re-reads orders by id, for rows the warehouse still holds as NEW."""
        if not source_order_ids:
            return []
        return self._select_orders("o.{id}::varchar = ANY(%s)", [list(source_order_ids)])

    def _select_orders(self, where_template: str, params: list) -> List[dict]:
        conn = self._connect()
        try:
            if not self._table_exists(conn, "orders"):
                return []

            ts_col = self._orders_timestamp_column(conn)
            columns = self._orders_columns(conn)

            source_id_col = self._pick_column(columns, ["source_order_id", "order_id"], "source order id")
            account_id_col = self._pick_column(columns, ["account_id"], "account id")
            side_col = self._pick_column(columns, ["side"], "side")
            quantity_col = self._pick_column(columns, ["quantity"], "quantity")
            price_col = self._pick_column(columns, ["price"], "price")
            executed_price_col = self._optional_column(columns, ["executed_price"])
            status_col = self._pick_column(columns, ["order_status", "status"], "status")
            executed_price_sql = f"o.{executed_price_col}" if executed_price_col else "NULL"

            if self._table_exists(conn, "instrument") and "instrument_id" in columns:
                instrument_cols = self._table_columns(conn, "instrument")
                instrument_symbol_col = self._pick_column(instrument_cols, ["symbol", "ticker"], "instrument symbol")
                symbol_sql = f"i.{instrument_symbol_col}"
                from_sql = "FROM orders o LEFT JOIN instrument i ON i.instrument_id = o.instrument_id"
            else:
                symbol_col = self._pick_column(columns, ["instrument_symbol", "symbol", "ticker"], "instrument symbol")
                symbol_sql = f"o.{symbol_col}"
                from_sql = "FROM orders o"

            with conn.cursor(cursor_factory=RealDictCursor) as cur:
                cur.execute(
                    f"""
                    SELECT
                        o.{source_id_col}::varchar AS source_order_id,
                        o.{account_id_col}::varchar AS account_id,
                        {symbol_sql}::varchar AS instrument_symbol,
                        o.{side_col} AS side,
                        o.{quantity_col} AS quantity,
                        o.{price_col} AS price,
                        {executed_price_sql} AS executed_price,
                        o.{status_col} AS status,
                        o.{ts_col} AS created_at
                    {from_sql}
                    WHERE {where_template.format(ts=ts_col, id=source_id_col)}
                    ORDER BY o.{ts_col}, o.{source_id_col}
                    """,
                    params,
                )
                return [dict(row) for row in cur.fetchall()]
        finally:
            conn.close()


class DuckWarehouse:
    def __init__(self, path: str):
        self.path = path

    def connect(self):
        return duckdb.connect(self.path)

    def ensure_schema(self):
        conn = self.connect()
        try:
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS dim_date (
                    date_key INTEGER NOT NULL PRIMARY KEY,
                    full_date DATE NOT NULL UNIQUE,
                    day INTEGER NOT NULL,
                    month INTEGER NOT NULL,
                    year INTEGER NOT NULL,
                    quarter INTEGER NOT NULL,
                    day_of_week INTEGER NOT NULL,
                    day_name VARCHAR(9) NOT NULL,
                    month_name VARCHAR(9) NOT NULL,
                    is_weekday BOOLEAN NOT NULL
                )
                """
            )

            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS dim_instrument (
                    instrument_key BIGINT NOT NULL PRIMARY KEY,
                    symbol VARCHAR(20) NOT NULL UNIQUE,
                    name VARCHAR(255) NOT NULL,
                    asset_class VARCHAR(20) NOT NULL,
                    currency CHAR(3) NOT NULL,
                    exchange VARCHAR(20),
                    tradable BOOLEAN NOT NULL,
                    loaded_at TIMESTAMP NOT NULL
                )
                """
            )

            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS dim_account (
                    account_key BIGINT NOT NULL PRIMARY KEY,
                    account_id VARCHAR(32) NOT NULL,
                    holder_name VARCHAR(255) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    effective_date DATE NOT NULL,
                    end_date DATE,
                    is_current BOOLEAN NOT NULL,
                    source_id BIGINT NOT NULL,
                    loaded_at TIMESTAMP NOT NULL
                )
                """
            )

            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS fact_trades (
                    trade_key BIGINT NOT NULL PRIMARY KEY,
                    account_key BIGINT NOT NULL,
                    instrument_key BIGINT NOT NULL,
                    date_key INTEGER NOT NULL,
                    side VARCHAR(4) NOT NULL,
                    quantity INTEGER NOT NULL,
                    price DECIMAL(18,2) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    executed_price DECIMAL(18,2),
                    trade_value DECIMAL(18,2) NOT NULL,
                    source_order_id VARCHAR(36) NOT NULL UNIQUE,
                    created_at TIMESTAMP NOT NULL,
                    loaded_at TIMESTAMP NOT NULL
                )
                """
            )

            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS etl_watermark (
                    pipeline_name VARCHAR PRIMARY KEY,
                    last_timestamp TIMESTAMP NOT NULL
                )
                """
            )

            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS dead_letter_trades (
                    source_order_id VARCHAR NOT NULL,
                    reason VARCHAR NOT NULL,
                    batch_id VARCHAR NOT NULL,
                    failed_at TIMESTAMP NOT NULL
                )
                """
            )
        finally:
            conn.close()

    def _next_key(self, conn, table_name: str, key_column: str) -> int:
        return int(
            conn.execute(
                f"SELECT COALESCE(MAX({key_column}), 0) + 1 FROM {table_name}"
            ).fetchone()[0]
        )

    def get_watermark(self) -> datetime:
        conn = self.connect()
        try:
            result = conn.execute(
                """
                SELECT last_timestamp
                FROM etl_watermark
                WHERE pipeline_name = 'trade_load'
                """
            ).fetchone()

            if result is None:
                return datetime(1900, 1, 1)

            return result[0]
        finally:
            conn.close()

    def set_watermark(self, timestamp: datetime):
        conn = self.connect()
        try:
            conn.execute(
                """
                INSERT INTO etl_watermark (pipeline_name, last_timestamp)
                VALUES ('trade_load', ?)
                ON CONFLICT (pipeline_name) DO UPDATE
                SET last_timestamp = excluded.last_timestamp
                """,
                [timestamp],
            )
        finally:
            conn.close()

    def load_dim_date_full_range(self, start_date: Optional[date], end_date: Optional[date]):
        if start_date is None or end_date is None:
            return

        conn = self.connect()
        try:
            current = start_date
            while current <= end_date:
                date_key = int(current.strftime("%Y%m%d"))
                conn.execute(
                    """
                    INSERT INTO dim_date (
                        date_key, full_date, day, month, year, quarter,
                        day_of_week, day_name, month_name, is_weekday
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (date_key) DO NOTHING
                    """,
                    [
                        date_key,
                        current,
                        current.day,
                        current.month,
                        current.year,
                        ((current.month - 1) // 3) + 1,
                        current.weekday() + 1,
                        current.strftime("%A"),
                        current.strftime("%B"),
                        current.weekday() < 5,
                    ],
                )
                current += timedelta(days=1)
        finally:
            conn.close()

    def load_dim_instrument(self, rows: Sequence[dict]):
        conn = self.connect()
        try:
            for row in rows:
                symbol = str(row["symbol"])
                existing = conn.execute(
                    "SELECT instrument_key FROM dim_instrument WHERE symbol = ?",
                    [symbol],
                ).fetchone()

                instrument_key = existing[0] if existing else self._next_key(conn, "dim_instrument", "instrument_key")

                conn.execute(
                    """
                    MERGE INTO dim_instrument t
                    USING (SELECT ? AS symbol) s
                    ON t.symbol = s.symbol
                    WHEN MATCHED THEN UPDATE SET
                        name = ?,
                        asset_class = ?,
                        currency = ?,
                        exchange = ?,
                        tradable = ?,
                        loaded_at = ?
                    WHEN NOT MATCHED THEN INSERT (
                        instrument_key, symbol, name, asset_class, currency,
                        exchange, tradable, loaded_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    [
                        symbol,
                        str(row["name"]),
                        str(row["asset_class"]),
                        str(row["currency"]),
                        row.get("exchange"),
                        bool(row["tradable"]),
                        datetime.now(),
                        instrument_key,
                        symbol,
                        str(row["name"]),
                        str(row["asset_class"]),
                        str(row["currency"]),
                        row.get("exchange"),
                        bool(row["tradable"]),
                        datetime.now(),
                    ],
                )
        finally:
            conn.close()

    def load_dim_account_type2(self, rows: Sequence[dict]):
        conn = self.connect()
        try:
            for row in rows:
                account_id = str(row["account_id"])
                holder_name = str(row["holder_name"])
                status = str(row["status"])
                effective_date = row["effective_date"]
                source_id = int(row["source_id"])

                current = conn.execute(
                    """
                    SELECT account_key, holder_name, status, effective_date
                    FROM dim_account
                    WHERE account_id = ?
                      AND is_current = TRUE
                    """,
                    [account_id],
                ).fetchone()

                if current is None:
                    account_key = self._next_key(conn, "dim_account", "account_key")
                    conn.execute(
                        """
                        INSERT INTO dim_account (
                            account_key, account_id, holder_name, status,
                            effective_date, end_date, is_current, source_id, loaded_at
                        )
                        VALUES (?, ?, ?, ?, ?, NULL, TRUE, ?, ?)
                        """,
                        [
                            account_key,
                            account_id,
                            holder_name,
                            status,
                            effective_date,
                            source_id,
                            datetime.now(),
                        ],
                    )
                    continue

                current_key, current_holder, current_status, current_effective = current
                changed = (
                    str(current_holder) != holder_name
                    or str(current_status) != status
                    or str(current_effective) != str(effective_date)
                )

                if not changed:
                    conn.execute(
                        """
                        UPDATE dim_account
                        SET source_id = ?, loaded_at = ?
                        WHERE account_key = ?
                        """,
                        [source_id, datetime.now(), current_key],
                    )
                    continue

                close_date = effective_date - timedelta(days=1)
                conn.execute(
                    """
                    UPDATE dim_account
                    SET end_date = ?,
                        is_current = FALSE,
                        loaded_at = ?
                    WHERE account_key = ?
                    """,
                    [close_date, datetime.now(), current_key],
                )

                account_key = self._next_key(conn, "dim_account", "account_key")
                conn.execute(
                    """
                    INSERT INTO dim_account (
                        account_key, account_id, holder_name, status,
                        effective_date, end_date, is_current, source_id, loaded_at
                    )
                    VALUES (?, ?, ?, ?, ?, NULL, TRUE, ?, ?)
                    """,
                    [
                        account_key,
                        account_id,
                        holder_name,
                        status,
                        effective_date,
                        source_id,
                        datetime.now(),
                    ],
                )
        finally:
            conn.close()

    def key_maps(self) -> Tuple[Dict[str, int], Dict[str, int], Dict[date, int]]:
        conn = self.connect()
        try:
            account_map = {
                str(row[0]): int(row[1])
                for row in conn.execute(
                    "SELECT account_id, account_key FROM dim_account WHERE is_current = TRUE"
                ).fetchall()
            }
            instrument_map = {
                str(row[0]): int(row[1])
                for row in conn.execute(
                    "SELECT symbol, instrument_key FROM dim_instrument"
                ).fetchall()
            }
            date_map = {
                row[0]: int(row[1])
                for row in conn.execute(
                    "SELECT full_date, date_key FROM dim_date"
                ).fetchall()
            }
            return account_map, instrument_map, date_map
        finally:
            conn.close()

    def open_source_order_ids(self) -> set:
        """Orders loaded while NEW: their status may have moved since."""
        conn = self.connect()
        try:
            return {
                str(row[0])
                for row in conn.execute(
                    "SELECT source_order_id FROM fact_trades WHERE status IN ('NEW', 'PENDING')"
                ).fetchall()
            }
        finally:
            conn.close()

    def existing_source_order_ids(self) -> set:
        conn = self.connect()
        try:
            return {
                str(row[0])
                for row in conn.execute("SELECT source_order_id FROM fact_trades").fetchall()
            }
        finally:
            conn.close()

    def upsert_fact_rows(self, rows: Sequence[dict]):
        conn = self.connect()
        try:
            for row in rows:
                existing = conn.execute(
                    "SELECT trade_key FROM fact_trades WHERE source_order_id = ?",
                    [row["source_order_id"]],
                ).fetchone()
                trade_key = existing[0] if existing else self._next_key(conn, "fact_trades", "trade_key")

                conn.execute(
                    """
                    MERGE INTO fact_trades t
                    USING (SELECT ? AS source_order_id) s
                    ON t.source_order_id = s.source_order_id
                    WHEN MATCHED THEN UPDATE SET
                        account_key = ?,
                        instrument_key = ?,
                        date_key = ?,
                        side = ?,
                        quantity = ?,
                        price = ?,
                        status = ?,
                        executed_price = ?,
                        trade_value = ?,
                        created_at = ?,
                        loaded_at = ?
                    WHEN NOT MATCHED THEN INSERT (
                        trade_key, account_key, instrument_key, date_key, side,
                        quantity, price, status, executed_price, trade_value,
                        source_order_id, created_at, loaded_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    [
                        row["source_order_id"],
                        row["account_key"],
                        row["instrument_key"],
                        row["date_key"],
                        row["side"],
                        row["quantity"],
                        row["price"],
                        row["status"],
                        row["executed_price"],
                        row["trade_value"],
                        row["created_at"],
                        datetime.now(),
                        trade_key,
                        row["account_key"],
                        row["instrument_key"],
                        row["date_key"],
                        row["side"],
                        row["quantity"],
                        row["price"],
                        row["status"],
                        row["executed_price"],
                        row["trade_value"],
                        row["source_order_id"],
                        row["created_at"],
                        datetime.now(),
                    ],
                )
        finally:
            conn.close()

    def insert_dead_letters(self, rows: Sequence[dict], batch_id: str):
        if not rows:
            return

        conn = self.connect()
        try:
            for row in rows:
                conn.execute(
                    """
                    INSERT INTO dead_letter_trades (
                        source_order_id, reason, batch_id, failed_at
                    )
                    VALUES (?, ?, ?, ?)
                    """,
                    [
                        str(row["source_order_id"]),
                        str(row["reason"]),
                        batch_id,
                        datetime.now(),
                    ],
                )
        finally:
            conn.close()


def _to_decimal(value) -> Optional[Decimal]:
    if value is None:
        return None

    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None


def _normalize_side(value) -> str:
    return str(value).strip().upper() if value is not None else ""


def _normalize_status(value) -> str:
    return str(value).strip().upper() if value is not None else ""


def _validate_and_map_fact_row(
    order_row: dict,
    account_map: Dict[str, int],
    instrument_map: Dict[str, int],
    date_map: Dict[date, int],
    existing_source_ids: set,
    batch_source_ids: set,
) -> Tuple[Optional[dict], Optional[str]]:
    source_order_id = str(order_row.get("source_order_id") or "").strip()
    if source_order_id == "":
        return None, "Missing source_order_id"

    if source_order_id in existing_source_ids:
        return None, "Duplicate source_order_id already loaded"

    if source_order_id in batch_source_ids:
        return None, "Duplicate source_order_id in batch"

    account_id = str(order_row.get("account_id") or "").strip()
    instrument_symbol = str(order_row.get("instrument_symbol") or "").strip()

    account_key = account_map.get(account_id)
    if account_key is None:
        return None, "Missing account dimension"

    instrument_key = instrument_map.get(instrument_symbol)
    if instrument_key is None:
        return None, "Missing instrument dimension"

    created_at = order_row.get("created_at")
    if created_at is None:
        return None, "Missing created_at"

    trade_date = created_at.date()
    date_key = date_map.get(trade_date)
    if date_key is None:
        return None, "Missing date dimension"

    quantity_decimal = _to_decimal(order_row.get("quantity"))
    if quantity_decimal is None:
        return None, "Invalid quantity type"

    if quantity_decimal <= 0:
        return None, "Quantity must be positive"

    if quantity_decimal != quantity_decimal.to_integral_value():
        return None, "Quantity must be integer"

    price = _to_decimal(order_row.get("price"))
    if price is None:
        return None, "Invalid price type"

    executed_price = _to_decimal(order_row.get("executed_price"))
    if executed_price is not None and executed_price <= 0:
        executed_price = None

    side = _normalize_side(order_row.get("side"))
    if side not in VALID_SIDES:
        return None, "Invalid side"

    status = _normalize_status(order_row.get("status"))
    if status not in VALID_STATUS:
        return None, "Invalid status"

    # Market orders carry a zero limit price; the executed price (when filled)
    # is the authoritative value. Pending/rejected/cancelled market orders have
    # no fill, so their trade value is zero and they are still reported.
    effective_price = executed_price if executed_price is not None else price
    if effective_price <= 0:
        if status in {"NEW", "PENDING", "REJECTED", "CANCELLED"}:
            effective_price = Decimal(0)
        else:
            return None, "No executable price"

    recomputed_trade_value = quantity_decimal * effective_price

    trade_value = _to_decimal(order_row.get("trade_value"))
    if trade_value is None:
        trade_value = recomputed_trade_value

    if trade_value != recomputed_trade_value:
        return None, "Trade value mismatch"

    batch_source_ids.add(source_order_id)

    return {
        "source_order_id": source_order_id,
        "account_key": account_key,
        "instrument_key": instrument_key,
        "date_key": date_key,
        "side": side,
        "quantity": int(quantity_decimal),
        "price": price,
        "status": status,
        "executed_price": executed_price,
        "trade_value": trade_value,
        "created_at": created_at,
    }, None


class Pipeline:
    def __init__(self, source: PostgresSourceAdapter, warehouse: DuckWarehouse):
        self.source = source
        self.warehouse = warehouse

    def initialize(self):
        self.warehouse.ensure_schema()

    def run_dim_date(self):
        self.initialize()
        start_date, end_date = self.source.get_order_range()
        self.warehouse.load_dim_date_full_range(start_date, end_date)

    def run_dimensions(self):
        self.initialize()
        instrument_rows = self.source.get_instruments_for_orders()
        account_rows = self.source.get_accounts_for_orders()
        self.warehouse.load_dim_instrument(instrument_rows)
        self.warehouse.load_dim_account_type2(account_rows)

    def run_facts(self) -> PipelineResult:
        self.initialize()

        watermark = self.warehouse.get_watermark()
        new_orders = self.source.get_orders_since(watermark)

        # An order loaded while NEW is filled, rejected or cancelled later, and its
        # created_at never moves past the watermark again. Re-read every order the
        # warehouse still holds as open so its final status reaches fact_trades.
        open_ids = self.warehouse.open_source_order_ids()
        new_ids = {str(o.get("source_order_id")) for o in new_orders}
        reread = self.source.get_orders_by_ids(sorted(open_ids - new_ids))
        orders = new_orders + reread

        if not orders:
            return PipelineResult(extracted=0, inserted=0, dead_lettered=0)

        account_map, instrument_map, date_map = self.warehouse.key_maps()
        # Open orders are updatable; anything else already loaded is a duplicate.
        existing_source_ids = self.warehouse.existing_source_order_ids() - open_ids

        valid_rows: List[dict] = []
        dead_rows: List[dict] = []
        batch_source_ids = set()

        for order in orders:
            mapped, reason = _validate_and_map_fact_row(
                order,
                account_map,
                instrument_map,
                date_map,
                existing_source_ids,
                batch_source_ids,
            )

            if reason:
                dead_rows.append(
                    {
                        "source_order_id": order.get("source_order_id"),
                        "reason": reason,
                    }
                )
                continue

            valid_rows.append(mapped)

        if valid_rows:
            self.warehouse.upsert_fact_rows(valid_rows)

        # The watermark only advances over newly created orders, from the data
        # itself rather than the loader's clock.
        batch_time = max(order["created_at"] for order in orders)
        if dead_rows:
            self.warehouse.insert_dead_letters(dead_rows, batch_time.isoformat())
        if new_orders:
            self.warehouse.set_watermark(max(order["created_at"] for order in new_orders))

        return PipelineResult(
            extracted=len(orders),
            inserted=len(valid_rows),
            dead_lettered=len(dead_rows),
        )

    def run(self, stage: str):
        if stage == "dim_date":
            self.run_dim_date()
            return

        if stage == "dimensions":
            self.run_dimensions()
            return

        if stage == "facts":
            self.run_facts()
            return

        self.run_dim_date()
        self.run_dimensions()
        self.run_facts()


def _get_env(*names: str, default: Optional[str] = None) -> Optional[str]:
    for name in names:
        value = os.getenv(name)
        if value not in (None, ""):
            return value
    return default


ETL_DIR = Path(__file__).resolve().parent


def parse_shared_config(text: str) -> dict:
    """Flattens the plain `key: value` YAML of Config/application.yml (nesting by
    indentation, comments, no lists) into dotted keys such as `database.host`."""
    out: dict = {}
    stack: list = []
    for raw in text.splitlines():
        line = re.sub(r"\s+#.*$", "", raw)
        if not line.strip() or line.strip().startswith("#"):
            continue
        m = re.match(r"^(\s*)([A-Za-z0-9_.-]+):\s*(.*)$", line)
        if not m:
            continue
        indent = len(m.group(1))
        while stack and stack[-1][0] >= indent:
            stack.pop()
        key = ".".join([k for _, k in stack] + [m.group(2)])
        value = m.group(3).strip().strip("'\"")
        if value:
            out[key] = value
        else:
            stack.append((indent, m.group(2)))
    return out


def shared_config() -> dict:
    """The platform's shared Config/application.yml (SHARED_CONFIG, else Application/Config)."""
    candidates = [os.getenv("SHARED_CONFIG"), str(ETL_DIR.parent / "Config" / "application.yml")]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return parse_shared_config(Path(candidate).read_text(encoding="utf-8"))
    return {}


def _setting(env_names: tuple, shared_key: Optional[str], required: bool = True) -> Optional[str]:
    """An environment variable (.env), else the shared Config value; never a guess."""
    value = _get_env(*env_names) or (shared_config().get(shared_key) if shared_key else None)
    if required and not value:
        where = f" or {shared_key} in Config/application.yml" if shared_key else ""
        raise RuntimeError(f"{env_names[-1]} is not set: add it to Application/.env{where}")
    return value


def _default_duckdb_path() -> str:
    configured = _setting(("DUCKDB_FILE", "DUCKDB_PATH"), "etl.duckdb-path")
    path = Path(configured)
    # A relative path is relative to the ETL Layer folder, wherever it is started from.
    return str(path if path.is_absolute() else (ETL_DIR / path).resolve())


def build_pipeline() -> Pipeline:

    source = PostgresSourceAdapter(
        host=_setting(("POSTGRES_HOST", "DB_HOST"), "database.host"),
        port=_setting(("POSTGRES_PORT", "DB_PORT"), "database.port"),
        database=_setting(("POSTGRES_DATABASE", "DB_NAME"), "database.name"),
        username=_setting(("POSTGRES_USERNAME", "DB_USER"), "database.user"),
        password=_setting(("POSTGRES_PASSWORD", "DB_PASSWORD"), None),
    )

    warehouse = DuckWarehouse(path=_default_duckdb_path())

    return Pipeline(source=source, warehouse=warehouse)


def parse_args():
    parser = argparse.ArgumentParser(description="Sprint 7 incremental ETL")
    parser.add_argument(
        "--stage",
        choices=["all", "dim_date", "dimensions", "facts"],
        default="all",
        help="Run one stage or the full pipeline",
    )
    return parser.parse_args()


def main():
    load_dotenv()
    args = parse_args()
    pipeline = build_pipeline()
    pipeline.run(args.stage)


if __name__ == "__main__":
    main()