package dev.hsbrysk.ahocorasick

import kotlin.test.Test
import kotlin.test.assertEquals

class CodeMapperTest {
    @Test
    fun ranksByDescendingFrequency() {
        val mapper = CodeMapper.build(mapOf('a'.code to 1, 'b'.code to 3, 'c'.code to 2))
        assertEquals(0, mapper.map('b'.code))
        assertEquals(1, mapper.map('c'.code))
        assertEquals(2, mapper.map('a'.code))
        assertEquals(3, mapper.alphabetSize)
    }

    @Test
    fun equalFrequenciesTieBreakByAscendingCodePoint() {
        val mapper = CodeMapper.build(mapOf('z'.code to 5, 'a'.code to 5, 'm'.code to 5))
        assertEquals(0, mapper.map('a'.code))
        assertEquals(1, mapper.map('m'.code))
        assertEquals(2, mapper.map('z'.code))
    }

    @Test
    fun unmappedCodePointsAreInvalid() {
        val mapper = CodeMapper.build(mapOf('b'.code to 1))
        // Below the table maximum but absent, and beyond the table, respectively.
        assertEquals(CodeMapper.INVALID_CODE, mapper.map('a'.code))
        assertEquals(CodeMapper.INVALID_CODE, mapper.map('c'.code))
        assertEquals(CodeMapper.INVALID_CODE, mapper.map(0x10FFFF))
    }

    @Test
    fun emptyInputMapsEverythingToInvalid() {
        val mapper = CodeMapper.build(emptyMap())
        assertEquals(0, mapper.alphabetSize)
        assertEquals(CodeMapper.INVALID_CODE, mapper.map(0))
        assertEquals(CodeMapper.INVALID_CODE, mapper.map('a'.code))
    }

    @Test
    fun nulAndSupplementaryPlaneCodePointsAreMapped() {
        val emoji = 0x1F600
        val mapper = CodeMapper.build(mapOf(0 to 1, emoji to 2))
        assertEquals(0, mapper.map(emoji))
        assertEquals(1, mapper.map(0))
        assertEquals(CodeMapper.INVALID_CODE, mapper.map(emoji - 1))
    }
}
