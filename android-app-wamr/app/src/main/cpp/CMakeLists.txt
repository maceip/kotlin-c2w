# friscy Android Runtime — libriscv RISC-V 64 emulator
cmake_minimum_required(VERSION 3.18)
project(friscy_android)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Locate libriscv: cpp -> main -> src -> app -> android-app-wamr -> kotlin-c2w
get_filename_component(KOTLIN_C2W_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(LIBRISCV_DIR "${KOTLIN_C2W_ROOT}/vendor/libriscv/lib")

if(NOT EXISTS "${LIBRISCV_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "libriscv not found at ${LIBRISCV_DIR}. Run: git submodule update --init")
endif()

message(STATUS "LIBRISCV_DIR: ${LIBRISCV_DIR}")

# libriscv configuration — optimized for performance
set(RISCV_64I ON CACHE BOOL "" FORCE)
set(RISCV_32I OFF CACHE BOOL "" FORCE)   # Only need 64-bit
set(RISCV_128I OFF CACHE BOOL "" FORCE)
set(RISCV_EXT_A ON CACHE BOOL "" FORCE)  # Atomics
set(RISCV_EXT_C ON CACHE BOOL "" FORCE)  # Compressed instructions
set(RISCV_EXT_V OFF CACHE BOOL "" FORCE) # Vector (not needed yet)
set(RISCV_FCSR OFF CACHE BOOL "" FORCE)
set(RISCV_FLAT_RW_ARENA ON CACHE BOOL "" FORCE)
set(RISCV_THREADED ON CACHE BOOL "" FORCE)  # Computed goto dispatch
set(RISCV_BINARY_TRANSLATION OFF CACHE BOOL "" FORCE)  # Disable for Phase 1
set(RISCV_MEMORY_TRAPS ON CACHE BOOL "" FORCE)
set(RISCV_DEBUG OFF CACHE BOOL "" FORCE)
set(RISCV_EXPERIMENTAL OFF CACHE BOOL "" FORCE)

add_subdirectory(${LIBRISCV_DIR} ${CMAKE_BINARY_DIR}/libriscv)

# Our JNI bridge library
add_library(friscy_android SHARED
    friscy_runtime.cpp
)

# Include the friscy runtime headers (vfs, elf_loader, syscalls, etc.)
target_include_directories(friscy_android PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(friscy_android
    riscv
    android
    log
)

# libriscv needs exceptions
target_compile_options(friscy_android PRIVATE
    -O3
    -fexceptions
    -ffunction-sections
    -fdata-sections
)

target_link_options(friscy_android PRIVATE
    -Wl,--gc-sections
)
