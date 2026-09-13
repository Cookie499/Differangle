#version 330
// Minecraft 26.2 shader family, adapted for Differangle Embedded output.
#moj_import <differangle:embedded_native_fragment.glsl>

#moj_import <minecraft:fog.glsl>


in float vertexDistance;
in vec4 vertexColor;

out vec4 fragColor;

void differangleNativeMain() {
    vec4 color = vertexColor;
    color.a *= 1.0f - linear_fog_value(vertexDistance, 0, FogCloudsEnd);
    fragColor = color;
}

void main() {
    differangleClipFragment();
    differangleNativeMain();
}
