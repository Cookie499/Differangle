layout(std140) uniform CameraView {
    mat4 CameraVP;
    mat4 ScreenModelView;
    vec4 ViewOptions;
};
uniform sampler2D ScreenVisibility;
in vec4 differangleCameraClip;
void differangleClipFragment() {
    if (differangleCameraClip.w <= 0.0) discard;
    vec3 ndc = differangleCameraClip.xyz / differangleCameraClip.w;
    float depth = ViewOptions.x > 0.5 ? ndc.z : ndc.z * 0.5 + 0.5;
    if (any(greaterThan(abs(ndc.xy), vec2(1.0))) || depth < 0.0 || depth > 1.0) discard;
    if (texelFetch(ScreenVisibility, ivec2(gl_FragCoord.xy), 0).r < 0.5) discard;
    gl_FragDepth = depth;
}
