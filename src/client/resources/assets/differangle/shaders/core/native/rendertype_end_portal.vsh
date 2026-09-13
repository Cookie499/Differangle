#version 330
// Minecraft 26.2 shader family, adapted for Differangle Embedded output.
#moj_import <differangle:embedded_native_vertex.glsl>

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;

out vec4 texProj0;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;

void differangleNativeMain() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texProj0 = projection_from_position(gl_Position);
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
}

void main() {
    differangleNativeMain();
    differangleMapVertex();
}
