package org.polyfrost.polyplus.mixin.client;

//? if = 1.21.11 {
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MusicManager.class)
public abstract class MixinMenuMusic {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void sleepclient$muteMenuMusic(CallbackInfo ci) {
        if (Minecraft.getInstance().level == null) {
            Minecraft.getInstance().getSoundManager().stop(null, SoundSource.MUSIC);
            ci.cancel();
        }
    }
}
//?}
