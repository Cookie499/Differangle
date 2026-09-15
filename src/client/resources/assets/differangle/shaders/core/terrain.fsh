#version 330
#moj_import <differangle:camera_environment.glsl>

uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord;
in vec3 cameraOffset;
out vec4 fragColor;
#ifdef SODIUM
flat in float alphaCutoff;
#endif

#ifdef EMBEDDED
layout(std140) uniform CameraView {
    mat4 CameraVP;
    mat4 ScreenModelView;
    vec4 ViewOptions;
};
uniform sampler2D ScreenVisibility;
in vec4 cameraClip;
#endif

void main() {
#ifdef EMBEDDED
    if (cameraClip.w <= 0.0) discard;
    vec3 ndc = cameraClip.xyz / cameraClip.w;
    float cameraDepth = ViewOptions.x > 0.5 ? ndc.z : ndc.z * 0.5 + 0.5;
    if (any(greaterThan(abs(ndc.xy), vec2(1.0))) || cameraDepth < 0.0 || cameraDepth > 1.0) discard;
    if (texelFetch(ScreenVisibility, ivec2(gl_FragCoord.xy), 0).r < 0.5) discard;
    gl_FragDepth = cameraDepth;
#endif
    vec4 color = texture(Sampler0, texCoord) * vertexColor;
#ifdef SODIUM
    if (color.a < alphaCutoff) discard;
#endif
#ifdef CUTOUT
    if (color.a < 0.5) discard;
#endif
    float environmentalFog = clamp((length(cameraOffset) - FogDistances.x) / (FogDistances.y - FogDistances.x), 0.0, 1.0);
    float distanceFog = clamp((max(length(cameraOffset.xz), abs(cameraOffset.y)) - FogDistances.z) / (FogDistances.w - FogDistances.z), 0.0, 1.0);
    fragColor = vec4(mix(color.rgb, FogColor.rgb, max(environmentalFog, distanceFog)),
#ifdef TRANSLUCENT
        color.a
#else
        1.0
#endif
    );
}
