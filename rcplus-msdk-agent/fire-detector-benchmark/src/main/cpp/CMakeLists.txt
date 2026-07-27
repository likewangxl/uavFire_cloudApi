cmake_minimum_required(VERSION 3.22.1)
project(fire_detector_ncnn)

if(NOT DEFINED NCNN_SDK_DIR OR "${NCNN_SDK_DIR}" STREQUAL "")
    message(FATAL_ERROR "NCNN_SDK_DIR must point to a checksum-recorded official NCNN Android SDK")
endif()

set(NCNN_INCLUDE_DIR "${NCNN_SDK_DIR}/include")
set(NCNN_LIBRARY "${NCNN_SDK_DIR}/lib/${ANDROID_ABI}/libncnn.so")
if(NOT EXISTS "${NCNN_INCLUDE_DIR}/net.h" OR NOT EXISTS "${NCNN_LIBRARY}")
    message(FATAL_ERROR "NCNN SDK must provide include/net.h and lib/${ANDROID_ABI}/libncnn.so")
endif()

add_library(ncnn SHARED IMPORTED)
set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION "${NCNN_LIBRARY}")
add_library(fire_detector_ncnn SHARED ncnn_bridge.cpp)
target_include_directories(fire_detector_ncnn PRIVATE "${NCNN_INCLUDE_DIR}")
target_link_libraries(fire_detector_ncnn ncnn android log)
