package net.astrorbits.differangle.client.render

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

object CameraPipelines {
    private fun id(path: String) = Identifier.fromNamespaceAndPath("differangle", path)
    private fun uniform(name: String) = BindGroupLayout.builder().withUniform(name, UniformType.UNIFORM_BUFFER).build()
    private val view = uniform("CameraView")
    private val environment = uniform("CameraEnvironment")
    private val section = uniform("SectionOffset")
    private val surface = uniform("Surface")
    private val sceneDepth = BindGroupLayout.builder().withSampler("SceneDepth").build()

    private fun terrain(embedded: Boolean, cutout: Boolean, translucent: Boolean = false): RenderPipeline {
        val builder = RenderPipeline.builder()
            .withLocation(id("pipeline/terrain_${embedded}_${cutout}_${translucent}"))
            .withVertexShader(id("core/terrain"))
            .withFragmentShader(id("core/terrain"))
            .withBindGroupLayout(view).withBindGroupLayout(section).withBindGroupLayout(environment)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
            .withVertexBinding(0, DefaultVertexFormat.BLOCK)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, !translucent))
            // Embedded mapping can reverse winding when the screen is seen from the back.
            .withCull(!embedded)
        if (embedded) builder.withShaderDefine("EMBEDDED")
            .withBindGroupLayout(BindGroupLayouts.PROJECTION).withBindGroupLayout(sceneDepth)
        if (cutout) builder.withShaderDefine("CUTOUT")
        if (translucent) builder.withShaderDefine("TRANSLUCENT")
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        return RenderPipelines.register(builder.build())
    }

    private fun surface(textured: Boolean): RenderPipeline {
        val builder = RenderPipeline.builder()
            .withLocation(id("pipeline/surface_$textured"))
            .withVertexShader(id("core/surface")).withFragmentShader(id("core/surface"))
            .withBindGroupLayout(surface).withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withDepthStencilState(DepthStencilState.DEFAULT).withCull(true)
        if (textured) builder.withShaderDefine("TEXTURED").withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        return RenderPipelines.register(builder.build())
    }

    val textureSolid = terrain(false, false)
    val textureCutout = terrain(false, true)
    val embeddedSolid = terrain(true, false)
    val embeddedCutout = terrain(true, true)
    val textureTranslucent = terrain(false, false, true)
    val embeddedTranslucent = terrain(true, false, true)
    val texturedSurface = surface(true)
    val backgroundSurface = surface(false)
    private fun sky(embedded: Boolean): RenderPipeline {
        val builder = RenderPipeline.builder()
            .withLocation(id("pipeline/sky_$embedded"))
            .withVertexShader(id("core/sky")).withFragmentShader(id("core/sky"))
            .withBindGroupLayout(view).withBindGroupLayout(environment)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
            .withDepthStencilState(if (embedded) DepthStencilState.DEFAULT else DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
        if (embedded) builder.withShaderDefine("EMBEDDED").withBindGroupLayout(BindGroupLayouts.PROJECTION)
        return RenderPipelines.register(builder.build())
    }

    val textureSky = sky(false)
    val embeddedSky = sky(true)

    fun initialize() { /* Force registration before shader resource loading. */ }
}
