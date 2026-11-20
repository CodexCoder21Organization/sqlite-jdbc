package org.sqlite.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.ClearSystemProperty;
import org.junitpioneer.jupiter.SetSystemProperty;

@SetSystemProperty(key = "java.runtime.name", value = "OpenJDK Runtime Environment")
@SetSystemProperty(key = "os.name", value = "Linux")
@SetSystemProperty(key = "os.arch", value = "amd64")
class TermuxDetectionTest {
    @Test
    @SetSystemProperty(key = "java.vendor", value = "Termux")
    @SetSystemProperty(key = "java.vm.vendor", value = "Other")
    void javaVendorSelectsAndroidNativeLibrary() {
        assertThat(OSInfo.isAndroidTermux()).isTrue();
        assertThat(OSInfo.isAndroidRuntime()).isFalse();
        assertThat(OSInfo.isAndroid()).isTrue();
        assertThat(OSInfo.getNativeLibFolderPathForCurrentOS()).isEqualTo("Linux-Android/x86_64");
    }

    @Test
    @ClearSystemProperty(key = "java.vendor")
    @SetSystemProperty(key = "java.vm.vendor", value = "tErMuX")
    void vmVendorSelectsAndroidNativeLibraryWithoutJavaVendor() {
        assertThat(OSInfo.isAndroidTermux()).isTrue();
        assertThat(OSInfo.isAndroidRuntime()).isFalse();
        assertThat(OSInfo.isAndroid()).isTrue();
        assertThat(OSInfo.getNativeLibFolderPathForCurrentOS()).isEqualTo("Linux-Android/x86_64");
    }

    @Test
    @SetSystemProperty(key = "java.vendor", value = "NotTermux")
    @SetSystemProperty(key = "java.vm.vendor", value = "Termux-like")
    void unrelatedVendorsAreNotTermux() {
        assertThat(OSInfo.isAndroidTermux()).isFalse();
    }

    @Test
    @ClearSystemProperty(key = "java.vendor")
    @ClearSystemProperty(key = "java.vm.vendor")
    void absentVendorsAreNotTermux() {
        assertThat(OSInfo.isAndroidTermux()).isFalse();
    }
}
