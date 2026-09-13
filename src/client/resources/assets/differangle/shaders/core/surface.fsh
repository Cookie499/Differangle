#version 330
layout(std140) uniform Surface { mat4 ModelView; vec4 Background; };
in vec2 texCoord;
out vec4 fragColor;
#ifdef TEXTURED
uniform sampler2D Sampler0;
#endif
void main() {
#ifdef TEXTURED
    fragColor = texture(Sampler0, texCoord);
#else
    fragColor = Background;
#endif
}
