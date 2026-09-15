package net.astrorbits.differangle.client.render;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit shader families; preserve native defines, vertex formats and material states. */
public final class EmbeddedNativePipelines extends RenderPipeline {
    private static final Set<String> FAMILIES = Set.of(
        "entity", "item", "block", "particle", "rendertype_clouds", "text", "text_background",
        "glint", "rendertype_entity_shadow", "rendertype_leash", "rendertype_lightning",
        "rendertype_beacon_beam", "rendertype_end_portal", "rendertype_water_mask", "rendertype_crumbling"
    );
    private static final Map<RenderPipeline, RenderPipeline> VARIANTS = new IdentityHashMap<>();
    private static final BindGroupLayout EMBEDDED = BindGroupLayout.builder()
        .withUniform("CameraView", UniformType.UNIFORM_BUFFER)
        .withUniform("MainProjection", UniformType.UNIFORM_BUFFER).withSampler("ScreenVisibility").build();

    private EmbeddedNativePipelines(RenderPipeline original) {
        super(Identifier.fromNamespaceAndPath("differangle", "embedded/" + original.getLocation().getPath()),
            shader(original.getVertexShader()), shader(original.getFragmentShader()), original.getShaderDefines(),
            layouts(original), original.getColorTargetStates(), original.getDepthStencilState(),
            original.getPolygonMode(), original.isCull(), original.getVertexFormatBindings(),
            original.getPrimitiveTopology(), original.getSortKey());
    }

    private static Identifier shader(Identifier id) {
        return Identifier.fromNamespaceAndPath("differangle", "core/native/" + id.getPath().substring("core/".length()));
    }

    private static List<BindGroupLayout> layouts(RenderPipeline original) {
        List<BindGroupLayout> layouts = new ArrayList<>(original.getBindGroupLayouts());
        layouts.add(EMBEDDED);
        return List.copyOf(layouts);
    }

    private static boolean supported(Identifier id) {
        return id.getNamespace().equals("minecraft") && id.getPath().startsWith("core/")
            && FAMILIES.contains(id.getPath().substring(5));
    }

    public static void initialize() {
        for (RenderPipeline pipeline : RenderPipelines.getStaticPipelines()) {
            if (supported(pipeline.getVertexShader()) && supported(pipeline.getFragmentShader())) {
                VARIANTS.computeIfAbsent(pipeline, p -> RenderPipelines.register(new EmbeddedNativePipelines(p)));
            }
        }
    }

    public static RenderPipeline variant(RenderPipeline pipeline) {
        RenderPipeline variant = VARIANTS.get(pipeline);
        if (variant == null) throw new UnadaptedPipelineException(pipeline.getLocation());
        return variant;
    }

    /** Carries the pipeline id so the render loop can report it through `differangle.render.pipeline`. */
    public static final class UnadaptedPipelineException extends IllegalStateException {
        private final Identifier pipeline;

        UnadaptedPipelineException(Identifier pipeline) {
            super("No embedded variant for pipeline " + pipeline);
            this.pipeline = pipeline;
        }

        public Identifier pipeline() {
            return this.pipeline;
        }
    }
}
