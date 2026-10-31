package com.badmintontracker.android.players

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.badmintontracker.shared.players.DetailsPlayersDraft
import com.badmintontracker.shared.players.EndsDraft
import com.badmintontracker.shared.players.SidesDraft
import com.badmintontracker.shared.players.Tracked
import com.badmintontracker.shared.scoring.Side

/*
 * Savers for the shared who's-playing drafts, so an editor holding one in
 * rememberSaveable keeps what the coach chose across a recreation. Flattened to
 * strings, booleans and nulls, which a Bundle holds as they are.
 */

private const val NONE = ""

/** An [EndsDraft], or null (no section) as an empty list. */
internal val EndsDraftSaver: Saver<EndsDraft?, Any> = listSaver(
    save = { d -> if (d == null) emptyList() else endsFields(d) },
    restore = { if (it.isEmpty()) null else endsFrom(it, 0) },
)

/** The details editor's who-played: its layout, its sides, and its section when shown. */
internal val DetailsPlayersDraftSaver: Saver<DetailsPlayersDraft, Any> = listSaver(
    save = { d -> listOf<Any?>(d.byEnds) + sidesFields(d.sides) + (d.ends?.let(::endsFields) ?: emptyList()) },
    restore = {
        val sides = sidesFrom(it, 1)
        val ends = if (it.size > 1 + SIDES_FIELDS) endsFrom(it, 1 + SIDES_FIELDS) else null
        DetailsPlayersDraft(byEnds = it[0] as Boolean, sides = sides, ends = ends)
    },
)

private fun endsFields(d: EndsDraft): List<Any?> = listOf(
    d.tracked.name, d.nearPlayerId, d.farPlayerId, d.nearName, d.farName, d.playerAtNear, d.nearSide?.name,
    d.nearSomeoneElse, d.farSomeoneElse,
)

private fun endsFrom(l: List<Any?>, at: Int) = EndsDraft(
    tracked = Tracked.valueOf(l[at] as String),
    nearPlayerId = l[at + 1] as String?,
    farPlayerId = l[at + 2] as String?,
    nearName = l[at + 3] as String,
    farName = l[at + 4] as String,
    playerAtNear = l[at + 5] as Boolean,
    nearSide = (l[at + 6] as String?)?.let(Side::valueOf),
    nearSomeoneElse = l[at + 7] as Boolean,
    farSomeoneElse = l[at + 8] as Boolean,
)

/** Two names a side, the most any format has: player and partner. */
private const val SIDES_FIELDS = 9

private fun sidesFields(d: SidesDraft): List<Any?> = listOf(
    d.homePlayerId, d.awayPlayerId,
    d.homeNames.getOrElse(0) { NONE }, d.homeNames.getOrElse(1) { NONE },
    d.awayNames.getOrElse(0) { NONE }, d.awayNames.getOrElse(1) { NONE },
    d.nearSide?.name, d.homeSomeoneElse, d.awaySomeoneElse,
)

private fun sidesFrom(l: List<Any?>, at: Int) = SidesDraft(
    homePlayerId = l[at] as String?,
    awayPlayerId = l[at + 1] as String?,
    homeNames = listOf(l[at + 2] as String, l[at + 3] as String),
    awayNames = listOf(l[at + 4] as String, l[at + 5] as String),
    nearSide = (l[at + 6] as String?)?.let(Side::valueOf),
    homeSomeoneElse = l[at + 7] as Boolean,
    awaySomeoneElse = l[at + 8] as Boolean,
)
