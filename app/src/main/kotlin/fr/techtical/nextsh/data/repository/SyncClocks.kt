// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec

/**
 * The stored vector clock [previous] (JSON, "{}" for a brand new row) with this device's counter
 * ticked at [now], re-encoded. A local write must always go through this: an un-ticked write
 * never wins a sync, and a clock restarted from "{}" on an existing row looks concurrent to
 * every peer and raises false conflicts.
 */
internal fun tickedClockJson(previous: String, now: Long): String =
    VectorClockCodec.encode(VectorClockCodec.decode(previous).tick(DeviceIdentity.deviceId(), now))
