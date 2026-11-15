package com.jbsoft.jobq.domain

import com.jbsoft.jobq.domain.ai.JsonShape
import com.jbsoft.jobq.domain.ai.toGbnf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonShapeTest {

	@Test
	fun `object keys are emitted in declared order with their value rules`() {
		val grammar = JsonShape.Obj("score" to JsonShape.Percent, "ok" to JsonShape.Bool).toGbnf()
		val lines = grammar.lines()

		assertEquals("root ::= obj0", lines[0])
		assertEquals("""obj0 ::= "{" "\"score\":" ws percent "," ws "\"ok\":" ws boolean "}"""", lines[1])
	}

	@Test
	fun `lists are bounded and reference their item rule`() {
		val grammar = JsonShape.Obj("tags" to JsonShape.ListOf(JsonShape.Text, 3)).toGbnf()

		assertTrue(grammar, grammar.contains("""list1 ::= "[" (string ("," ws string){0,2})? "]""""))
	}

	@Test
	fun `single-item list has no repetition`() {
		val grammar = JsonShape.ListOf(JsonShape.Text, 1).toGbnf()

		assertTrue(grammar, grammar.contains("""list0 ::= "[" (string)? "]""""))
	}

	@Test
	fun `nullable, enum and nested objects compose`() {
		val grammar = JsonShape.Obj(
			"place" to JsonShape.Nullable(JsonShape.Text),
			"level" to JsonShape.OneOf(listOf("junior", "senior")),
			"roles" to JsonShape.ListOf(JsonShape.Obj("title" to JsonShape.Text), 2),
		).toGbnf()

		assertTrue(grammar, grammar.contains("""(string | "null")"""))
		assertTrue(grammar, grammar.contains("""("\"junior\"" | "\"senior\"")"""))
		assertTrue(grammar, grammar.contains("""obj1 ::= "{" "\"title\":" ws string "}""""))
		assertTrue(grammar, grammar.contains("(obj1 (\",\" ws obj1){0,1})?"))
	}

	@Test
	fun `every referenced rule is defined`() {
		val grammar = JsonShape.Obj(
			"a" to JsonShape.Integer,
			"b" to JsonShape.ListOf(JsonShape.Obj("c" to JsonShape.Percent, "d" to JsonShape.Bool), 4),
		).toGbnf()
		val defined = grammar.lines().filter { it.isNotBlank() }.map { it.substringBefore(" ::=") }.toSet()
		val referenced = Regex("""\b(obj\d+|list\d+|string|integer|percent|boolean|ws)\b""")
			.findAll(grammar.lines().filter { it.startsWith("root") || it.startsWith("obj") || it.startsWith("list") }.joinToString("\n") { it.substringAfter("::=") })
			.map { it.value }.toSet()

		assertTrue("undefined: ${referenced - defined}", defined.containsAll(referenced))
	}
}
