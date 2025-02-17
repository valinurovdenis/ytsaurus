
# This file was generated by the build system used internally in the Yandex monorepo.
# Only simple modifications are allowed (adding source-files to targets, adding simple properties
# like target_include_directories). These modifications will be ported to original
# ya.make files by maintainers. Any complex modifications which can't be ported back to the
# original buildsystem will not be accepted.



add_executable(cpp-integration-test-cypress-election)
target_compile_options(cpp-integration-test-cypress-election PRIVATE
  -Wdeprecated-this-capture
)
target_link_libraries(cpp-integration-test-cypress-election PUBLIC
  contrib-libs-linux-headers
  contrib-libs-cxxsupp
  yutil
  library-cpp-cpuid_check
  cpp-testing-gtest
  cpp-testing-gtest_main
  library-query-engine
  yt-core-test_framework
  server-lib-cypress_election
  tests-cpp-test_base
  yt-yt-ytlib
)
target_link_options(cpp-integration-test-cypress-election PRIVATE
  -ldl
  -lrt
  -Wl,--no-as-needed
  -fPIC
  -fPIC
  -lpthread
  -lrt
  -ldl
  -lutil
)
target_sources(cpp-integration-test-cypress-election PRIVATE
  ${CMAKE_SOURCE_DIR}/yt/yt/tests/cpp/test_cypress_election/test_cypress_election.cpp
)
add_test(
  NAME
  cpp-integration-test-cypress-election
  COMMAND
  cpp-integration-test-cypress-election
)
set_property(
  TEST
  cpp-integration-test-cypress-election
  PROPERTY
  LABELS
  MEDIUM
)
set_property(
  TEST
  cpp-integration-test-cypress-election
  PROPERTY
  PROCESSORS
  4
)
target_allocator(cpp-integration-test-cypress-election
  cpp-ytalloc-impl
)
vcs_info(cpp-integration-test-cypress-election)
