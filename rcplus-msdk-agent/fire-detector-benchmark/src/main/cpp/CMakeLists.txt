cmake_minimum_required(VERSION 3.22.1)
project(fire_detector_ncnn)

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

get_target_property(NCNN_COMPILE_DEFINITIONS ${NCNN_TARGET} INTERFACE_COMPILE_DEFINITIONS)
string(JOIN ";" NCNN_COMPILE_DEFINITIONS_TEXT ${NCNN_COMPILE_DEFINITIONS})
if(NOT NCNN_COMPILE_DEFINITIONS_TEXT MATCHES "NCNN_VULKAN=1")
    message(FATAL_ERROR "The provisioned official NCNN CMake package is not Vulkan-capable")
endif()

add_library(fire_detector_ncnn SHARED ncnn_bridge.cpp)
target_link_libraries(fire_detector_ncnn ${NCNN_TARGET} android log)
