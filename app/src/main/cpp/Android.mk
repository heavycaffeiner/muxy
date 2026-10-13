LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
LOCAL_ADDITIONAL_DEPENDENCIES := \
    $(LOCAL_PATH)/bootstrap-aarch64.zip \
    $(LOCAL_PATH)/bootstrap-arm.zip \
    $(LOCAL_PATH)/bootstrap-i686.zip \
    $(LOCAL_PATH)/bootstrap-x86_64.zip
include $(BUILD_SHARED_LIBRARY)

# Assembly .incbin inputs are not recorded by the C preprocessor.
$(LOCAL_OBJS_DIR)/termux-bootstrap-zip.o: $(LOCAL_ADDITIONAL_DEPENDENCIES)
