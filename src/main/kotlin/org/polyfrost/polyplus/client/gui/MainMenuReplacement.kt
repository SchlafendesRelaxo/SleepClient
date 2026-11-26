package org.polyfrost.polyplus.client.gui

import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
//? if > 1.8.9
import com.mojang.realmsclient.RealmsMainScreen
import org.polyfrost.polyplus.client.MainMenuChoice
import org.polyfrost.polyplus.client.PolyPlusConfig
import org.polyfrost.polyplus.client.PolyPlusMainMenuConfig
import org.polyfrost.polyplus.client.PolyPlusPauseConfig
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
import org.polyfrost.polyplus.client.gui.lobbyshell.LobbyPage
import org.polyfrost.polyplus.client.gui.pausescreen.PauseScreenStyle
import org.polyfrost.polyplus.client.lobby.LOBBY_LOGGER
import org.polyfrost.polyplus.client.lobby.LobbyScene
import org.polyfrost.polyplus.client.utils.ClientPlatform

object MainMenuReplacement {
    private var playing = false
    private var vanillaServerList = false

    internal fun choice(): MainMenuChoice = MainMenuChoice.VANILLA

    @JvmStatic
    fun enabled(): Boolean = choice() != MainMenuChoice.VANILLA

    @JvmStatic
    fun replacesTitleScreen(): Boolean = enabled()

    @JvmStatic
    fun isMenu(screen: Screen?): Boolean = screen is PolyPlusLobbyScreen || screen is PolyPlusMainMenuScreen

    @JvmStatic
    fun alreadyOpen(): Boolean = ClientPlatform.currentScreen().let { isMenu(it) || it is PolyPlusOnboardingScreen }

    @JvmStatic
    fun create(): Screen = TitleScreen()

    @JvmStatic
    fun open() = ClientPlatform.setScreen(TitleScreen())

    fun dismissVanillaOffer() {
        PolyPlusConfig.vanillaOfferDismissed = true
        PolyPlusConfig.save()
    }

    fun useVanillaOffer() {
        PolyPlusPauseConfig.pauseScreenStyle = PauseScreenStyle.VANILLA
        PolyPlusPauseConfig.save()
        dismissVanillaOffer()
        switchTo()
    }

    @JvmStatic
    fun switchToLobby() = switchTo()

    @JvmStatic
    fun switchToPolyPlusMenu() = switchTo()

    @JvmStatic
    fun switchToVanilla() = switchTo()

    private fun switchTo() {
        PolyPlusMainMenuConfig.mainMenu = MainMenuChoice.VANILLA
        PolyPlusMainMenuConfig.save()
        LobbyScene.stop()
        ClientPlatform.setScreen(TitleScreen())
    }

    fun fallBackToVanilla() {
        LOBBY_LOGGER.warn("Custom menus are disabled; returning to the vanilla title screen")
        LobbyScene.stop()
        ClientPlatform.setScreen(if (ClientPlatform.inWorld()) null else TitleScreen())
    }

    fun openVanillaServerList(screen: JoinMultiplayerScreen) {
        vanillaServerList = true
        try {
            ClientPlatform.setScreen(screen)
        } finally {
            vanillaServerList = false
        }
    }

    private fun keepsVanillaServerList(): Boolean =
        vanillaServerList || ClientPlatform.currentScreen() is JoinMultiplayerScreen

    @JvmStatic
    fun replaceLists(screen: Screen?): Screen? {
        val inWorld = ClientPlatform.inWorld() && !LobbyScene.terrainActive()
        if (inWorld) playing = true
        val left = playing && !inWorld
        if (left && screen is TitleScreen) playing = false
        if (!enabled()) {
            if (inWorld || (screen !is PolyPlusLobbyScreen && screen !is PolyPlusMainMenuScreen && screen !is PolyPlusOnboardingScreen)) return screen
            playing = false
            LobbyScene.stop()
            return TitleScreen()
        }
        if (left && screen is PolyPlusLobbyScreen) {
            playing = false
            screen.showPage(LobbyPage.Home)
            return screen
        }
        if (inWorld) return screen
        //? if > 1.8.9 {
        if (left && screen is RealmsMainScreen) {
            playing = false
            return lobbyPage(LobbyPage.Home)
        }
        //?}
        when (screen?.javaClass) {
            JoinMultiplayerScreen::class.java -> {
                if (!left && keepsVanillaServerList()) return screen
                if (!left) return lobbyPage(LobbyPage.Multiplayer)
                playing = false
                return lobbyPage(LobbyPage.Home)
            }
            SelectWorldScreen::class.java -> return lobbyPage(LobbyPage.Singleplayer)
        }
        return screen
    }

    private fun lobbyPage(page: LobbyPage): Screen {
        val open = ClientPlatform.currentScreen() as? PolyPlusLobbyScreen ?: return PolyPlusLobbyScreen(page)
        open.showPage(page)
        return open
    }
}
