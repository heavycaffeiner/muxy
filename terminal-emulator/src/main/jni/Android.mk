LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := ghostty_vt
LOCAL_SRC_FILES := ../../../build/native/$(TARGET_ARCH_ABI)/lib/libghostty-vt.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := muxy_terminal
LOCAL_SRC_FILES := muxy_terminal.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/../../../build/native/ghostty/include
LOCAL_STATIC_LIBRARIES := ghostty_vt
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror -Wno-c23-extensions -fstack-protector-strong
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,-z,relro -Wl,-z,now
LOCAL_LDLIBS := -lm -ldl -lz
include $(BUILD_SHARED_LIBRARY)
