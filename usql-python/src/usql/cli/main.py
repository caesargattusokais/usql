"""USQL Command Line Interface."""
from __future__ import annotations

import os
from pathlib import Path

import click

from usql.compiler import USqlCompiler
from usql.dialect.dialect import Dialect


@click.group()
@click.version_option()
def cli():
    """USQL — Universal SQL Compiler. Write once, run on 11 databases."""
    pass


@cli.command()
@click.option("--sql", required=True, help="SQL statement to translate")
@click.option("--to", required=True, type=click.Choice([d.name.lower() for d in Dialect if d != Dialect.H2]),
              help="Target dialect")
def translate(sql: str, to: str):
    """Translate a SQL statement to target dialect."""
    compiler = USqlCompiler()
    result = compiler.compile(sql, Dialect[to.upper()])
    if result.success:
        click.echo(result.sql)
    else:
        click.echo(result.report(), err=True)
        raise SystemExit(1)


@cli.command()
@click.option("--to", required=True, type=click.Choice([d.name.lower() for d in Dialect if d != Dialect.H2]),
              help="Target dialect")
@click.option("--input", "input_dir", required=True, type=click.Path(exists=True),
              help="Input directory with .sql files")
@click.option("--output", "output_dir", required=True, type=click.Path(),
              help="Output directory for translated SQL files")
def migrate(to: str, input_dir: str, output_dir: str):
    """Batch migrate SQL files from one dialect to another."""
    compiler = USqlCompiler()
    target = Dialect[to.upper()]
    in_path = Path(input_dir)
    out_path = Path(output_dir)
    out_path.mkdir(parents=True, exist_ok=True)

    for sql_file in in_path.glob("*.sql"):
        try:
            usql = sql_file.read_text(encoding="utf-8")
            result = compiler.compile(usql, target)
            out_file = out_path / sql_file.name
            if result.success:
                out_file.write_text(result.sql, encoding="utf-8")
                click.echo(f"  OK: {sql_file.name}")
            else:
                click.echo(f"  FAIL: {sql_file.name} — {result.report()}")
        except Exception as e:
            click.echo(f"  ERR: {sql_file.name} — {e}")


@cli.command()
@click.option("--sql", required=True, help="SQL statement to verify")
@click.option("--to", required=True, type=click.Choice([d.name.lower() for d in Dialect if d != Dialect.H2]),
              help="Target dialect")
def verify(sql: str, to: str):
    """Verify a SQL statement against a target dialect."""
    compiler = USqlCompiler(verify=True)
    result = compiler.compile(sql, Dialect[to.upper()])
    click.echo("✅ Valid" if result.success else f"❌ {result.report()}")


@cli.command()
def dialects():
    """List supported dialects."""
    click.echo("Supported dialects:")
    for d in Dialect:
        if d == Dialect.H2:
            continue
        click.echo(f"  {d.name:<12} — {d.display_name}")


if __name__ == "__main__":
    cli()
