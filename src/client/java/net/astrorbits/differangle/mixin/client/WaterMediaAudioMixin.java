package net.astrorbits.differangle.mixin.client;

import net.astrorbits.differangle.client.media.SpatialAudioOutput;
import net.astrorbits.differangle.client.media.SpatialPcm;
import net.astrorbits.differangle.media.AudioMix;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.SFXEngine;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

/** WaterMedia seals SFXEngine, so adapt only the ALEngine instances owned by our screens. */
@Mixin(value = ALEngine.class, remap = false)
abstract class WaterMediaAudioMixin implements SpatialAudioOutput {
    @Unique private SpatialPcm differangle$pcm;

    @Override
    public void differangleSpatial(Supplier<AudioMix> mix) {
        differangle$pcm = new SpatialPcm(mix);
        int source = ((ALEngine) (Object) this).source();
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0f);
    }

    @Inject(method = "supportedChannels", at = @At("HEAD"), cancellable = true)
    private void differangle$channels(CallbackInfoReturnable<SFXEngine.ChannelSupport[]> cir) {
        if (differangle$pcm != null) cir.setReturnValue(new SFXEngine.ChannelSupport[] {
            new SFXEngine.ChannelSupport(2, SFXEngine.SampleType.S16)
        });
    }

    @Inject(method = "supportedTypes", at = @At("HEAD"), cancellable = true)
    private void differangle$types(CallbackInfoReturnable<SFXEngine.SampleType[]> cir) {
        if (differangle$pcm != null) cir.setReturnValue(new SFXEngine.SampleType[] { SFXEngine.SampleType.S16 });
    }

    @ModifyVariable(method = "upload", at = @At("HEAD"), argsOnly = true)
    private ByteBuffer differangle$spatialize(ByteBuffer buffer) {
        return differangle$pcm == null ? buffer : differangle$pcm.process(buffer);
    }
}
