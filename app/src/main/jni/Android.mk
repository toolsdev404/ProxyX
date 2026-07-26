# ProxyX native build.
#
# The vendored hev-socks5-tunnel already ships a complete Android JNI bridge
# (src/hev-jni.c), so it builds directly into libhev-socks5-tunnel.so with all
# four TProxy* native methods. We do NOT add our own bridge — we just build the
# engine. Its own Android.mk pulls in yaml, lwip and hev-task-system.
LOCAL_PATH := $(call my-dir)
include $(LOCAL_PATH)/hev-socks5-tunnel/Android.mk