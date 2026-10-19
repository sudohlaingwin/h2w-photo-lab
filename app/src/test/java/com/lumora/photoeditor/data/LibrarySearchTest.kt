package com.lumora.photoeditor.data

import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of the library search LIKE contract —
 * data/LibrarySearch.kt's [escapeLikePattern], the pure half of the fix that
 * stops typed `%`, `_` and `\` in the library search box from acting as SQL
 * wildcards (the other half is the `ESCAPE '\'` clause on
 * [LibraryDao.search], pinned structurally by the harness).
 */
class LibrarySearchTest {

    @Test fun `escape constant is a single backslash`() {
        assertEquals('\\', LIKE_ESCAPE_CHAR)
    }

    @Test fun `empty query is unchanged`() {
        assertEquals("", escapeLikePattern(""))
    }

    @Test fun `plain query is returned unchanged`() {
        assertEquals("sunset beach", escapeLikePattern("sunset beach"))
    }

    @Test fun `percent is escaped`() {
        assertEquals("100\\%", escapeLikePattern("100%"))
    }

    @Test fun `underscore is escaped`() {
        assertEquals("a\\_b", escapeLikePattern("a_b"))
    }

    @Test fun `backslash is escaped`() {
        assertEquals("a\\\\b", escapeLikePattern("a\\b"))
    }

    @Test fun `backslash is escaped before the other wildcards`() {
        // Input "\%" (two chars) must become "\\\%" (four chars):
        // the backslash is doubled first, then the percent is escaped.
        // Escaping '%' first would corrupt the backslash just inserted.
        assertEquals("\\\\\\%", escapeLikePattern("\\%"))
    }

    @Test fun `combined wildcards are all escaped`() {
        assertEquals("100\\%\\_x\\\\y", escapeLikePattern("100%_x\\y"))
    }

    @Test fun `multiple wildcards in a row`() {
        assertEquals("\\%\\%\\_\\_", escapeLikePattern("%%__"))
    }

    @Test fun `unicode and whitespace pass through untouched`() {
        assertEquals("မြန်မာ beach  2024", escapeLikePattern("မြန်မာ beach  2024"))
    }

    @Test fun `unicode with wildcard`() {
        assertEquals("မြန်မာ\\%", escapeLikePattern("မြန်မာ%"))
    }

    @Test fun `wildcard-only query`() {
        assertEquals("\\%", escapeLikePattern("%"))
        assertEquals("\\_", escapeLikePattern("_"))
        assertEquals("\\\\", escapeLikePattern("\\"))
    }

    @Test fun `leading and trailing wildcards`() {
        assertEquals("\\%party\\%", escapeLikePattern("%party%"))
    }

    @Test fun `result contains no unescaped wildcard`() {
        // Every %, _, \ in the output must be immediately preceded by
        // the escape char (i.e. no raw wildcard survives).
        val out = escapeLikePattern("a%b_c\\d")
        var i = 0
        while (i < out.length) {
            val c = out[i]
            if (c == '\\') {
                // Escape char must be followed by exactly one of the three.
                assertTrue(i + 1 < out.length)
                assertTrue(out[i + 1] == '\\' || out[i + 1] == '%' || out[i + 1] == '_')
                i += 2
            } else {
                assertFalse(c == '%' || c == '_')
                i += 1
            }
        }
    }

    @Test fun `escaped fragment is safe to wrap in percent signs`() {
        // The DAO wraps the fragment as '%' || :q || '%'; the escaped output
        // must remain a well-formed fragment under that wrapping.
        val fragment = escapeLikePattern("100%")
        assertEquals("%100\\%%", "%$fragment%")
    }

    @Test fun `output length is input length plus wildcard count`() {
        val input = "ab%cd_ef\\gh"
        val wildcards = input.count { it == '%' || it == '_' || it == '\\' }
        assertEquals(input.length + wildcards, escapeLikePattern(input).length)
    }
}
