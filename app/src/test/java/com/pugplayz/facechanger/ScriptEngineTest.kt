package com.pugplayz.facechanger

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptEngineTest {
    @Test
    fun letWithShortVariableNameParsesExpressionAfterVariable() {
        val program = ScriptEngine().parse(
            """
            repeat 2
              let t = loop/max(1,steps-1)
            end
            """.trimIndent()
        )

        val repeat = program.statements.single() as ScriptEngine.Repeat
        val let = repeat.body.single() as ScriptEngine.Let

        assertEquals("t", let.name)
        assertEquals("loop/max(1,steps-1)", let.expression)
    }
}
