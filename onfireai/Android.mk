#
# ON-FireAI: the assistant of ON-FireLOS.
# Built as packages/apps/ONFireAI (scripts/onfireai-into-tree.sh puts it there).
#

LOCAL_PATH := $(call my-dir)
# The wake word's library and model, when scripts/fetch-onfireai-kws.sh fetched them.
ONFIREAI_KWS := $(wildcard $(LOCAL_PATH)/jni/arm64-v8a/libsherpa-onnx-jni.so)

include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := ONFireAI
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
LOCAL_ASSET_DIR := $(LOCAL_PATH)/assets
# The model is read straight from the APK; stored, not compressed.
LOCAL_AAPT_FLAGS := -0 .onnx
# Platform-signed, but not privileged: its signature permissions (media sessions,
# secure settings) come from the certificate, so no privapp whitelist is needed.
LOCAL_CERTIFICATE := platform
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_PROGUARD_ENABLED := disabled
ifneq ($(ONFIREAI_KWS),)
LOCAL_JNI_SHARED_LIBRARIES := libsherpa-onnx-jni
LOCAL_MULTILIB := 64
endif

include $(BUILD_PACKAGE)

ifneq ($(ONFIREAI_KWS),)
include $(CLEAR_VARS)
LOCAL_MODULE := libsherpa-onnx-jni
LOCAL_MODULE_CLASS := SHARED_LIBRARIES
LOCAL_MODULE_SUFFIX := .so
LOCAL_SRC_FILES := jni/arm64-v8a/libsherpa-onnx-jni.so
LOCAL_MULTILIB := 64
LOCAL_MODULE_TARGET_ARCH := arm64
LOCAL_STRIP_MODULE := false
include $(BUILD_PREBUILT)
endif
