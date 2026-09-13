#version 330
// Minecraft 26.2 shader family, adapted for Differangle Embedded output.
#moj_import <differangle:embedded_native_fragment.glsl>

#moj_import <minecraft:dynamictransforms.glsl>

out vec4 fragColor;

void differangleNativeMain() {
    fragColor = ColorModulator;
}

void main() {
    differangleClipFragment();
    differangleNativeMain();
}
