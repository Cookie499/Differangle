layout(std140) uniform CameraView {
    mat4 CameraVP;
    mat4 ScreenModelView;
    vec4 ViewOptions;
};
uniform sampler2D SceneDepth;
in vec4 differangleCameraClip;
void differangleClipFragment() {
    if (differangleCameraClip.w <= 0.0) discard;
    vec3 ndc = differangleCameraClip.xyz / differangleCameraClip.w;
    float depth = ViewOptions.x > 0.5 ? ndc.z : ndc.z * 0.5 + 0.5;
    if (any(greaterThan(abs(ndc.xy), vec2(1.0))) || depth < 0.0 || depth > 1.0) discard;
    float sceneDepth = texelFetch(SceneDepth, ivec2(gl_FragCoord.xy), 0).r;
    if (ViewOptions.y > 0.5 ? gl_FragCoord.z - 0.000001 > sceneDepth : gl_FragCoord.z + 0.000001 < sceneDepth) discard;
    gl_FragDepth = depth;
}
