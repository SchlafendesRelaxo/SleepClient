@file:OptIn(ExperimentalOneConfigUiApi::class)

package org.polyfrost.polyplus.client.gui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
//? if > 1.8.9 {
import net.minecraft.client.gui.screens.Screen
//?} else {
/*import net.minecraft.client.gui.screen.Screen
*///?}
import org.apache.logging.log4j.LogManager
import org.polyfrost.oneconfig.api.config.v1.ConfigManager
import org.polyfrost.oneconfig.api.config.v1.Property
import org.polyfrost.oneconfig.api.config.v1.Tree
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi
import org.polyfrost.oneconfig.api.ui.v1.OneConfigUI
import org.polyfrost.oneconfig.api.ui.v1.components.ControlSize
import org.polyfrost.oneconfig.api.ui.v1.components.ControlStyle
import org.polyfrost.oneconfig.api.ui.v1.components.EmptyState
import org.polyfrost.oneconfig.api.ui.v1.components.SearchField
import org.polyfrost.oneconfig.api.ui.v1.components.TextButton
import org.polyfrost.oneconfig.api.ui.v1.page.OneConfigDestination
import org.polyfrost.oneconfig.api.ui.v1.page.OneConfigPages
import org.polyfrost.oneconfig.internal.ui.api.ConfigRegistry
import org.polyfrost.oneconfig.internal.ui.components.localizedString
import org.polyfrost.oneconfig.internal.ui.screens.ConfigScreen
import org.polyfrost.oneconfig.internal.ui.shell.LocalShell
import org.polyfrost.oneconfig.internal.ui.shell.ShellController
import java.util.concurrent.Executors

private val SleepClientGutter = 25.dp
private val SleepClientSearchWidth = 200.dp
private const val SLEEPCLIENT_ENTRY_PREFIX = "sleepclient:config:"
private const val SLEEPCLIENT_FOCUS = "sleepclient:option:"

internal object SleepClientSettingsPages : SettingsEntryProvider {
    private val logger = LogManager.getLogger("SleepClient/Settings")
    private val shell = ShellController()
    private val saver = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SleepClient-SettingsSave").apply { isDaemon = true }
    }

    fun register() = SettingsPages.register(this)

    override fun entries(): List<SettingsEntry> {
        var previous = "sleepclient:mods"
        return SleepClientConfigs.entries().map { entry ->
            val placement = SettingsPlacement.After(previous)
            previous = entry.id
            SettingsEntry(entry.id, entry.label, entry.icon, SettingsOwner.SleepClient, SettingsGroup.SleepClient, placement)
        }
    }

    override fun search(entry: SettingsEntry, parent: () -> Screen?): List<SettingsSearchItem> {
        val tree = tree(entry.id.removePrefix(SLEEPCLIENT_ENTRY_PREFIX)) ?: return emptyList()
        val out = mutableListOf<SettingsSearchItem>()
        tree.onAllProps { _, prop ->
            if (prop.display == Property.Display.HIDDEN || prop.getMetadata<Any?>("keybindsPageOnly") == true) return@onAllProps
            if (prop.title == null && prop.getMetadata<String>("titleKey").isNullOrBlank()) return@onAllProps
            val title = localizedString(prop.getMetadata("titleKey"), prop.title).trim()
            if (title.isEmpty()) return@onAllProps
            val description = if (prop.description == null && prop.getMetadata<String>("descriptionKey").isNullOrBlank()) {
                null
            } else {
                localizedString(prop.getMetadata("descriptionKey"), prop.description)
            }
            out += SettingsSearchItem(title, description, null, SLEEPCLIENT_FOCUS + prop.id, title)
        }
        return out
    }

    @Composable
    override fun Page(entry: SettingsEntry, parent: () -> Screen?) {
        val id = entry.id.removePrefix(SLEEPCLIENT_ENTRY_PREFIX)
        val revision = ConfigRegistry.revision
        val tree = remember(id, revision) { tree(id) }
        val focus = LocalSettingsFocus.current
        DisposableEffect(id) {
            shell.search.clear()
            onDispose {
                shell.search.clear()
                save(id)
            }
        }
        LaunchedEffect(focus) {
            snapshotFlow { focus.key }.collect { focus.take(SLEEPCLIENT_FOCUS)?.let { shell.search.update(it) } }
        }
        CompositionLocalProvider(LocalShell provides shell, LocalSettingsOwner provides SettingsOwner.SleepClient) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SleepClientHeader(entry.label, searchable = tree != null)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (tree == null) {
                        SleepClientMissing(id)
                    } else {
                        key(revision, tree) { ConfigScreen(tree, pageKey = "polyplus-settings/$id", configId = id) }
                    }
                }
            }
        }
    }

    @Composable
    private fun SleepClientHeader(title: String, searchable: Boolean) {
        SettingsPageHeader(
            title,
            "Changes apply as you make them.",
            Modifier.padding(horizontal = SleepClientGutter),
            owner = SettingsOwner.SleepClient,
        ) {
            if (searchable) {
                SearchField(
                    shell.search.query,
                    { shell.search.update(it) },
                    Modifier.width(SleepClientSearchWidth),
                    placeholder = "Search",
                    size = ControlSize.Regular,
                )
            }
        }
    }

    @Composable
    private fun SleepClientMissing(id: String) {
        EmptyState(
            "These settings are not loaded",
            Modifier.fillMaxSize(),
            icon = "settings",
            text = "They may still be loading, or available through the Mods tab.",
            action = {
                TextButton(
                    "Open settings",
                    { OneConfigUI.open(OneConfigDestination.ModConfig(OneConfigPages.modConfig(id))) },
                    style = ControlStyle.Primary,
                    size = ControlSize.Large,
                )
            },
        )
    }

    private fun tree(id: String): Tree? =
        runCatching { ConfigRegistry.findTree(id) }.getOrNull()
            ?: runCatching { ConfigManager.active().trees().firstOrNull { it.id == id } }.getOrNull()

    private fun save(id: String) {
        saver.execute {
            runCatching {
                val manager = ConfigManager.active()
                val tree = manager.trees().firstOrNull { it.id == id }
                if (tree != null) manager.save(tree) else manager.save(id)
            }.onFailure { logger.error("Failed to save {}", id, it) }
        }
    }
}
