#include "wang_a1ex_android_4over6_NdkUtil.h"
JNIEXPORT jstring JNICALL
Java_wang_a1ex_android_14over6_NdkUtil_getString(JNIEnv *env, jobject obj) {
     return (*env)->NewStringUTF(env,"Test C String");
}
