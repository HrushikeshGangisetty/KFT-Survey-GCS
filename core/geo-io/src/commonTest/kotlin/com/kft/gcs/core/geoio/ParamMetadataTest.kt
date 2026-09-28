package com.kft.gcs.core.geoio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tiny pdef in each format, describing the same four parameters. The shapes follow autotest.ardupilot.org's
 * files (checked against Copter master's apm.pdef.json and stable-4.5.7's apm.pdef.xml, 2026-09); the texts are
 * ours, not copied, so no GPL-derived content sits in the repository.
 */
internal val PDEF_JSON = """
{
  "json": {"version": 0},
  "CAM1": {
    "CAM1_TYPE": {"DisplayName": "Camera trigger", "Description": "How the camera is triggered", "RebootRequired": "True",
                  "User": "Standard", "Values": {"0": "None", "1": "Servo", "2": "Relay"}},
    "CAM1_DURATION": {"DisplayName": "Shutter time", "Description": "How long the trigger is held", "Units": "s",
                      "Range": {"low": "0", "high": "5"}, "Increment": "0.1"}
  },
  "ATC_": {
    "ATC_OPTIONS": {"DisplayName": "Options", "Description": "Axis options", "Bitmask": {"0": "Roll", "1": "Pitch", "2": "Yaw"}}
  },
  "Copter": {
    "FORMAT_VERSION": {"DisplayName": "Format", "Description": "Storage format number", "ReadOnly": "True"}
  }
}
"""

internal val PDEF_XML = """<?xml version="1.0" encoding="utf-8"?>
<!-- test fixture -->
<paramfile>
  <vehicles>
    <parameters name="ArduCopter">
      <param humanName="Format" name="ArduCopter:FORMAT_VERSION" documentation="Storage format number" user="Advanced">
        <field name="ReadOnly">True</field>
      </param>
    </parameters>
  </vehicles>
  <libraries>
    <parameters name="CAM1_">
      <param humanName="Camera trigger" name="CAM1_TYPE" documentation="How the camera is triggered" user="Standard">
        <values>
          <value code="0">None</value>
          <value code="1">Servo</value>
          <value code="2">Relay</value>
        </values>
        <field name="RebootRequired">True</field>
      </param>
      <param humanName="Shutter time" name="CAM1_DURATION" documentation="How long the trigger is held" user="Standard">
        <field name="Units">s</field>
        <field name="UnitText">seconds</field>
        <field name="Range">0 5</field>
        <field name="Increment">.1</field>
      </param>
    </parameters>
    <parameters name="ATC_">
      <param humanName="Options" name="ATC_OPTIONS" documentation="Axis options" user="Advanced">
        <field name="Bitmask">0:Roll, 1:Pitch,2:Yaw</field>
      </param>
    </parameters>
  </libraries>
</paramfile>
"""

class ParamMetadataTest {

    @Test
    fun jsonIsFlattenedByNameWithEveryFieldWeUse() {
        val m = parsePdef(PDEF_JSON)
        assertEquals(setOf("CAM1_TYPE", "CAM1_DURATION", "ATC_OPTIONS", "FORMAT_VERSION"), m.keys, "the \"json\" version marker is not a group")
        val type = m.getValue("CAM1_TYPE")
        assertEquals(listOf(0.0 to "None", 1.0 to "Servo", 2.0 to "Relay"), type.values)
        assertTrue(type.rebootRequired)
        val duration = m.getValue("CAM1_DURATION")
        assertEquals("s", duration.units)
        assertEquals(0.0..5.0, duration.range)
        assertEquals(0.1, duration.increment)
        assertEquals(listOf(0 to "Roll", 1 to "Pitch", 2 to "Yaw"), m.getValue("ATC_OPTIONS").bitmask)
        assertTrue(m.getValue("FORMAT_VERSION").readOnly)
        assertEquals("Shutter time", duration.displayName)
    }

    @Test
    fun notAPdefFileGivesNothing() {
        assertEquals(emptyMap(), parsePdef("CAM1_TYPE,1\n"), "a .param file is not metadata")
        assertEquals(emptyMap(), parsePdef("{ broken"))
    }

    /** autotest.ardupilot.org's layout: releases in the versioned archive (XML only there today), the rest at master. */
    @Test
    fun downloadUrlsFollowTheFirmwareVersion() {
        assertEquals(
            listOf(
                "https://autotest.ardupilot.org/Parameters/versioned/Copter/stable-4.5.7/apm.pdef.json",
                "https://autotest.ardupilot.org/Parameters/versioned/Copter/stable-4.5.7/apm.pdef.xml",
            ),
            pdefUrls(PdefVehicle.COPTER, "4.5.7"),
        )
        assertEquals(
            listOf("https://autotest.ardupilot.org/Parameters/ArduPlane/apm.pdef.json", "https://autotest.ardupilot.org/Parameters/ArduPlane/apm.pdef.xml"),
            pdefUrls(PdefVehicle.PLANE, "4.8.0-dev"),
        )
        assertEquals(pdefUrls(PdefVehicle.COPTER, null), pdefUrls(PdefVehicle.COPTER, "4.6.0-beta"), "no archive for betas or an unknown version")
        assertEquals("Plane-4.8.0-dev", pdefCacheKey(PdefVehicle.PLANE, "4.8.0-dev"))
    }
}
