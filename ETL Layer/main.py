"""ETL Layer entry point: loads the trading database into the DuckDB star schema.

Runs one incremental pass (--once) or keeps refreshing on an interval. Each pass
loads dim_date, then dim_instrument and dim_account (Type 2), then fact_trades
from orders created since the watermark plus every order the warehouse still
holds as NEW, so a fill, rejection or cancellation is picked up after the fact.

    cd "ETL Layer"
    pip install -r requirements.txt
    python main.py            # refresh loop
    python main.py --once     # single pass

Configuration, from ETL Layer/.env, the sprint root .env, or the environment:
  DUCKDB_PATH                    DuckDB file (default: <sprint root>/analytics.duckdb)
  ETL_REFRESH_INTERVAL_SECONDS   seconds between passes (default 45)
  DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD   Postgres connection
"""
import argparse
import logging
import os
import sys
import time
from pathlib import Path

from dotenv import load_dotenv

from analytics_pipeline import build_pipeline, shared_config

ETL_DIR = Path(__file__).resolve().parent
SPRINT_ROOT = ETL_DIR.parent
LOCK_FILE = ETL_DIR / ".etl.lock"
LOGGER = logging.getLogger("etl")


def _configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%Y-%m-%dT%H:%M:%S",
        stream=sys.stdout,
    )


def _load_env() -> None:
    # The ETL's own .env wins over the shared one; real environment variables win over both.
    for candidate in (ETL_DIR / ".env", SPRINT_ROOT / ".env"):
        if candidate.is_file():
            load_dotenv(candidate, override=False)


def run_once() -> None:
    pipeline = build_pipeline()
    pipeline.initialize()
    pipeline.run_dim_date()
    pipeline.run_dimensions()
    result = pipeline.run_facts()
    LOGGER.info(
        "ETL pass complete: extracted=%d loaded=%d dead_lettered=%d",
        result.extracted,
        result.inserted,
        result.dead_lettered,
    )


def _pid_alive(pid: int) -> bool:
    if sys.platform == "win32":
        import subprocess

        probe = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/NH"],
            capture_output=True,
            text=True,
            creationflags=0x08000000,  # CREATE_NO_WINDOW
        )
        return str(pid) in probe.stdout
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def _another_instance_running() -> bool:
    """Two loaders writing one DuckDB file would fight over its lock."""
    if not LOCK_FILE.exists():
        return False
    try:
        return _pid_alive(int(LOCK_FILE.read_text(encoding="utf-8").strip()))
    except (ValueError, OSError):
        return False


def main() -> None:
    parser = argparse.ArgumentParser(description="Incremental DuckDB ETL for the trading platform")
    parser.add_argument("--once", action="store_true", help="Run a single ETL pass, then exit")
    args = parser.parse_args()

    _configure_logging()
    _load_env()

    if _another_instance_running():
        LOGGER.info("Another ETL process holds %s; exiting", LOCK_FILE)
        return
    LOCK_FILE.write_text(str(os.getpid()), encoding="utf-8")

    try:
        if args.once:
            run_once()
            return
        refresh_seconds = int(os.getenv("ETL_REFRESH_INTERVAL_SECONDS")
                              or shared_config().get("etl.refresh-interval-seconds", "45"))
        LOGGER.info("ETL refresh loop every %s seconds", refresh_seconds)
        while True:
            try:
                run_once()
            except Exception:
                LOGGER.exception("ETL pass failed; retrying next interval")
            time.sleep(refresh_seconds)
    finally:
        try:
            LOCK_FILE.unlink()
        except OSError:
            pass


if __name__ == "__main__":
    main()
