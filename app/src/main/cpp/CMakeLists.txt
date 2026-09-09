cmake_minimum_required(VERSION 3.22.1)
project(pocketpc_runtime LANGUAGES CXX)

add_library(
    pocketpc_runtime
    SHARED
    runtime_host.cpp
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
