"""IR model tests."""
import pytest
from usql.ir.types import IntType, DecimalType, FloatType, VarcharType, BooleanType, NullType
from usql.ir.expr import IRLiteral, IRColumnRef, IRWildcard, IRBinaryOp, BinaryOp
from usql.ir.statement import IRSelect, SelectCore, IRTableName, TclType, IRTCL
from usql.dialect.capability import Capability


class TestDataType:
    def test_int_type_name(self):
        assert IntType(8).type_name == "TINYINT"
        assert IntType(16).type_name == "SMALLINT"
        assert IntType(32).type_name == "INT"
        assert IntType(64).type_name == "BIGINT"

    def test_decimal_type_name(self):
        assert DecimalType(10, 2).type_name == "DECIMAL(10,2)"

    def test_float_type_name(self):
        assert FloatType(32).type_name == "FLOAT"
        assert FloatType(64).type_name == "DOUBLE"

    def test_varchar_type_name(self):
        assert VarcharType(255).type_name == "VARCHAR(255)"

    def test_boolean_type_name(self):
        assert BooleanType().type_name == "BOOLEAN"

    def test_null_type_name(self):
        assert NullType().type_name == "NULL"


class TestIRExpr:
    def test_literal(self):
        lit = IRLiteral(value=42, type=IntType(32))
        assert lit.value == 42
        assert lit.type == IntType(32)

    def test_column_ref(self):
        ref = IRColumnRef(name="id", qualifier="users")
        assert ref.full_name == "users.id"

    def test_wildcard(self):
        wc = IRWildcard(qualifier="t")
        assert wc.qualifier == "t"

    def test_frozen(self):
        lit = IRLiteral(value=42, type=IntType(32))
        with pytest.raises(AttributeError):
            lit.value = 99


class TestIRStatement:
    def test_tcl_begin(self):
        tcl = IRTCL(type=TclType.BEGIN)
        assert tcl.type == TclType.BEGIN

    def test_tcl_with_savepoint(self):
        tcl = IRTCL(type=TclType.SAVEPOINT, savepoint_name="sp1")
        assert tcl.savepoint_name == "sp1"

    def test_select_capabilities(self):
        sel = IRSelect(
            core=SelectCore(
                projections=(),
                distinct=True,
            ),
            capabilities=frozenset({Capability.DISTINCT}),
        )
        assert Capability.DISTINCT in sel.capabilities
