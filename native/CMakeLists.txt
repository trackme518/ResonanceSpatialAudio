# Builds resonanceaudio_jni.dll: Resonance Audio renderer + JNI bridge.
cmake_minimum_required(VERSION 3.16)
project(ResonanceAudioJNI CXX C)

set(BUILD_RESONANCE_AUDIO_API ON CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory(
        ${CMAKE_CURRENT_SOURCE_DIR}/../third_party_resonance
        ${CMAKE_CURRENT_BINARY_DIR}/resonance_build)

find_package(JNI REQUIRED)
find_package(Threads REQUIRED)

add_library(resonanceaudio_jni SHARED resonance_audio_jni.cpp)
set(RESONANCE_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../third_party_resonance)
target_include_directories(resonanceaudio_jni PRIVATE
        ${JNI_INCLUDE_DIRS}
        ${RESONANCE_ROOT}
        ${RESONANCE_ROOT}/resonance_audio
        ${RESONANCE_ROOT}/third_party/eigen)
target_compile_definitions(resonanceaudio_jni PRIVATE
        _USE_MATH_DEFINES NOMINMAX EIGEN_MPL2_ONLY)
target_link_libraries(resonanceaudio_jni PRIVATE ResonanceAudioStatic Threads::Threads)

# Use the platform's normal naming on Unix (libresonanceaudio_jni.so/.dylib),
# but keep the Windows DLL unprefixed.
if (WIN32)
    set_target_properties(resonanceaudio_jni PROPERTIES PREFIX "")
endif()
