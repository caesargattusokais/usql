"""Pytest configuration and fixtures."""
import pytest
from usql.compiler import USqlCompiler
from usql.dialect.dialect import Dialect


@pytest.fixture
def compiler() -> USqlCompiler:
    """Provide a USqlCompiler instance."""
    return USqlCompiler()


@pytest.fixture(params=[d for d in Dialect if d != Dialect.H2])
def dialect(request) -> Dialect:
    """Parametrize over all non-H2 dialects."""
    return request.param
