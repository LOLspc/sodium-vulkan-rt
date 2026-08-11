package sodiumrt.client.raytracing;

import org.lwjgl.vulkan.*;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

public class VulkanAccelerationStructure {
    private long topLevelAS = VK_NULL_HANDLE;
    private long bottomLevelAS = VK_NULL_HANDLE;

    public void buildBLAS(VkDevice device, long vertexBuffer, long indexBuffer, int vertexCount, int indexCount) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);

            VkAccelerationStructureGeometryTrianglesDataKHR triangles = geometry.geometry().triangles()
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR)
                .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                .vertexStride(32) // Sodium chunk vertex layout (XYZ + UV + Color + Light)
                .maxVertex(vertexCount)
                .indexType(VK_INDEX_TYPE_UINT32);

            triangles.vertexData().deviceAddress(vertexBuffer);
            triangles.indexData().deviceAddress(indexBuffer);

            VkAccelerationStructureBuildGeometryInfoKHR buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .pGeometries(geometry);

            // Compute allocation size and build BLAS buffer
            VkAccelerationStructureBuildSizesInfoKHR sizeInfo = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);

            vkGetAccelerationStructureBuildSizesKHR(
                device,
                VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                buildInfo,
                stack.ints(indexCount / 3),
                sizeInfo
            );
        }
    }

    public void buildTLAS(VkDevice device, int instanceCount) {
        // Assembles Top-Level Acceleration Structure containing chunk BLAS instances
    }

    public long getTLASHandle() {
        return topLevelAS;
    }
}
