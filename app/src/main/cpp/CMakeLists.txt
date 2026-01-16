cmake_minimum_required(VERSION 3.22.1)

project("hostai")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add compiler flags for optimization
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fvisibility=hidden")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -fvisibility=hidden")

# Find the log library
find_library(log-lib log)

# Configure llama.cpp
set(GGML_LTO OFF)
set(GGML_STATIC ON)

# Enable NEON optimizations for ARM
if(ANDROID_ABI STREQUAL "armeabi-v7a" OR ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_NEON ON)
    message(STATUS "Enabling NEON optimizations for ${ANDROID_ABI}")
endif()

# Add llama.cpp source files
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

include_directories(${LLAMA_DIR})
include_directories(${LLAMA_DIR}/include)
include_directories(${LLAMA_DIR}/ggml/include)
include_directories(${LLAMA_DIR}/ggml/src)
include_directories(${LLAMA_DIR}/src)

# Add ggml sources
file(GLOB GGML_SOURCES 
    ${LLAMA_DIR}/ggml/src/*.c
    ${LLAMA_DIR}/ggml/src/*.cpp
)

# Add llama.cpp sources
file(GLOB LLAMA_SOURCES
    ${LLAMA_DIR}/src/llama*.cpp
)

# Extract version information from llama.cpp's CMakeLists.txt
file(STRINGS "${LLAMA_DIR}/ggml/CMakeLists.txt" GGML_VERSION_MAJOR_LINE REGEX "set\\(GGML_VERSION_MAJOR [0-9]+\\)")
file(STRINGS "${LLAMA_DIR}/ggml/CMakeLists.txt" GGML_VERSION_MINOR_LINE REGEX "set\\(GGML_VERSION_MINOR [0-9]+\\)")
file(STRINGS "${LLAMA_DIR}/ggml/CMakeLists.txt" GGML_VERSION_PATCH_LINE REGEX "set\\(GGML_VERSION_PATCH [0-9]+\\)")

string(REGEX MATCH "[0-9]+" GGML_VERSION_MAJOR "${GGML_VERSION_MAJOR_LINE}")
string(REGEX MATCH "[0-9]+" GGML_VERSION_MINOR "${GGML_VERSION_MINOR_LINE}")
string(REGEX MATCH "[0-9]+" GGML_VERSION_PATCH "${GGML_VERSION_PATCH_LINE}")

set(GGML_VERSION "${GGML_VERSION_MAJOR}.${GGML_VERSION_MINOR}.${GGML_VERSION_PATCH}")

# Get git commit hash for version info
execute_process(
    COMMAND git rev-parse --short HEAD
    WORKING_DIRECTORY ${LLAMA_DIR}
    OUTPUT_VARIABLE GGML_BUILD_COMMIT
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET
)

# Set default version if git command fails
if(NOT GGML_BUILD_COMMIT)
    set(GGML_BUILD_COMMIT "unknown")
endif()

# Create llama static library
add_library(llama STATIC ${GGML_SOURCES} ${LLAMA_SOURCES})

# Define version macros required by ggml
target_compile_definitions(llama PRIVATE 
    GGML_VERSION="${GGML_VERSION}"
    GGML_COMMIT="${GGML_BUILD_COMMIT}"
)

# Enable NEON for ARM architectures
if(ANDROID_ABI STREQUAL "armeabi-v7a" OR ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(llama PRIVATE GGML_USE_NEON)
endif()

# Create the shared library for JNI wrapper
add_library(hostai
        SHARED
        llama_jni.cpp)

# Link against Android log library and llama
target_link_libraries(hostai
        llama
        ${log-lib})
