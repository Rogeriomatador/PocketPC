cmake_minimum_required(VERSION 3.22.1)
project(pocketpc_runtime LANGUAGES CXX)

add_library(
    pocketpc_runtime
    SHARED
    runtime_host.cpp
    hardware_buffer_cross_process.cpp
    hardware_buffer_resource_broker.cpp
    vulkan_wsi_capability_probe.cpp
    vulkan_external_resource_probe.cpp
    vulkan_external_sync_probe.cpp
    vulkan_ahardwarebuffer_import_probe.cpp
    vulkan_external_image_fd_broker.cpp
    vulkan_external_image_host_consumer.cpp
    vulkan_continuous_present_host.cpp
    vulkan_continuous_present_broker_port.cpp
    graphics_seqpacket_session.cpp
    graphics_present_copy_ack.cpp
)

find_library(vulkan_lib vulkan)
find_library(android_lib android)

target_compile_features(pocketpc_runtime PRIVATE cxx_std_20)
target_compile_options(pocketpc_runtime PRIVATE -Wall -Wextra -Werror)
target_link_libraries(
    pocketpc_runtime
    PRIVATE
    ${vulkan_lib}
    ${android_lib}
)
