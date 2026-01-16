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

# Create the shared library for JNI wrapper
add_library(hostai
        SHARED
        llama_jni.cpp)

# Link against Android log library
target_link_libraries(hostai
        ${log-lib})

# TODO: When adding full llama.cpp integration:
# 1. Add llama.cpp source files or link to pre-built library:
#    add_subdirectory(llama.cpp)
#    or
#    include_directories(llama.cpp/include)
#    file(GLOB LLAMA_SOURCES llama.cpp/*.cpp)
#    add_library(llama STATIC ${LLAMA_SOURCES})
#
# 2. Link llama.cpp library:
#    target_link_libraries(hostai llama ${log-lib})
#
# 3. Add necessary compiler definitions:
#    target_compile_definitions(hostai PRIVATE GGML_USE_ACCELERATE)
#
# 4. For ARM NEON optimizations (common on Android):
#    if(ANDROID_ABI STREQUAL "armeabi-v7a" OR ANDROID_ABI STREQUAL "arm64-v8a")
#        target_compile_definitions(hostai PRIVATE GGML_USE_NEON)
#    endif()
