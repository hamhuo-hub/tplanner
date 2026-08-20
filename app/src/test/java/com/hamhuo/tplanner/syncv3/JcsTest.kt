package com.hamhuo.tplanner.syncv3

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class JcsTest {

    @Test
    fun `canonical form is key-order independent`() {
        val a = JSONObject()
            .put("b", 1)
            .put("a", JSONObject().put("y", true).put("x", "s"))
        val b = JSONObject()
            .put("a", JSONObject().put("x", "s").put("y", true))
            .put("b", 1)
        assertEquals(Jcs.canonicalize(a), Jcs.canonicalize(b))
    }

    @Test
    fun `nested arrays canonicalize recursively`() {
        val obj = JSONObject().put("list", JSONArray().put(JSONObject().put("z", 1).put("a", 2)))
        val expected = """{"list":[{"a":2,"z":1}]}"""
        assertEquals(expected, Jcs.canonicalize(obj))
    }

    @Test
    fun `ecmascript string escaping matches JSON stringify`() {
        val obj = JSONObject().put("text", "a\"b\\c\nd\te\u0001")
        assertEquals("""{"text":"a\"b\\c\nd\te\u0001"}""", Jcs.canonicalize(obj))
    }

    @Test
    fun `integral doubles serialize with trailing zero per JCS`() {
        val obj = JSONObject().put("n", 42.0).put("m", 2.5)
        assertEquals("""{"m":2.5,"n":42.0}""", Jcs.canonicalize(obj))
    }

    @Test
    fun `null and booleans serialize as literals`() {
        val obj = JSONObject().put("a", JSONObject.NULL).put("b", true).put("c", false)
        assertEquals("""{"a":null,"b":true,"c":false}""", Jcs.canonicalize(obj))
    }
}
