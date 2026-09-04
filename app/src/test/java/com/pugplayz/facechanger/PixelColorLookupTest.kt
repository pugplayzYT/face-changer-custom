package com.pugplayz.facechanger

import org.junit.Assert.*
import org.junit.Test

class PixelColorLookupTest {
    private val frame = TrackingFrame(TrackingMode.FACE, emptyList(), 0L)
    private fun invert(region: String = "0 0 1 1") = ScriptEngine().parse("""
        input number amount Invert 1 0 1
        pixels $region
          let cr = sample_r(x, y)
          let cg = sample_g(x, y)
          let cb = sample_b(x, y)
          set r lerp(cr, 1 - cr, amount)
          set g lerp(cg, 1 - cg, amount)
          set b lerp(cb, 1 - cb, amount)
        end
    """.trimIndent()).let { requireNotNull(compilePixelBytecode(it)) }

    @Test fun lookupMatchesVmForDistinctChannelsAlphaAndSliderChanges() {
        val program = invert()
        assertTrue(program.usesColorLookup)
        val source = IntArray(256) { i -> (i shl 24) or (i shl 16) or ((255-i) shl 8) or ((i*73) and 255) }
        for (amount in listOf("0", "0.25", "0.5", "0.75", "1")) {
            val values = mapOf("amount" to amount)
            assertArrayEquals(program.renderPixels(source, 16, 16, frame, values, false),
                program.renderPixels(source, 16, 16, frame, values))
        }
    }

    @Test fun fullFrameIncludesEveryPixelAndFinalRowInBothOrientations() {
        for ((w, h) in listOf(481 to 639, 639 to 481, 1 to 1)) {
            val source = IntArray(w*h) { 0xff123456.toInt() }
            val output = invert().renderPixels(source, w, h, frame, emptyMap())
            assertTrue(output.all { it == 0xffedcba9.toInt() })
            assertArrayEquals(output, invert("").renderPixels(source, w, h, frame, emptyMap()))
        }
    }

    @Test fun partialAndReversedRegionsPreserveOutsidePixels() {
        val source = IntArray(17*23) { 0xff224466.toInt() }
        for (region in listOf("0.25 0.25 0.5 0.5", "0.75 0.75 -0.5 -0.5", "-1 -1 2 2")) {
            val program = invert(region)
            assertArrayEquals(program.renderPixels(source, 17, 23, frame, emptyMap(), false),
                program.renderPixels(source, 17, 23, frame, emptyMap()))
        }
    }

    @Test fun unsafeDependenciesRetainNormalVm() {
        for (body in listOf("set r x", "set r g", "let cr = sample_r(1-x,y)\nset r cr",
            "let n = n+1\nset r n", "if tracked\nset r 1\nend", "let cr = sample_g(x,y)\nset r cr")) {
            val program = requireNotNull(compilePixelBytecode(ScriptEngine().parse("pixels\n$body\nend")))
            assertFalse(body, program.usesColorLookup)
        }
    }

    @Test fun otherIndependentColorMathUsesLookupExactly() {
        val program = requireNotNull(compilePixelBytecode(ScriptEngine().parse("""
            pixels
              set r pow(r, 2)
              set g saturate(g*1.5)
              set b 1-b
              set a a*0.5
            end
        """.trimIndent())))
        assertTrue(program.usesColorLookup)
        val source = IntArray(256) { i -> (i shl 24) or ((255-i) shl 16) or (((i*17) and 255) shl 8) or i }
        assertArrayEquals(program.renderPixels(source, 256, 1, frame, emptyMap(), false),
            program.renderPixels(source, 256, 1, frame, emptyMap()))
    }
}
