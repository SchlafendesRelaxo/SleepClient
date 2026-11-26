package org.polyfrost.polyplus.client

import org.apache.logging.log4j.LogManager
import org.polyfrost.oneconfig.api.config.v1.Config
import org.polyfrost.oneconfig.api.config.v1.annotations.Dropdown
import org.polyfrost.oneconfig.api.config.v1.annotations.Include
import org.polyfrost.oneconfig.api.config.v1.annotations.Slider
import org.polyfrost.oneconfig.api.config.v1.annotations.Switch
import org.polyfrost.polyplus.PolyPlusConstants
import org.polyfrost.polyplus.client.gui.widgets.WidgetMigration
import org.polyfrost.polyplus.client.lobby.LobbyCaptureFrequency
import org.polyfrost.polyplus.client.lobby.LobbyCaptureView
import org.polyfrost.polyplus.client.lobby.LobbyReveal
import org.polyfrost.polyplus.client.lobby.LobbySceneStore
import org.polyfrost.polyplus.client.lobby.LobbyTime
import org.polyfrost.polyplus.compat.SparkCompat
import kotlin.math.roundToInt

object PolyPlusLobbyConfig : Config(
    "${PolyPlusConstants.ID}-lobby.json",
    "Lobby",
    Category.OTHER,
) {
    @Transient
    private val LOGGER = LogManager.getLogger()

    @JvmStatic @Include
    var migratedFromSplitConfigs = false

    @JvmStatic @Include
    var serverListView = false

    @JvmStatic
    @Dropdown(
        title = "Lobby Reveal",
        description = "Choose how the lobby scene appears when the menu opens.",
        options = ["Random", "Wave", "Focus pull", "First light"],
    )
    var lobbyReveal: LobbyReveal = LobbyReveal.RANDOM

    @JvmStatic
    @Dropdown(
        title = "Lobby Time",
        description = "Choose the time of day in the lobby scene.",
        options = ["As captured", "Match theme", "Real time", "Day", "Sunset", "Night"],
    )
    var lobbyTime: LobbyTime = LobbyTime.CAPTURED

    @JvmStatic
    @Switch(
        title = "Vignette",
        description = "Darken the edges of the lobby background.",
    )
    var lobbyVignette = true

    @JvmStatic
    @Switch(
        title = "Quit Without Asking",
        description = "Close the game as soon as you press the X button in the lobby, without asking first.",
        subcategory = "Quit Button",
    )
    var quitWithoutConfirmation = false

    @JvmStatic
    @Switch(
        title = "Click Twice to Quit",
        description = "Press the X button twice to close the game, with no popup.",
        subcategory = "Quit Button",
    )
    var quitClickTwice = false

    @JvmStatic @Include
    var lobbyMediaWidget = true

    @JvmStatic @Include
    var lobbyMediaWidgetSize: LobbyMediaSize = LobbyMediaSize.COMPACT

    @JvmStatic @Include
    var lobbyWidgets = ""

    @JvmStatic @Include
    var lobbyWidgetOrder = ""

    @JvmStatic @Include
    var widgetsMigrated = false

    @JvmStatic
    @Dropdown(
        title = "Scene Capture",
        description = "How often the lobby saves scenes while you play. You can still capture a spot by hand.",
        options = ["Often", "Normal", "Rarely", "Off"],
    )
    var lobbyCaptureFrequency: LobbyCaptureFrequency = LobbyCaptureFrequency.NORMAL

    @JvmStatic
    @Dropdown(
        title = "Scene View",
        description = "Use the best view around you, or a random good one.",
        options = ["Best", "Random"],
    )
    var lobbyCaptureView: LobbyCaptureView = LobbyCaptureView.BEST

    @JvmStatic
    @Slider(
        title = "Automatic Scene Limit",
        description = "How many automatic captures to keep. 0 keeps them all. Favourites are never removed.",
        min = 0f,
        max = 100f,
        step = 5f,
    )
    var sceneCaptureLimit = 0f

    init {
        preload()
        migrateFromSplitConfigs()
        migrateWidgets()
        LobbySceneStore.captureLimit = sceneCaptureLimit.roundToInt()
        addCallback("sceneCaptureLimit") { LobbySceneStore.captureLimit = sceneCaptureLimit.roundToInt() }
        hideIf("quitClickTwice") { quitWithoutConfirmation }
    }

    private fun migrateFromSplitConfigs() {
        if (migratedFromSplitConfigs) return
        loadPolyPlusOptions("${PolyPlusConstants.ID}.json", "lobby", LOGGER, ::loadFrom)
        loadPolyPlusOptions("${PolyPlusConstants.ID}-mainmenu.json", "lobby", LOGGER, ::loadFrom)
        migratedFromSplitConfigs = true
        save()
    }

    private fun migrateWidgets() {
        if (widgetsMigrated) return
        lobbyWidgets = WidgetMigration.lobby(lobbyWidgets, lobbyMediaWidget, SparkCompat.installed)
        widgetsMigrated = true
        save()
    }
}

enum class LobbyMediaSize(private val label: String) {
    COMPACT("Compact"),
    LARGE("Large");

    override fun toString(): String = label
}
