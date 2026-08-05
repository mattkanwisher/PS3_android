# Distributed under the OSI-approved BSD 3-Clause License.  See accompanying
# file Copyright.txt or https://cmake.org/licensing for details.

cmake_minimum_required(VERSION 3.5)

file(MAKE_DIRECTORY
  "/home/user/PS3_android/build-host/_deps/absl-src"
  "/home/user/PS3_android/build-host/_deps/absl-build"
  "/home/user/PS3_android/build-host/_deps/absl-subbuild/absl-populate-prefix"
  "/home/user/PS3_android/build-host/_deps/absl-subbuild/absl-populate-prefix/tmp"
  "/home/user/PS3_android/build-host/_deps/absl-subbuild/absl-populate-prefix/src/absl-populate-stamp"
  "/home/user/PS3_android/build-host/_deps/absl-subbuild/absl-populate-prefix/src"
  "/home/user/PS3_android/build-host/_deps/absl-subbuild/absl-populate-prefix/src/absl-populate-stamp"
)

set(configSubDirs )
foreach(subDir IN LISTS configSubDirs)
    file(MAKE_DIRECTORY "/home/user/PS3_android/build-host/_deps/absl-subbuild/absl-populate-prefix/src/absl-populate-stamp/${subDir}")
endforeach()
if(cfgdir)
  file(MAKE_DIRECTORY "/home/user/PS3_android/build-host/_deps/absl-subbuild/absl-populate-prefix/src/absl-populate-stamp${cfgdir}") # cfgdir has leading slash
endif()
