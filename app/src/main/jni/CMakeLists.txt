cmake_minimum_required(VERSION 3.18)
project(mtk_thal_capture C)

# Standalone root binary for MediaTek THAL screen capture.
# Uses libthal_capture.so (HIDL client) → do_capture_window() with DMA heap.
# Built as executable but named lib*.so so Android Gradle packages it in the APK.
add_executable(libmtk_thal_capture_server.so
    mtk_thal_capture_server.c
)

target_link_libraries(libmtk_thal_capture_server.so
    log
    dl
)

set_target_properties(libmtk_thal_capture_server.so PROPERTIES
    PREFIX ""
    SUFFIX ""
)

# Самостоятельный сервер VSS для ARM32. Фирменные библиотеки и заголовки
# не включаются: символы MTVSS_* загружаются из /vendor/lib при запуске.
add_executable(libmstar_vss_server.so
    mstar_vss_server.c
)
target_link_libraries(libmstar_vss_server.so dl)
set_target_properties(libmstar_vss_server.so PROPERTIES PREFIX "" SUFFIX "")
