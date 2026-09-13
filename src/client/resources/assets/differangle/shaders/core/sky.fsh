#version 330
#moj_import <differangle:camera_environment.glsl>
in vec3 viewRay;
out vec4 fragColor;
void main() {
    // Basic sky background; celestial bodies and clouds are separate future stages.
    float height = max(normalize(viewRay).y, 0.0);
    vec3 color = FogColor.rgb;
    if (SkyOptions.x > 0.5 && SkyOptions.x < 1.5) {
        color = mix(FogColor.rgb, SkyColor.rgb, smoothstep(0.0, 0.4, height) * SkyAngles.w);
    }
    fragColor = vec4(color, 1.0);
}
