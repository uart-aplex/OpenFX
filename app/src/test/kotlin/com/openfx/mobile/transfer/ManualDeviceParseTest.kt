package com.openfx.mobile.transfer

import com.openfx.mobile.ui.transfer.parse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualDeviceParseTest {

    @Test
    fun acceptsIpWithOptionalPort() {
        assertEquals("192.168.0.127" to 8989, parse("192.168.0.127"))
        assertEquals("192.168.0.127" to 9000, parse(" 192.168.0.127:9000 "))
        assertEquals("tv.local" to 8989, parse("tv.local"))
    }

    @Test
    fun rejectsMalformedInput() {
        assertNull(parse(""))
        assertNull(parse("192.168.0"))
        assertNull(parse("192.168.0.300"))
        assertNull(parse("192.168.0.1:abc"))
        assertNull(parse("192.168.0.1:70000"))
        assertNull(parse("http://192.168.0.1"))
        assertNull(parse("192.168.0.1/upload"))
    }
}
