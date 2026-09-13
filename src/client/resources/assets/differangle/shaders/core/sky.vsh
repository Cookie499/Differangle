#version 330
layout(std140) uniform CameraView {
    mat4 CameraVP;
    mat4 ScreenModelView;
    vec4 ViewOptions;
};
#ifdef EMBEDDED
#moj_import <minecraft:projection.glsl>
#endif
out vec3 viewRay;
const vec2 corners[6] = vec2[6](
    vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
    vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0)
);
void main() {
    vec2 p = corners[gl_VertexID];
    vec4 ray = inverse(CameraVP) * vec4(p, 0.5, 1.0);
    viewRay = ray.xyz / ray.w;
#ifdef EMBEDDED
    gl_Position = ProjMat * ScreenModelView * vec4(p * 0.5, 0.0, 1.0);
#else
    gl_Position = vec4(p, ViewOptions.x > 0.5 ? 0.0 : -1.0, 1.0);
#endif
}
