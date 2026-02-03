# WAMR-based c2w Android Runtime
cmake_minimum_required(VERSION 3.18)
project(c2w_wamr)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# WAMR source directory
# Allow override via cmake variable
if(NOT DEFINED WAMR_ROOT_DIR)
    # Compute path: cpp -> main -> src -> app -> android-app-wamr -> kotlin-c2w
    get_filename_component(KOTLIN_C2W_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
    set(WAMR_ROOT_DIR "${KOTLIN_C2W_ROOT}/wamr-integration/wamr")
endif()

# Verify WAMR exists
if(NOT EXISTS ${WAMR_ROOT_DIR}/core/iwasm/include/wasm_export.h)
    message(FATAL_ERROR "WAMR not found at ${WAMR_ROOT_DIR}")
endif()

message(STATUS "WAMR_ROOT_DIR: ${WAMR_ROOT_DIR}")

# WAMR configuration - OPTIMIZED FOR PERFORMANCE
set(WAMR_BUILD_PLATFORM "android")
set(WAMR_BUILD_TARGET "AARCH64")

# Performance features
set(WAMR_BUILD_INTERP 1)
set(WAMR_BUILD_FAST_INTERP 1)
set(WAMR_BUILD_AOT 1)
set(WAMR_BUILD_JIT 0)  # JIT requires LLVM

# WASI for c2w
set(WAMR_BUILD_LIBC_BUILTIN 1)
set(WAMR_BUILD_LIBC_WASI 1)

# Memory features
set(WAMR_BUILD_BULK_MEMORY 1)
set(WAMR_BUILD_REF_TYPES 1)
set(WAMR_BUILD_SIMD 1)

# Disable hardware bound checking (requires signal handlers that don't work on all platforms)
set(WAMR_DISABLE_HW_BOUND_CHECK 1)
set(WAMR_DISABLE_STACK_HW_BOUND_CHECK 1)

# Disable unneeded
set(WAMR_BUILD_MULTI_MODULE 0)
set(WAMR_BUILD_MINI_LOADER 0)
set(WAMR_BUILD_LIB_PTHREAD 0)
set(WAMR_BUILD_LIB_WASI_THREADS 0)
set(WAMR_BUILD_WASI_NN 0)
set(WAMR_BUILD_DEBUG_INTERP 0)
set(WAMR_BUILD_MEMORY_PROFILING 0)
set(WAMR_BUILD_PERF_PROFILING 0)
set(WAMR_BUILD_CUSTOM_NAME_SECTION 0)
set(WAMR_BUILD_DUMP_CALL_STACK 0)
set(WAMR_BUILD_LOAD_CUSTOM_SECTION 0)

# Include WAMR build scripts
include(${WAMR_ROOT_DIR}/build-scripts/runtime_lib.cmake)

# Our native library
add_library(c2w_wamr SHARED
    c2w_runtime.cpp
    ${WAMR_RUNTIME_LIB_SOURCE}
)

target_include_directories(c2w_wamr PRIVATE
    ${WAMR_ROOT_DIR}/core/iwasm/include
)

target_link_libraries(c2w_wamr
    android
    log
    m
    dl
)

# Optimization flags
target_compile_options(c2w_wamr PRIVATE
    -O3
    -ffast-math
    -fno-exceptions
    -fno-rtti
    -ffunction-sections
    -fdata-sections
)

target_link_options(c2w_wamr PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
)
