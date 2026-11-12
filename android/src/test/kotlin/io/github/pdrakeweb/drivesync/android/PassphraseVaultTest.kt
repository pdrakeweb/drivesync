package io.github.pdrakeweb.drivesync.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PassphraseVaultTest {
    @Test
    fun `a suggested passphrase is long, readable and different every time`() {
        val many = List(500) { PassphraseVault.suggest() }
        assertEquals("no repeats in 500", 500, many.toSet().size)
        for (p in many) {
            assertTrue(p, Regex("([a-hj-km-np-z2-9]{4}-){5}[a-hj-km-np-z2-9]{4}").matches(p))
            assertTrue("long enough for the sample and for any sane minimum", p.length >= 20)
        }
        // 24 symbols from an alphabet of 31: about 118 bits.
        val distinct = many.joinToString("").filter { it != '-' }.toSet()
        assertEquals("the whole alphabet is used", 31, distinct.size)
    }

    @Test
    fun `the generator takes its randomness from the source it is given`() {
        class Fixed : SecureRandom() {
            override fun nextInt(bound: Int) = 0
        }
        assertEquals("aaaa-aaaa-aaaa-aaaa-aaaa-aaaa", PassphraseVault.suggest(Fixed()))
    }
}
