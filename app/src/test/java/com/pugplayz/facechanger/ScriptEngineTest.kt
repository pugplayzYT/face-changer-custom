package com.pugplayz.facechanger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun trackedMonochromeEyeFilterCompilesToPixelBytecode() {
        val program = ScriptEngine().parse(
            """
            fn mono_eye cx cy radius
              pixels cx-radius cy-radius radius*2 radius*2
                let dx = x-cx
                let dy = y-cy
                let d = hypot(dx,dy)
                if lt(d,radius)
                  let gray = r*0.299+g*0.587+b*0.114
                  set r gray
                  set g gray
                  set b gray
                end
              end
            end

            if tracked
              let eyeRadius = group_width(0)*0.10
              let leftX = landmark_mid_x(0,33,133)
              let leftY = landmark_mid_y(0,159,145)
              let rightX = landmark_mid_x(0,362,263)
              let rightY = landmark_mid_y(0,386,374)
              call mono_eye leftX leftY eyeRadius
              call mono_eye rightX rightY eyeRadius
            end
            """.trimIndent()
        )

        assertNotNull(compilePixelBytecode(program))
    }
}
