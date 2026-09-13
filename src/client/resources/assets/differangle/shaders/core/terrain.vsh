#version 330

layout(std140) uniform CameraView {
    mat4 CameraVP;
    mat4 ScreenModelView;
    vec4 ViewOptions;
};
layout(std140) uniform SectionOffset { vec4 Offset; };

#ifdef EMBEDDED
#moj_import <minecraft:projection.glsl>
out vec4 cameraClip;
#endif

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
uniform sampler2D Sampler2;
out vec4 vertexColor;
out vec2 texCoord;
out vec3 cameraOffset;

void main() {
    vec4 clip = CameraVP * vec4(Position + Offset.xyz, 1.0);
#ifdef EMBEDDED
    cameraClip = clip;
    // Homogeneous mapping preserves perspective across an arbitrarily rotated screen plane.
    gl_Position = ProjMat * ScreenModelView * vec4(clip.xy * 0.5, 0.0, clip.w);
#else
    gl_Position = clip;
#endif
    vertexColor = Color * texelFetch(Sampler2, clamp(UV2 / 16, ivec2(0), ivec2(15)), 0);
    texCoord = UV0;
    cameraOffset = Position + Offset.xyz;
}
