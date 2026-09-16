#version 330
layout(std140) uniform Surface { mat4 ModelView; vec4 SurfaceColor; };
in vec2 texCoord;
out vec4 fragColor;
#ifdef TEXTURED
uniform sampler2D Sampler0;
#endif
void main() {
#ifdef TEXTURED
    vec2 uv = vec2(SurfaceColor.x > 0.5 ? 1.0 - texCoord.x : texCoord.x, texCoord.y);
    fragColor = texture(Sampler0, uv);
#else
    fragColor = SurfaceColor;
#endif
}
