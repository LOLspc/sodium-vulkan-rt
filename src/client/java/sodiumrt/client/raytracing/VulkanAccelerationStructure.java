package sodiumrt.client.raytracing;

import org.lwjgl.vulkan.*;
import org.lwjgl.system.MemoryStack;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.*;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT;

public class VulkanAccelerationStructure {
    private long topLevelAS = VK_NULL_HANDLE;
    private long topLevelASBuffer = VK_NULL_HANDLE;
    private long topLevelASMemory = VK_NULL_HANDLE;

    private final List<Long> blasHandles = new ArrayList<>();
    private final List<Long> blasBuffers = new ArrayList<>();
    private final List<Long> blasMemories = new ArrayList<>();

    public static class AccelerationStructureObject {
        public long handle;
        public long buffer;
        public long memory;
        public long deviceAddress;
    }

    public AccelerationStructureObject buildBLAS(VkDevice device, long vertexBuffer, long indexBuffer, int vertexCount, int indexCount, long vertexBufferAddress, long indexBufferAddress) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryTrianglesDataKHR triangles = VkAccelerationStructureGeometryTrianglesDataKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR)
                .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                .vertexStride(32) // Sodium chunk vertex layout stride
                .maxVertex(vertexCount)
                .indexType(VK_INDEX_TYPE_UINT32);

            triangles.vertexData().deviceAddress(vertexBufferAddress);
            triangles.indexData().deviceAddress(indexBufferAddress);

            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            geometry.get(0).geometry().triangles(triangles);

            VkAccelerationStructureBuildGeometryInfoKHR buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .pGeometries(geometry);

            VkAccelerationStructureBuildSizesInfoKHR sizeInfo = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);

            int primitiveCount = indexCount / 3;
            vkGetAccelerationStructureBuildSizesKHR(
                device,
                VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                buildInfo,
                stack.ints(primitiveCount),
                sizeInfo
            );

            AccelerationStructureObject blas = createAccelerationStructureBuffer(
                device,
                sizeInfo.accelerationStructureSize(),
                VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR
            );

            ScratchBuffer scratch = createScratchBuffer(device, sizeInfo.buildScratchSize());

            buildInfo.dstAccelerationStructure(blas.handle);
            buildInfo.scratchData().deviceAddress(scratch.deviceAddress);

            blasHandles.add(blas.handle);
            blasBuffers.add(blas.buffer);
            blasMemories.add(blas.memory);

            destroyScratchBuffer(device, scratch);

            return blas;
        }
    }

    public void buildTLAS(VkDevice device, List<Long> instanceBlasAddresses) {
        if (instanceBlasAddresses.isEmpty()) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int instanceCount = instanceBlasAddresses.size();
            VkAccelerationStructureInstanceKHR.Buffer instances = VkAccelerationStructureInstanceKHR.calloc(instanceCount, stack);

            for (int i = 0; i < instanceCount; i++) {
                VkAccelerationStructureInstanceKHR instance = instances.get(i);
                instance.transform()
                    .matrix( 0, 1.0f)
                    .matrix( 1, 1.0f)
                    .matrix( 2, 1.0f);
                
                instance.instanceCustomIndex(i);
                instance.mask(0xFF);
                instance.instanceShaderBindingTableRecordOffset(0);
                instance.flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR);
                instance.accelerationStructureReference(instanceBlasAddresses.get(i));
            }

            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);

            VkAccelerationStructureGeometryInstancesDataKHR instancesData = geometry.get(0).geometry().instances()
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR)
                .arrayOfPointers(false);
            instancesData.data().deviceAddress(instances.address());

            VkAccelerationStructureBuildGeometryInfoKHR buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .pGeometries(geometry);

            VkAccelerationStructureBuildSizesInfoKHR sizeInfo = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);

            vkGetAccelerationStructureBuildSizesKHR(
                device,
                VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                buildInfo,
                stack.ints(instanceCount),
                sizeInfo
            );

            AccelerationStructureObject tlas = createAccelerationStructureBuffer(
                device,
                sizeInfo.accelerationStructureSize(),
                VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR
            );

            this.topLevelAS = tlas.handle;
            this.topLevelASBuffer = tlas.buffer;
            this.topLevelASMemory = tlas.memory;
        }
    }

    private AccelerationStructureObject createAccelerationStructureBuffer(VkDevice device, long size, int type) {
        AccelerationStructureObject obj = new AccelerationStructureObject();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            if (vkCreateBuffer(device, bufferInfo, null, pBuffer) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create AS buffer");
            }
            obj.buffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, obj.buffer, memReqs);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(memReqs.size());

            VkMemoryAllocateFlagsInfo flagsInfo = VkMemoryAllocateFlagsInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
                .flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
            allocInfo.pNext(flagsInfo.address());

            LongBuffer pMemory = stack.mallocLong(1);
            if (vkAllocateMemory(device, allocInfo, null, pMemory) != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate AS memory");
            }
            obj.memory = pMemory.get(0);

            vkBindBufferMemory(device, obj.buffer, obj.memory, 0);

            VkAccelerationStructureCreateInfoKHR createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                .buffer(obj.buffer)
                .size(size)
                .type(type);

            LongBuffer pAS = stack.mallocLong(1);
            if (vkCreateAccelerationStructureKHR(device, createInfo, null, pAS) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Acceleration Structure handle");
            }
            obj.handle = pAS.get(0);

            VkAccelerationStructureDeviceAddressInfoKHR addressInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR)
                .accelerationStructure(obj.handle);
            obj.deviceAddress = vkGetAccelerationStructureDeviceAddressKHR(device, addressInfo);
        }
        return obj;
    }

    private static class ScratchBuffer {
        long buffer;
        long memory;
        long deviceAddress;
    }

    private ScratchBuffer createScratchBuffer(VkDevice device, long size) {
        ScratchBuffer scratch = new ScratchBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            vkCreateBuffer(device, bufferInfo, null, pBuffer);
            scratch.buffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, scratch.buffer, memReqs);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(memReqs.size());

            VkMemoryAllocateFlagsInfo flagsInfo = VkMemoryAllocateFlagsInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
                .flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
            allocInfo.pNext(flagsInfo.address());

            LongBuffer pMemory = stack.mallocLong(1);
            vkAllocateMemory(device, allocInfo, null, pMemory);
            scratch.memory = pMemory.get(0);

            vkBindBufferMemory(device, scratch.buffer, scratch.memory, 0);

            scratch.deviceAddress = getBufferDeviceAddress(device, scratch.buffer);
        }
        return scratch;
    }

    private void destroyScratchBuffer(VkDevice device, ScratchBuffer scratch) {
        if (scratch.buffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(device, scratch.buffer, null);
        }
        if (scratch.memory != VK_NULL_HANDLE) {
            vkFreeMemory(device, scratch.memory, null);
        }
    }

    private long getBufferDeviceAddress(VkDevice device, long buffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferDeviceAddressInfo info = VkBufferDeviceAddressInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO_KHR)
                .buffer(buffer);
            return vkGetBufferDeviceAddressKHR(device, info);
        }
    }

    public long getTLASHandle() {
        return topLevelAS;
    }

    public void cleanup(VkDevice device) {
        if (topLevelAS != VK_NULL_HANDLE) {
            vkDestroyAccelerationStructureKHR(device, topLevelAS, null);
            vkDestroyBuffer(device, topLevelASBuffer, null);
            vkFreeMemory(device, topLevelASMemory, null);
        }
        for (int i = 0; i < blasHandles.size(); i++) {
            vkDestroyAccelerationStructureKHR(device, blasHandles.get(i), null);
            vkDestroyBuffer(device, blasBuffers.get(i), null);
            vkFreeMemory(device, blasMemories.get(i), null);
        }
        blasHandles.clear();
        blasBuffers.clear();
        blasMemories.clear();
    }
}

