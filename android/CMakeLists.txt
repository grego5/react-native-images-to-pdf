cmake_minimum_required(VERSION 3.13)
project(NitroImagesToPdf)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(NitroImagesToPdf SHARED
  src/main/cpp/cpp-adapter.cpp
)

include(${CMAKE_SOURCE_DIR}/../nitrogen/generated/android/NitroImagesToPdf+autolinking.cmake)
