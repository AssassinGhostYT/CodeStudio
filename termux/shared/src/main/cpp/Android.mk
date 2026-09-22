LOCAL_PATH:= $(call my-dir)
LOCAL_16K_PAGE_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(CLEAR_VARS)
LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS := $(LOCAL_16K_PAGE_LDFLAGS)
LOCAL_MODULE := local-socket
LOCAL_SRC_FILES := local-socket.cpp
include $(BUILD_SHARED_LIBRARY)
