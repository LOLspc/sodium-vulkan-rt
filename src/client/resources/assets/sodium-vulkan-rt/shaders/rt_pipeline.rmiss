#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 0) rayPayloadInEXT vec3 hitValue;

void main() {
    // Dynamic procedural atmosphere / sky radiance
    vec3 rayDir = gl_WorldRayDirectionEXT;
    float sunWeight = max(0.0, dot(rayDir, normalize(vec3(0.3, 0.8, 0.5))));
    vec3 skyColor = mix(vec3(0.4, 0.6, 1.0), vec3(0.9, 0.7, 0.5), pow(1.0 - max(0.0, rayDir.y), 3.0));
    hitValue = skyColor + vec3(1.0, 0.9, 0.7) * pow(sunWeight, 16.0) * 2.0;
}
