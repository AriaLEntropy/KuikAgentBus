cmake_minimum_required(VERSION 3.4.1)
project(agentbus_entry)

set(NATIVERENDER_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR})

if (NOT OHOS_ARCH)
    set(OHOS_ARCH arm64-v8a)
endif ()

set(ENTRY_LIBS_DIR ${NATIVERENDER_ROOT_PATH}/../../../libs/${OHOS_ARCH})
set(KUIKLY_RENDER_SO ${NATIVERENDER_ROOT_PATH}/../../../oh_modules/@kuikly-open/render/libs/${OHOS_ARCH}/libkuikly.so)
set(SHARED_SO ${ENTRY_LIBS_DIR}/libshared.so)

include_directories(${NATIVERENDER_ROOT_PATH}
        ${NATIVERENDER_ROOT_PATH}/include)

function(import_prebuilt_shared name so_path)
    if (NOT EXISTS ${so_path})
        message(FATAL_ERROR
                "missing native library ${name}: ${so_path}\n"
                "Run: ./gradlew -c settings.ohos.gradle.kts :shared:linkDebugSharedOhosArm64 and copy the .so into ohosApp/entry/libs/arm64-v8a")
    endif ()
    add_library(${name} SHARED IMPORTED)
    set_target_properties(${name}
            PROPERTIES
            IMPORTED_LOCATION ${so_path})
endfunction()

import_prebuilt_shared(kuikly_render ${KUIKLY_RENDER_SO})
import_prebuilt_shared(kuikly_shared ${SHARED_SO})

add_library(entry SHARED napi_init.cpp)
target_link_directories(entry PUBLIC ${ENTRY_LIBS_DIR})
target_link_libraries(entry PUBLIC
        libace_napi.z.so
        libhilog_ndk.z.so
        kuikly_shared
        kuikly_render)
