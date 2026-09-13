#version 330
// Minecraft 26.2 shader family, adapted for Differangle Embedded output.
#moj_import <differangle:embedded_native_fragment.glsl>

#moj_import <minecraft:fog.glsl>


in float sphericalVertexDistance;
in float cylindricalVertexDistance;
flat in vec4 vertexColor;

out vec4 fragColor;

void differangleNativeMain() {
    fragColor = apply_fog(vertexColor, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

void main() {
    differangleClipFragment();
    differangleNativeMain();
}
