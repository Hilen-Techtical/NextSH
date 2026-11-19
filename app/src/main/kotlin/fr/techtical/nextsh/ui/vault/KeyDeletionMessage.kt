// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.content.res.Resources
import fr.techtical.nextsh.R

/**
 * The sentence of the dialog that confirms the deletion of a key.
 *
 * Nothing uses the key: the plain sentence, "« id_ed25519 » will be permanently deleted from the
 * vault." Hosts use it: that sentence, then who uses it and what will break, for instance
 * "It is used by 2 hosts: db-02, web-01 (and 3 tunnels that go through them). These hosts will
 * no longer be able to connect until you choose another key for them."
 *
 * The lookup failed ([VaultViewModel.PendingKeyDeletion.usageCheckFailed]): the plain sentence,
 * then "Could not check whether any hosts use this key."
 *
 * Plurals everywhere a count appears, in both languages. The pronoun of the tunnels follows the
 * number of hosts ("it" for one, "them" for several), which a single plural cannot say. The
 * warning names its subject ("This host", "These hosts") instead of a bare pronoun.
 *
 * Takes the [Resources] rather than being a composable so that its branches can be tested.
 * [pending] holds user labels: the result goes to the screen only, never to a log.
 */
internal fun keyDeletionMessage(
    resources: Resources,
    pending: VaultViewModel.PendingKeyDeletion,
): String {
    val plain = resources.getString(R.string.dialog_delete_key_message, pending.keyLabel)
    // The lookup failed: the plain sentence and a line saying so, the deletion stays possible.
    if (pending.usageCheckFailed) return "$plain ${resources.getString(R.string.dialog_delete_key_usage_unknown)}"
    if (!pending.isInUse) return plain

    val hostCount = pending.hostCount
    val named = pending.hostNames.joinToString(", ")
    val hosts = if (pending.otherHostCount > 0) {
        resources.getQuantityString(
            R.plurals.dialog_delete_key_hosts_and_more,
            pending.otherHostCount,
            named,
            pending.otherHostCount,
        )
    } else {
        named
    }
    // The tunnels come between the hosts and the full stop, preceded by a space; none, nothing.
    val tunnels = if (pending.tunnelCount > 0) {
        val plural = if (hostCount == 1) {
            R.plurals.dialog_delete_key_tunnels_one_host
        } else {
            R.plurals.dialog_delete_key_tunnels_many_hosts
        }
        " " + resources.getQuantityString(plural, pending.tunnelCount, pending.tunnelCount)
    } else {
        ""
    }
    val usedBy = resources.getQuantityString(R.plurals.dialog_delete_key_used_by_hosts, hostCount, hostCount, hosts, tunnels)
    val consequence = resources.getQuantityString(R.plurals.dialog_delete_key_cannot_connect, hostCount)
    return "$plain $usedBy $consequence"
}
