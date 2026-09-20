package net.astrorbits.differangle.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.astrorbits.differangle.client.media.NativeSpatialAudio;
import net.astrorbits.differangle.client.media.SpatialAudioOutput;
import net.astrorbits.differangle.media.MediaRequest;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.SFXEngine;

import java.nio.ByteBuffer;

/** Adapt only our ALEngines; the unadapted companion and other mods retain WaterMedia behavior. */
@Mixin(value = ALEngine.class, remap = false)
abstract class WaterMediaAudioMixin implements SpatialAudioOutput {
    @Unique private volatile NativeSpatialAudio differangle$spatial;

    @Override
    public void differangleSpatial(MediaRequest request) {
        if (differangle$spatial == null) {
            ALEngine engine = (ALEngine) (Object) this;
            differangle$spatial = new NativeSpatialAudio(engine.source(), engine.buffers().length, request);
        } else differangle$spatial.update(request);
    }

    @Override
    public int[] differangleSources() {
        return differangle$spatial == null ? new int[0] : differangle$spatial.sourceIds();
    }

    @Inject(method = "supportedChannels", at = @At("HEAD"), cancellable = true)
    private void differangle$channels(CallbackInfoReturnable<SFXEngine.ChannelSupport[]> cir) {
        if (differangle$spatial != null) cir.setReturnValue(new SFXEngine.ChannelSupport[] {
            new SFXEngine.ChannelSupport(2, SFXEngine.SampleType.S16)
        });
    }

    @Inject(method = "supportedTypes", at = @At("HEAD"), cancellable = true)
    private void differangle$types(CallbackInfoReturnable<SFXEngine.SampleType[]> cir) {
        if (differangle$spatial != null) cir.setReturnValue(new SFXEngine.SampleType[] { SFXEngine.SampleType.S16 });
    }

    @WrapMethod(method = "format")
    private boolean differangle$format(SFXEngine.SampleType type, int channels, int rate, Operation<Boolean> original) {
        NativeSpatialAudio spatial = differangle$spatial;
        if (spatial == null) return original.call(type, channels, rate);
        synchronized (spatial) {
            return !spatial.getReleased() && type == SFXEngine.SampleType.S16 && channels == 2 &&
                original.call(type, channels, rate) && spatial.format(rate);
        }
    }

    @WrapMethod(method = "upload")
    private boolean differangle$upload(ByteBuffer buffer, Operation<Boolean> original) {
        NativeSpatialAudio spatial = differangle$spatial;
        if (spatial == null) return original.call(buffer);
        synchronized (spatial) { return spatial.canUpload() && original.call(buffer); }
    }

    @Redirect(method = "upload", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/AL10;alBufferData(IILjava/nio/ByteBuffer;I)V"))
    private void differangle$monoBuffers(int buffer, int format, ByteBuffer pcm, int rate) {
        if (differangle$spatial == null) AL10.alBufferData(buffer, format, pcm, rate);
        else differangle$spatial.upload(buffer, pcm, rate);
    }

    @WrapMethod(method = "play")
    private void differangle$play(Operation<Void> original) {
        NativeSpatialAudio spatial = differangle$spatial;
        if (spatial == null) { original.call(); return; }
        synchronized (spatial) { spatial.play(); }
    }

    @WrapMethod(method = "pause")
    private void differangle$pause(Operation<Void> original) {
        NativeSpatialAudio spatial = differangle$spatial;
        if (spatial == null) { original.call(); return; }
        synchronized (spatial) { spatial.pause(); }
    }

    @WrapMethod(method = "speed(F)V")
    private void differangle$speed(float value, Operation<Void> original) {
        NativeSpatialAudio spatial = differangle$spatial;
        if (spatial == null) { original.call(value); return; }
        synchronized (spatial) { spatial.speed(value); }
    }

    @WrapMethod(method = "volume")
    private void differangle$volume(float value, Operation<Void> original) {
        NativeSpatialAudio spatial = differangle$spatial;
        if (spatial == null) { original.call(value); return; }
        synchronized (spatial) { spatial.volume(value); }
    }

    @WrapMethod(method = "flush")
    private void differangle$flush(Operation<Void> original) {
        NativeSpatialAudio spatial = differangle$spatial;
        if (spatial == null) { original.call(); return; }
        synchronized (spatial) {
            if (spatial.getReleased()) return;
            spatial.flushRight();
            original.call();
        }
    }

    @WrapMethod(method = "release")
    private void differangle$release(Operation<Void> original) {
        NativeSpatialAudio spatial = differangle$spatial;
        if (spatial == null) { original.call(); return; }
        synchronized (spatial) {
            if (spatial.getReleased()) return;
            try { original.call(); } finally { spatial.releaseRight(); }
        }
    }
}
