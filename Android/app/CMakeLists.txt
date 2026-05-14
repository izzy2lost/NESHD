cmake_minimum_required(VERSION 3.22.1)
project(MesenCore CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# Source root is two levels up from Android/app/
set(MESEN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../..")

# ── Source globs ──────────────────────────────────────────────────────────────
# Android is a NES-only port. Keep the native build scoped to the shared runtime
# plus the NES core instead of compiling every desktop console backend.
file(GLOB CORE_BASE_SRC
    "${MESEN_ROOT}/Core/*.cpp"
)

file(GLOB_RECURSE CORE_SHARED_SRC
    "${MESEN_ROOT}/Core/Shared/*.cpp"
)
list(FILTER CORE_SHARED_SRC EXCLUDE REGEX ".*/Core/Shared/RecordedRomTest\\.cpp$")
list(FILTER CORE_SHARED_SRC EXCLUDE REGEX ".*/Core/Shared/CdReader\\.cpp$")

file(GLOB_RECURSE CORE_NES_SRC
    "${MESEN_ROOT}/Core/NES/*.cpp"
)
list(FILTER CORE_NES_SRC EXCLUDE REGEX ".*/Core/NES/Debugger/.*")

set(CORE_SRC
    ${CORE_BASE_SRC}
    ${CORE_SHARED_SRC}
    ${CORE_NES_SRC}
)

file(GLOB_RECURSE UTIL_CPP_SRC
    "${MESEN_ROOT}/Utilities/*.cpp"
)
list(FILTER UTIL_CPP_SRC EXCLUDE REGEX ".*/Utilities/Socket\\.cpp$")
list(FILTER UTIL_CPP_SRC EXCLUDE REGEX ".*/Utilities/UPnPPortMapper\\.cpp$")
file(GLOB_RECURSE UTIL_C_SRC
    "${MESEN_ROOT}/Utilities/*.c"
)

file(GLOB_RECURSE SEVENZIP_SRC
    "${MESEN_ROOT}/SevenZip/*.c"
)

# JNI + platform bridge sources
file(GLOB JNI_SRC
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/*.cpp"
)

# ── Library target ────────────────────────────────────────────────────────────
add_library(MesenCore SHARED
    ${CORE_SRC}
    ${UTIL_CPP_SRC}
    ${UTIL_C_SRC}
    ${SEVENZIP_SRC}
    ${JNI_SRC}
)

# ── Include paths ─────────────────────────────────────────────────────────────
target_include_directories(MesenCore PRIVATE
    "${MESEN_ROOT}"
    "${MESEN_ROOT}/Core"
    "${MESEN_ROOT}/Utilities"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp"
)

# ── Compile definitions ───────────────────────────────────────────────────────
target_compile_definitions(MesenCore PRIVATE
    __ANDROID__=1
    # Android only ships the NES runtime surface.
    MESEN_NES_ONLY=1
    MESEN_DISABLE_DEBUGGER=1
    # Disable NetPlay networking on Android (uses sockets not needed)
    MESEN_NO_NETPLAY=1
)

# ── Compile options ───────────────────────────────────────────────────────────
target_compile_options(MesenCore PRIVATE
    $<$<COMPILE_LANGUAGE:CXX>:
        -Wall
        -Wno-switch
        -Wno-unused-variable
        -Wno-unused-parameter
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
    >
    $<$<COMPILE_LANGUAGE:C>:
        -Wall
        -Wno-unused-variable
        -Wno-unused-parameter
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
    >
    $<$<AND:$<COMPILE_LANGUAGE:CXX>,$<CONFIG:Release>>:-O3>
    $<$<AND:$<COMPILE_LANGUAGE:CXX>,$<CONFIG:Release>>:-DNDEBUG>
    $<$<AND:$<COMPILE_LANGUAGE:C>,$<CONFIG:Release>>:-O3>
    $<$<AND:$<COMPILE_LANGUAGE:C>,$<CONFIG:Release>>:-DNDEBUG>
    $<$<AND:$<COMPILE_LANGUAGE:CXX>,$<CONFIG:Debug>>:-O0>
    $<$<AND:$<COMPILE_LANGUAGE:CXX>,$<CONFIG:Debug>>:-g>
    $<$<AND:$<COMPILE_LANGUAGE:C>,$<CONFIG:Debug>>:-O0>
    $<$<AND:$<COMPILE_LANGUAGE:C>,$<CONFIG:Debug>>:-g>
)

# ── Linker options ────────────────────────────────────────────────────────────
target_link_options(MesenCore PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)

# ── System libraries ──────────────────────────────────────────────────────────
find_library(log-lib    log)
find_library(android-lib android)
find_library(aaudio-lib aaudio)
find_library(gles3-lib  GLESv3)
find_library(egl-lib    EGL)
find_library(z-lib      z)

target_link_libraries(MesenCore
    ${log-lib}
    ${android-lib}
    ${aaudio-lib}
    ${gles3-lib}
    ${egl-lib}
    ${z-lib}
    c++_shared
)
