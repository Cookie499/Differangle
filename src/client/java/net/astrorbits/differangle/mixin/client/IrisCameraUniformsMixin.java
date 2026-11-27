package net.astrorbits.differangle.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.astrorbits.differangle.client.render.ShaderCameraContext;
import net.astrorbits.differangle.client.render.VirtualCamera;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CameraUniforms", remap = false)
abstract class IrisCameraUniformsMixin {
    @WrapOperation(method = "addCameraUniforms", at = @At(value = "INVOKE", target =
        "Lnet/irisshaders/iris/gl/uniform/UniformHolder;uniform1f(Lnet/irisshaders/iris/gl/uniform/UniformUpdateFrequency;Ljava/lang/String;Ljava/util/function/DoubleSupplier;)Lnet/irisshaders/iris/gl/uniform/UniformHolder;"))
    private static UniformHolder differangle$near(UniformHolder holder, UniformUpdateFrequency frequency,
                                                   String name, DoubleSupplier value, Operation<UniformHolder> original) {
        if (!name.equals("near") || !(ShaderCameraContext.observer instanceof VirtualCamera)) {
            return original.call(holder, frequency, name, value);
        }
        // A mirror's near plane moves with the eye. Depth reconstruction must use the current plane.
        return original.call(holder, UniformUpdateFrequency.PER_FRAME, name, (DoubleSupplier) () ->
            ShaderCameraContext.observer instanceof VirtualCamera camera
                ? camera.getDefinition().getNearPlane() : value.getAsDouble());
    }

    @WrapOperation(method = "addCameraUniforms", at = @At(value = "INVOKE", target =
        "Lnet/irisshaders/iris/gl/uniform/UniformHolder;uniform1f(Lnet/irisshaders/iris/gl/uniform/UniformUpdateFrequency;Ljava/lang/String;Ljava/util/function/IntSupplier;)Lnet/irisshaders/iris/gl/uniform/UniformHolder;"))
    private static UniformHolder differangle$far(UniformHolder holder, UniformUpdateFrequency frequency,
                                                  String name, IntSupplier value, Operation<UniformHolder> original) {
        if (!name.equals("far") || !(ShaderCameraContext.observer instanceof VirtualCamera)) {
            return original.call(holder, frequency, name, value);
        }
        return holder.uniform1f(frequency, name, (DoubleSupplier) () ->
            ShaderCameraContext.observer instanceof VirtualCamera camera
                ? camera.getDefinition().getFarPlane() : value.getAsInt());
    }
}
