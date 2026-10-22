package com.openfx.mobile.transfer

import com.openfx.core.sync.PairingCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingCodeTest {

    @Test
    fun roundTripsAllFields() {
        val code = PairingCode("192.168.0.127", 8989, "012345", "abc-123", "客廳 TV & 投影")
        assertEquals(code, PairingCode.parse(code.toUri()))
    }

    @Test
    fun optionalFieldsMayBeMissing() {
        assertEquals(
            PairingCode("tv.local", 9000, "999999"),
            PairingCode.parse("openfx://pair?h=tv.local&p=9000&k=999999")
        )
    }

    @Test
    fun rejectsForeignOrBrokenCodes() {
        assertNull(PairingCode.parse("https://example.com/?h=1.2.3.4&p=1&k=123456"))
        assertNull(PairingCode.parse("openfx://pair?h=1.2.3.4&p=8989"))
        assertNull(PairingCode.parse("openfx://pair?h=1.2.3.4&p=8989&k=12345"))
        assertNull(PairingCode.parse("openfx://pair?h=1.2.3.4&p=0&k=123456"))
        assertNull(PairingCode.parse("openfx://pair?h=1.2.3.4/evil&p=8989&k=123456"))
    }
}
