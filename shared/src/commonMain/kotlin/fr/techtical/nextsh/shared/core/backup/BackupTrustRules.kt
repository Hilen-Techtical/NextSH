// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import fr.techtical.nextsh.shared.domain.model.Host

/**
 * Trust rules of the known-host import.
 *
 * For a host that is ALREADY on the device (same id, alive or deleted), the backup never brings the
 * first fingerprint of the address written in the file: the user already trusts that host under its
 * local address, and a (possibly booby-trapped) file must not approve a server for it, whether the
 * address is new or was already moved by an earlier import of the same file. The first-connection
 * prompt stays. Hosts that are new on the device keep their fingerprints, and fingerprints without
 * any host in the file are imported as usual.
 */
object BackupTrustRules {

    /**
     * The key under which both platforms store a known host: `"$hostname:$port"`, taken verbatim from the
     * host, with no brackets around an IPv6 literal and with the port even when it is 22.
     */
    fun hostPortKey(hostname: String, port: Int): String = "$hostname:$port"

    /**
     * The [hostPortKey] (address WRITTEN IN THE FILE) of the [incoming] hosts whose id is in [local]
     * (alive or deleted: the caller passes the list it wants). In [incoming] order, without duplicates.
     * Pure: no state, no log. The platform then classifies each fingerprint of the file whose address is
     * in this set: absent locally means held back (report it with [BackupImportReport.withHeldKnownHosts]),
     * present with the same key means already known, present with another key means conflict.
     */
    fun heldDestinations(local: List<Host>, incoming: List<Host>): Set<String> {
        val localIds = HashSet<String>(local.size)
        for (h in local) localIds.add(h.id)
        val result = LinkedHashSet<String>()
        for (h in incoming) {
            if (h.id in localIds) result.add(hostPortKey(h.hostname, h.port))
        }
        return result
    }
}
