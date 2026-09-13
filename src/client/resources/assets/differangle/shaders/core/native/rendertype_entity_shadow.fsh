#version 330
// Minecraft 26.2 shader family, adapted for Differangle Embedded output.
#moj_import <differangle:embedded_native_fragment.glsl>

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void differangleNativeMain() {
    vec4 color = texture(Sampler0, clamp(texCoord0, 0.0, 1.0));
    color *= vertexColor * ColorModulator;
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

void main() {
    differangleClipFragment();
    differangleNativeMain();
}
