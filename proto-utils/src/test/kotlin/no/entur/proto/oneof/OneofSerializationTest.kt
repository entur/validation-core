package no.entur.proto.oneof

import no.entur.proto.testfixtures.v1.OneofWithScalar
import no.entur.proto.testfixtures.v1.note
import no.entur.proto.testfixtures.v1.oneofWithScalar
import no.entur.validation.proto.v1.Cardinality
import no.entur.validation.proto.v1.Severity
import no.entur.validation.proto.v1.ValidationRule
import no.entur.validation.proto.v1.ValidationStage
import no.entur.validation.proto.v1.sqlRule
import no.entur.validation.proto.v1.validationRule
import no.entur.validation.proto.v1.xPathRule
import no.entur.validation.proto.v1.xsdRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OneofSerializationTest {
    @Test
    fun `serialize then deserialize round-trips a set xsd_rule variant`() {
        val rule =
            validationRule {
                defaultSeverity = Severity.ERROR
                stage = ValidationStage.STRUCTURAL
                description = "d"
                xsdRule =
                    xsdRule {
                        message = "m"
                        xsdPath = "x.xsd"
                        fileFilter = "f"
                    }
            }

        val variant = OneofSerialization.serialize(rule, "rule")
        assertEquals("xsd_rule", variant.kind)

        val builder = ValidationRule.newBuilder()
        OneofSerialization.deserialize(builder, "rule", variant)
        val result = builder.build()

        assertEquals(ValidationRule.RuleCase.XSD_RULE, result.ruleCase)
        assertEquals(rule.xsdRule, result.xsdRule)
    }

    @Test
    fun `serialize then deserialize round-trips a set xpath_rule variant, enum field included`() {
        val rule =
            validationRule {
                defaultSeverity = Severity.ERROR
                stage = ValidationStage.LOCAL
                description = "d"
                xpathRule =
                    xPathRule {
                        message = "m"
                        xpathExpression = "Foo"
                        cardinality = Cardinality.EXACTLY_ONE
                        fileFilter = "f"
                    }
            }

        val variant = OneofSerialization.serialize(rule, "rule")
        assertEquals("xpath_rule", variant.kind)

        val builder = ValidationRule.newBuilder()
        OneofSerialization.deserialize(builder, "rule", variant)
        val result = builder.build()

        assertEquals(ValidationRule.RuleCase.XPATH_RULE, result.ruleCase)
        assertEquals(rule.xpathRule, result.xpathRule)
    }

    @Test
    fun `serialize then deserialize round-trips a set sql_rule variant, enum field included`() {
        val rule =
            validationRule {
                defaultSeverity = Severity.ERROR
                stage = ValidationStage.GLOBAL
                description = "d"
                sqlRule =
                    sqlRule {
                        message = "m"
                        sqlExpression = "SELECT 1"
                        cardinality = Cardinality.EXACTLY_ONE
                    }
            }

        val variant = OneofSerialization.serialize(rule, "rule")
        assertEquals("sql_rule", variant.kind)

        val builder = ValidationRule.newBuilder()
        OneofSerialization.deserialize(builder, "rule", variant)
        val result = builder.build()

        assertEquals(ValidationRule.RuleCase.SQL_RULE, result.ruleCase)
        assertEquals(rule.sqlRule, result.sqlRule)
    }

    @Test
    fun `serialize rejects a message with no oneof variant set`() {
        val rule =
            validationRule {
                defaultSeverity = Severity.ERROR
                stage = ValidationStage.STRUCTURAL
                description = "d"
            }

        val exception = assertThrows<IllegalStateException> { OneofSerialization.serialize(rule, "rule") }
        assertTrue(exception.message!!.contains("has no field set for oneof 'rule'"))
    }

    @Test
    fun `deserialize rejects a kind unknown to this schema`() {
        val builder = ValidationRule.newBuilder()
        val variant = OneofVariant("some_future_rule", "{}")

        val exception = assertThrows<IllegalStateException> { OneofSerialization.deserialize(builder, "rule", variant) }
        assertTrue(exception.message!!.contains("Unknown rule kind 'some_future_rule'"))
    }

    @Test
    fun `serialize rejects a scalar-valued oneof variant`() {
        val withScalar = oneofWithScalar { textValue = "x" }

        val exception = assertThrows<IllegalStateException> { OneofSerialization.serialize(withScalar, "value") }
        assertTrue(exception.message!!.contains("'text_value' oneof variant is STRING, not a message"))
    }

    @Test
    fun `deserialize rejects a scalar-valued oneof variant`() {
        val builder = OneofWithScalar.newBuilder()
        val variant = OneofVariant("text_value", "\"x\"")

        val exception = assertThrows<IllegalStateException> { OneofSerialization.deserialize(builder, "value", variant) }
        assertTrue(exception.message!!.contains("'text_value' oneof variant is STRING, not a message"))
    }

    @Test
    fun `serialize then deserialize still round-trips the message-valued variant of a mixed oneof`() {
        val withScalar = oneofWithScalar { messageValue = note { message = "m" } }

        val variant = OneofSerialization.serialize(withScalar, "value")
        assertEquals("message_value", variant.kind)

        val builder = OneofWithScalar.newBuilder()
        OneofSerialization.deserialize(builder, "value", variant)

        assertEquals(withScalar.messageValue, builder.build().messageValue)
    }
}
