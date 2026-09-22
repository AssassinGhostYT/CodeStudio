LOCAL_PATH:= $(call my-dir)
LOCAL_16K_PAGE_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(CLEAR_VARS)
LOCAL_LDFLAGS := $(LOCAL_16K_PAGE_LDFLAGS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
include $(BUILD_SHARED_LIBRARY)
