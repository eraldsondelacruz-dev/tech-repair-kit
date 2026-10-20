cmake_minimum_required(VERSION 3.21)

project(BootloaderUnlocker
    VERSION 1.0
    LANGUAGES CXX
)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 REQUIRED COMPONENTS
    Quick
)

qt_standard_project_setup(REQUIRES 6.5)

qt_add_executable(BootloaderUnlocker
    qt_src/main.cpp
)

qt_add_qml_module(BootloaderUnlocker
    URI BootloaderUnlocker
    VERSION 1.0
    QML_FILES
        qt_src/Main.qml
)

target_link_libraries(BootloaderUnlocker
    PRIVATE
        Qt6::Quick
)

set_target_properties(BootloaderUnlocker PROPERTIES
    QT_ANDROID_PACKAGE_SOURCE_DIR
        "${CMAKE_CURRENT_SOURCE_DIR}/android"
)
