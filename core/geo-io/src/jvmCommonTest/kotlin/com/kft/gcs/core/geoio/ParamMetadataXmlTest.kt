package com.kft.gcs.core.geoio

import kotlin.test.Test
import kotlin.test.assertEquals

/** The XML reader (platform SAX) runs on the desktop JVM and on the Android host, so it lives beside both. */
class ParamMetadataXmlTest {

    /**
     * Both fixtures describe the same parameters, so both readers must give the same result. That covers the XML
     * specifics: the "ArduCopter:" prefix dropped, "0 5" as a range, ".1" as an increment, "0:Roll, 1:Pitch,2:Yaw"
     * with and without spaces, `<values>` codes, and fields we ignore (UnitText, user).
     */
    @Test
    fun xmlGivesExactlyWhatJsonGives() {
        assertEquals(parsePdef(PDEF_JSON), parsePdef(PDEF_XML))
    }

    @Test
    fun malformedXmlGivesNothing() {
        assertEquals(emptyMap(), parsePdef("<paramfile><param name=\"A\">"))
    }
}
