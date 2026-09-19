#ifndef LUNARIS_HOST_ANDROID_LOG_H
#define LUNARIS_HOST_ANDROID_LOG_H
#include <stdarg.h>
#include <stdio.h>
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_INFO 4
static inline int __android_log_print(int priority, const char *tag, const char *format, ...) {
    (void) priority;
    (void) tag;
    va_list args;
    va_start(args, format);
    int result = vfprintf(stderr, format, args);
    va_end(args);
    return result;
}
#endif
