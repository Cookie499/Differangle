#version 330
// Minecraft 26.2 shader family, adapted for Differangle Embedded output.
#moj_import <differangle:embedded_native_vertex.glsl>

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;

void differangleNativeMain() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}

void main() {
    differangleNativeMain();
    differangleMapVertex();
}
