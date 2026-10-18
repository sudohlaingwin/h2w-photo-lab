package com.lumora.photoeditor

import com.lumora.photoeditor.export.ExportPreset
import com.lumora.photoeditor.export.factoryExportPresets
import com.lumora.photoeditor.export.isFactoryExportPreset
import com.lumora.photoeditor.export.mergeFactoryPresets
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/**
 * Pins the built-in per-platform export recipes (Instagram/Facebook/web/
 * full-resolution): stable ids, app-valid settings, and merge behavior that
 * always keeps the shipped recipes first while dropping stale persisted
 * factory copies. (The Gradle test worker is broken in this environment, so
 * these mirror the standalone kotlinc harness in /tmp/export-presets-harness.)
 */
class ExportPresetFactoryTest {

    @Test fun factory_ids_are_stable_and_unique() {
        val factory = factoryExportPresets()
        assertTrue(factory.isNotEmpty())
        assertEquals(factory.size, factory.map { it.id }.toSet().size)
        assertTrue(factory.all { it.id.startsWith("factory:") })
        assertTrue(factory.all { isFactoryExportPreset(it.id) })
    }

    @Test fun factory_ids_never_collide_with_user_ids() {
        val factory = factoryExportPresets()
        repeat(50) {
            val uuid = UUID.randomUUID().toString()
            assertFalse(isFactoryExportPreset(uuid))
            assertFalse(factory.any { it.id == uuid })
        }
    }

    @Test fun factory_settings_pass_export_validation() {
        for (p in factoryExportPresets()) {
            assertTrue(
                "factory recipe '${p.name}' invalid: ${p.settings.validate()}",
                p.settings.validate().isEmpty(),
            )
        }
    }

    @Test fun factory_platform_sizes() {
        val byId = factoryExportPresets().associateBy { it.id }
        assertEquals(1080, byId["factory:instagram-post"]!!.settings.longEdge)
        assertEquals(1920, byId["factory:story"]!!.settings.longEdge)
        assertEquals(2048, byId["factory:facebook"]!!.settings.longEdge)
        val web = byId["factory:web"]!!.settings
        assertEquals(1600, web.longEdge)
        assertEquals(80, web.quality)
        val full = byId["factory:full-res"]!!.settings
        assertEquals(0, full.longEdge)
        assertEquals(100, full.quality)
    }

    @Test fun merge_factory_first_and_drops_stale_copies() {
        val factory = factoryExportPresets()
        val user = listOf(
            ExportPreset(name = "Mine"),
            ExportPreset(id = "factory:instagram-post", name = "Tampered copy"),
        )
        val merged = mergeFactoryPresets(user)
        assertEquals(factory.map { it.id }, merged.take(factory.size).map { it.id })
        assertTrue(merged.any { it.name == "Mine" && !isFactoryExportPreset(it.id) })
        assertFalse(merged.any { it.name == "Tampered copy" })
        assertEquals(factory.size + 1, merged.size)
    }

    @Test fun merge_empty_user_list_gives_factory_only() {
        assertEquals(factoryExportPresets(), mergeFactoryPresets(emptyList()))
    }
}
