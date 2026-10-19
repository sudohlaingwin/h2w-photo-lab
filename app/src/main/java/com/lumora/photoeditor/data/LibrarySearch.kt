package com.lumora.photoeditor.data

/**
 * Library search LIKE-pattern contract.
 *
 * [LibraryDao.search] matches `displayName`/`folder` with a SQL `LIKE`
 * pattern built as `'%' || :q || '%'` with an explicit `ESCAPE '\'` clause.
 * The caller ([LibraryRepository]) must pass the user-typed query through
 * [escapeLikePattern] first: without escaping, a query containing `%`, `_`
 * or `\` would be interpreted as SQL wildcards (e.g. searching for `100%`
 * would match every name containing `100` followed by anything) instead of
 * matching literally.
 *
 * The escape character is a single backslash; it is applied to the backslash
 * itself FIRST so the transformation is unambiguous.
 */
internal const val LIKE_ESCAPE_CHAR = '\\'

/**
 * Escapes a user-typed library search string for use as a bound LIKE-pattern
 * parameter in a query that declares `ESCAPE '\'`.
 *
 * - `\` -> `\\`
 * - `%` -> `\%`
 * - `_` -> `\_`
 * - every other character (including non-ASCII and whitespace) is unchanged
 *
 * The result is a fragment, not a full pattern: the DAO wraps it as
 * `'%' || :q || '%'` to get contains-semantics.
 */
internal fun escapeLikePattern(q: String): String {
    // Fast path: most queries have no wildcards.
    var needsEscape = false
    for (ch in q) {
        if (ch == '\\' || ch == '%' || ch == '_') {
            needsEscape = true
            break
        }
    }
    if (!needsEscape) return q
    val sb = StringBuilder(q.length + 8)
    for (ch in q) {
        if (ch == '\\' || ch == '%' || ch == '_') sb.append(LIKE_ESCAPE_CHAR)
        sb.append(ch)
    }
    return sb.toString()
}
