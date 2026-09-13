#version 330

uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord;
out vec4 fragColor;

#ifdef EMBEDDED
layout(std140) uniform CameraView {
    mat4 CameraVP;
    mat4 ScreenModelView;
    vec4 ViewOptions;
};
uniform sampler2D SceneDepth;
in vec4 cameraClip;
#endif

void main() {
#ifdef EMBEDDED
    if (cameraClip.w <= 0.0) discard;
    vec3 ndc = cameraClip.xyz / cameraClip.w;
    float cameraDepth = ViewOptions.x > 0.5 ? ndc.z : ndc.z * 0.5 + 0.5;
    if (any(greaterThan(abs(ndc.xy), vec2(1.0))) || cameraDepth < 0.0 || cameraDepth > 1.0) discard;
    // Main depth is reversed Z in 26.2. Camera depth belongs to a separate attachment.
    float sceneDepth = texelFetch(SceneDepth, ivec2(gl_FragCoord.xy), 0).r;
    if (gl_FragCoord.z + 0.000001 < sceneDepth) discard;
    gl_FragDepth = cameraDepth;
#endif
    vec4 color = texture(Sampler0, texCoord) * vertexColor;
#ifdef CUTOUT
    if (color.a < 0.5) discard;
#endif
    fragColor = vec4(color.rgb, 1.0);
}
