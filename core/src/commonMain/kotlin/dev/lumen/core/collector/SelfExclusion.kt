package dev.lumen.core.collector

import dev.lumen.core.model.AppKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

/**
 * Self-exclusion rule — `docs/design-spec.md` locks it: the app must
 * exclude ITSELF from its own numbers from day one. A screen-time app that
 * counts its own window corrupts every number it shows.
 *
 * Central implementation so every collector only declares [selfAppKey] and
 * the engine applies the filter once — three platforms each special-casing
 * their own identity is how the bug comes back.
 */
fun Flow<FocusChange>.excludingSelf(selfAppKey: AppKey): Flow<FocusChange> =
    filter { change -> change.appKey != selfAppKey && !change.isIdle }
