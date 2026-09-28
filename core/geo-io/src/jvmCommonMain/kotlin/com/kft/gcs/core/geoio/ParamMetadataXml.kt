package com.kft.gcs.core.geoio

import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * SAX, not a DOM: the files are 2–3 MB, and a streaming reader never holds the whole tree (a DOM of that size is tens
 * of MB, a lot on a tablet). Only three elements matter: `param` (its attributes), `field` (name → text) and `value`
 * (code → text). Everything else (paramfile, vehicles, libraries, parameters, values) is structure we skip.
 * A malformed file gives an empty map, which the caller reports as "not a parameter metadata file".
 */
internal actual fun parsePdefXml(text: String): Map<String, ParamMeta> {
    val out = LinkedHashMap<String, ParamMeta>()
    val handler = object : DefaultHandler() {
        var name: String? = null
        var humanName = ""
        var documentation = ""
        val fields = HashMap<String, String>()
        val values = ArrayList<Pair<String, String>>()
        var fieldName: String? = null
        var valueCode: String? = null
        val chars = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            chars.setLength(0)
            when (qName) {
                "param" -> {
                    name = attributes.getValue("name")
                    humanName = attributes.getValue("humanName") ?: ""
                    documentation = attributes.getValue("documentation") ?: ""
                    fields.clear()
                    values.clear()
                }
                "field" -> fieldName = attributes.getValue("name")
                "value" -> valueCode = attributes.getValue("code")
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            chars.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "field" -> fieldName?.let { fields[it] = chars.toString().trim() }.also { fieldName = null }
                "value" -> valueCode?.let { values += it to chars.toString().trim() }.also { valueCode = null }
                "param" -> name?.let { n ->
                    val meta = metaFromXml(n, humanName, documentation, fields, values.toList())
                    out[meta.name] = meta
                }.also { name = null }
            }
        }
    }
    runCatching {
        val factory = SAXParserFactory.newInstance()
        // The files have no DTD; refusing external entities keeps a crafted file from reading local files (XXE).
        // One guard each: Android's parser doesn't recognise these flags (and never loads external entities).
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.newSAXParser().parse(InputSource(StringReader(text)), handler)
    }.onFailure { return emptyMap() }
    return out
}
