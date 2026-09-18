package com.badmintontracker.shared.repo

import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.PostgrestQueryBuilder
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder

/**
 * Every row a select matches, however many responses that takes.
 *
 * PostgREST answers a list with at most `max_rows` rows (1000 on a hosted
 * project and in supabase/config.toml) and says nothing about the rest, so an
 * unranged select over a table that grows with use silently turns into "the
 * first thousand".
 *
 * The total comes from the exact count PostgREST returns beside the first page,
 * which makes the common case still one request. The loop advances by what was
 * actually RECEIVED rather than by what was asked for, because the cap is server
 * configuration: stopping at the first page shorter than the request would bring
 * the same bug back the day somebody lowers it. Only a server that declines to
 * count falls back to that rule, since it leaves nothing better to go on, and it
 * is also what makes the loop certain to end against a fake that answers every
 * request with the same rows.
 *
 * [request] must impose a total order, or paging can show a row twice and
 * another not at all; end an `order` on a unique column.
 */
internal suspend inline fun <reified T : Any> PostgrestQueryBuilder.selectAllPages(
    crossinline request: PostgrestRequestBuilder.() -> Unit,
): List<T> {
    val rows = ArrayList<T>()
    var total: Long? = null
    do {
        val from = rows.size.toLong()
        val result = select {
            request()
            count(Count.EXACT)
            range(from, from + PAGE_SIZE - 1)
        }
        val page = result.decodeList<T>()
        total = result.countOrNull() ?: total
        rows += page
    } while (page.isNotEmpty() && (total?.let { rows.size < it } ?: (page.size >= PAGE_SIZE)))
    return rows
}

@PublishedApi
internal const val PAGE_SIZE = 1_000L
