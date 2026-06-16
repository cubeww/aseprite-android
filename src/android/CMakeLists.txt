# Aseprite Android bridge
# Copyright (C) 2026  Igara Studio S.A.
#
# This program is distributed under the terms of
# the End-User License Agreement for Aseprite.

add_library(aseprite-android SHARED
  aseprite_android.cpp
  ../main/main.cpp)

find_library(ANDROID_LOG_LIBRARY log)
if(ANDROID_LOG_LIBRARY)
  target_link_libraries(aseprite-android PRIVATE ${ANDROID_LOG_LIBRARY})
endif()

target_link_libraries(aseprite-android PRIVATE app-lib)

set_target_properties(aseprite-android PROPERTIES
  OUTPUT_NAME aseprite-android)
