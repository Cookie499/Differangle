package net.astrorbits.differangle.client.render

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.DepthStencilState
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
    private val section = uniform("SectionOffset")
    private val surface = uniform("Surface")
    private val sceneDepth = BindGroupLayout.builder().withSampler("SceneDepth").build()

    private fun terrain(embedded: Boolean, cutout: Boolean): RenderPipeline {
        val builder = RenderPipeline.builder()
            .withLocation(id("pipeline/terrain_${embedded}_${cutout}"))
            .withVertexShader(id("core/terrain"))
            .withFragmentShader(id("core/terrain"))
            .withBindGroupLayout(view).withBindGroupLayout(section)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
            .withVertexBinding(0, DefaultVertexFormat.BLOCK)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
            // Embedded mapping can reverse winding when the screen is seen from the back.
            .withCull(!embedded)
        if (embedded) builder.withShaderDefine("EMBEDDED")
            .withBindGroupLayout(BindGroupLayouts.PROJECTION).withBindGroupLayout(sceneDepth)
        if (cutout) builder.withShaderDefine("CUTOUT")
        return RenderPipelines.register(builder.build())
    }

    private fun surface(textured: Boolean): RenderPipeline {
        val builder = RenderPipeline.builder()
            .withLocation(id("pipeline/surface_$textured"))
            .withVertexShader(id("core/surface")).withFragmentShader(id("core/surface"))
            .withBindGroupLayout(surface).withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withDepthStencilState(DepthStencilState.DEFAULT).withCull(false)
        if (textured) builder.withShaderDefine("TEXTURED").withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        return RenderPipelines.register(builder.build())
    }

    val textureSolid = terrain(false, false)
    val textureCutout = terrain(false, true)
    val embeddedSolid = terrain(true, false)
    val embeddedCutout = terrain(true, true)
    val texturedSurface = surface(true)
    val backgroundSurface = surface(false)
    val textureSky = backgroundSurface
    val embeddedSky = backgroundSurface

    fun initialize() { /* Force registration before shader resource loading. */ }
}
