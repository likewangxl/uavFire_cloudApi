cmake_minimum_required(VERSION 3.22.1)
project(visible_fire_ncnn)

if(NOT DEFINED ncnn_DIR OR "${ncnn_DIR}" STREQUAL "")
    message(FATAL_ERROR "ncnn_DIR must point to the checksum-recorded official NCNN Android CMake package")
endif()
find_package(ncnn CONFIG REQUIRED)

if(TARGET ncnn::ncnn)
    set(NCNN_TARGET ncnn::ncnn)
elseif(TARGET ncnn)
    set(NCNN_TARGET ncnn)
else()
    message(FATAL_ERROR "Official NCNN package did not export an NCNN target")
endif()

if(NOT NCNN_VULKAN)
    message(FATAL_ERROR "The provisioned official NCNN CMake package is not Vulkan-capable")
endif()

add_library(visible_fire_ncnn SHARED visible_fire_ncnn_bridge.cpp)
target_link_libraries(visible_fire_ncnn ${NCNN_TARGET} android log)
