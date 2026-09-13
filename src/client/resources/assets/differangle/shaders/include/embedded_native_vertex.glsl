// Camera-space shading is completed before mapping onto the physical screen.
layout(std140) uniform CameraView {
    mat4 CameraVP;
    mat4 ScreenModelView;
    vec4 ViewOptions;
};
layout(std140) uniform MainProjection { mat4 MainProjMat; };
out vec4 differangleCameraClip;
void differangleMapVertex() {
    differangleCameraClip = gl_Position;
    gl_Position = MainProjMat * ScreenModelView
        * vec4(differangleCameraClip.xy * 0.5, 0.0, differangleCameraClip.w);
}
