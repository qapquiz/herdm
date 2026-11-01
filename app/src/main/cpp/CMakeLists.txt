cmake_minimum_required(VERSION 3.22.1)
project(ghosttydroid CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Prebuilt libghostty-vt for this ABI (see scripts/vendor.sh to rebuild from
# third_party/ghostty at the pinned commit).
add_library(ghostty-vt STATIC IMPORTED)
set_target_properties(ghostty-vt PROPERTIES
  IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/${ANDROID_ABI}/libghostty-vt.a)

add_library(ghostty_android SHARED
  ghostty_jni.cpp
  pty_jni.cpp)

target_include_directories(ghostty_android PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/include)
# Static linking: keep GHOSTTY_API a no-op (see ghostty/vt/types.h).
target_compile_definitions(ghostty_android PRIVATE GHOSTTY_STATIC)
target_compile_options(ghostty_android PRIVATE -Wall -fvisibility=hidden)
# Android 15+ devices require 16 KB page alignment.
target_link_options(ghostty_android PRIVATE -Wl,-z,max-page-size=16384)

if(ANDROID_ABI STREQUAL "x86_64")
  # Zig emits general-dynamic TLS on x86_64, which needs __tls_get_addr;
  # bionic only exports it from API 29. Let it stay undefined at link time
  # (overrides AGP's --no-undefined): API 29+ resolves it from libc.so.
  target_link_options(ghostty_android PRIVATE -Wl,-z,undefs)
endif()

target_link_libraries(ghostty_android ghostty-vt log)
