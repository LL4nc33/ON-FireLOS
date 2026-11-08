#
# ON-FireAI: the assistant of ON-FireLOS.
# Built as packages/apps/ONFireAI (scripts/onfireai-into-tree.sh puts it there).
#

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := ONFireAI
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
# Platform-signed, but not privileged: its signature permissions (media sessions,
# secure settings) come from the certificate, so no privapp whitelist is needed.
LOCAL_CERTIFICATE := platform
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_PROGUARD_ENABLED := disabled

include $(BUILD_PACKAGE)
