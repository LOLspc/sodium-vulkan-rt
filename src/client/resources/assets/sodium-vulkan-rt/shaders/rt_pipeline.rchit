#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : enable

layout(location = 0) rayPayloadInEXT vec3 hitValue;
layout(location = 1) rayPayloadEXT bool isShadowed;

hitAttributeEXT vec2 attribs;

layout(binding = 0, set = 0) uniform accelerationStructureEXT topLevelAS;
layout(binding = 2, set = 0) uniform CameraData {
    mat4 viewInverse;
    mat4 projInverse;
    vec4 lightDirection;
} camera;

layout(binding = 3, set = 0) buffer Vertices {
    float v[];
} vertices;

layout(binding = 4, set = 0) buffer Indices {
    uint i[];
} indices;

void main() {
    // Reconstruct hit normal from triangle geometry
    uint primitiveID = gl_PrimitiveID;
    uint i0 = indices.i[primitiveID * 3 + 0];
    uint i1 = indices.i[primitiveID * 3 + 1];
    uint i2 = indices.i[primitiveID * 3 + 2];

    vec3 v0 = vec3(vertices.v[i0 * 8], vertices.v[i0 * 8 + 1], vertices.v[i0 * 8 + 2]);
    vec3 v1 = vec3(vertices.v[i1 * 8], vertices.v[i1 * 8 + 1], vertices.v[i1 * 8 + 2]);
    vec3 v2 = vec3(vertices.v[i2 * 8], vertices.v[i2 * 8 + 1], vertices.v[i2 * 8 + 2]);

    vec3 N = normalize(cross(v1 - v0, v2 - v0));
    vec3 L = normalize(camera.lightDirection.xyz);

    // Calculate world space position of current hit
    vec3 hitPos = gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * gl_HitTEXT;

    // Trace secondary shadow ray towards the light source
    isShadowed = true;
    uint flags = gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT | gl_RayFlagsSkipClosestHitShaderEXT;
    
    traceRayEXT(
        topLevelAS,
        flags,
        0xFF,
        0,
        0,
        0, // shadow miss shader
        hitPos + N * 0.001, // offset to avoid self-intersection
        0.001,
        L,
        1000.0,
        1 // payload location 1 (isShadowed)
    );

    float shadow = isShadowed ? 0.2 : 1.0;
    float NdotL = max(0.0, dot(N, L));
    vec3 albedo = vec3(0.85, 0.85, 0.85);
    
    vec3 ambient = vec3(0.2, 0.22, 0.25) * albedo;
    vec3 direct = albedo * NdotL * vec3(1.0, 0.95, 0.85) * shadow;

    hitValue = ambient + direct;
}
