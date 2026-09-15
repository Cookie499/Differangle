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

#ifdef SODIUM
in uvec2 a_Position;
in vec4 a_Color;
in uvec2 a_TexCoord;
in uvec4 a_LightAndData;
flat out float alphaCutoff;
#else
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
#endif
uniform sampler2D Sampler2;
out vec4 vertexColor;
out vec2 texCoord;
out vec3 cameraOffset;

void main() {
#ifdef SODIUM
    uvec3 hi = (uvec3(a_Position.x) >> uvec3(0u, 10u, 20u)) & 1023u;
    uvec3 lo = (uvec3(a_Position.y) >> uvec3(0u, 10u, 20u)) & 1023u;
    vec3 Position = vec3((hi << 10u) | lo) * (32.0 / 1048576.0) - 8.0;
    vec4 Color = a_Color;
    vec2 UV0 = vec2(a_TexCoord & 32767u) / 32768.0;
    ivec2 UV2 = ivec2(a_LightAndData.xy);
    const float cutoffs[4] = float[4](0.0, 0.1, 0.5, 1.0);
    alphaCutoff = cutoffs[(a_LightAndData.z >> 1u) & 3u];
#ifdef IRIS_TERRAIN
    // Iris replaces Sodium's material byte with a separate-AO flag.
    if ((a_LightAndData.z & 1u) != 0u) Color.rgb *= Color.a;
    Color.a = 1.0;
    alphaCutoff = 0.0;
#endif
#endif
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
