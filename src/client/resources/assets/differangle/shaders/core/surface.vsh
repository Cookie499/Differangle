#version 330
#moj_import <minecraft:projection.glsl>
layout(std140) uniform Surface { mat4 ModelView; };
out vec2 texCoord;
const vec2 corners[6] = vec2[6](
    vec2(-0.5, -0.5), vec2(0.5, -0.5), vec2(0.5, 0.5),
    vec2(-0.5, -0.5), vec2(0.5, 0.5), vec2(-0.5, 0.5)
);
void main() {
    vec2 position = corners[gl_VertexID];
    gl_Position = ProjMat * ModelView * vec4(position, 0.0, 1.0);
    texCoord = position + 0.5;
}
