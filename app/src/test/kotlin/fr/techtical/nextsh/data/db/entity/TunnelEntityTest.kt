// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.entity

import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Mapping between [TunnelConfig] and [TunnelEntity], with a focus on the per-tunnel start
 * path of the in-app browser. The sync (`upsertSyncEntry`) and backup (`VaultExporter`,
 * `VaultImporter`) paths both go through the domain model and these two mappers, so a field
 * dropped here would silently reset to the root on every sync pull or backup restore.
 */
class TunnelEntityTest {

    private fun config(browserPath: String = TunnelBrowserPath.DEFAULT) = TunnelConfig(
        id = "tunnel-1",
        label = "Web",
        hostId = "host-1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "127.0.0.1",
        remotePort = 80,
        autoStart = true,
        openBrowserOnConnect = true,
        keepAliveAfterBrowserClose = false,
        isFavorite = true,
        browserPath = browserPath,
    )

    @Test
    fun `an entity built without a path defaults to the site root`() {
        val entity = TunnelEntity(
            id = "tunnel-1",
            label = "Web",
            hostId = "host-1",
            type = "LOCAL_FORWARD",
            localPort = 8080,
            remoteHost = "127.0.0.1",
            remotePort = 80,
        )

        assertEquals("/", entity.browserPath)
        assertEquals("/", entity.toDomain().browserPath)
    }

    @Test
    fun `fromDomain copies the start path`() {
        val entity = TunnelEntity.fromDomain(config(browserPath = "/admin?tab=2#top"))

        assertEquals("/admin?tab=2#top", entity.browserPath)
    }

    @Test
    fun `toDomain copies the start path`() {
        val entity = TunnelEntity.fromDomain(config()).copy(browserPath = "/grafana/d/abc?orgId=1")

        assertEquals("/grafana/d/abc?orgId=1", entity.toDomain().browserPath)
    }

    @Test
    fun `a domain config survives the round trip through the entity`() {
        val original = config(browserPath = "/admin?tab=2#top")

        assertEquals(original, TunnelEntity.fromDomain(original).toDomain())
    }

    @Test
    fun `the default root survives the round trip`() {
        val original = config()

        assertEquals("/", original.browserPath)
        assertEquals(original, TunnelEntity.fromDomain(original).toDomain())
    }
}
