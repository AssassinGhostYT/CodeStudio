LOCAL_PATH:= $(call my-dir)
LOCAL_16K_PAGE_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(CLEAR_VARS)
LOCAL_LDFLAGS := $(LOCAL_16K_PAGE_LDFLAGS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
include $(BUILD_SHARED_LIBRARY)
