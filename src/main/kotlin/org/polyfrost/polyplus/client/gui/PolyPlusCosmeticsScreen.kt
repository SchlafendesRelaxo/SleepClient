package org.polyfrost.polyplus.client.gui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.polyfrost.polyplus.client.gui.lobbyshell.ConsentStep
import org.polyfrost.polyplus.client.gui.lobbyshell.ConsentWidth
import org.polyfrost.polyplus.client.gui.lobbyshell.rememberOnlineConsent
import org.polyfrost.polyplus.client.gui.wardrobe.SkinOnlyWardrobeScreen

@Composable
internal fun PolyPlusCosmeticsScreen() {
    var consent by rememberOnlineConsent()
    if (!consent) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ConsentStep(
                "Changing your Minecraft skin requires online services.",
                Modifier.width(ConsentWidth),
                onDismiss = {},
                onAccepted = { consent = true },
            )
        }
        return
    }

    SkinOnlyWardrobeScreen()
}
