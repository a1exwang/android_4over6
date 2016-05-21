

/**************************************************************************
 * simpletun.c                                                            *
 *                                                                        *
 * A simplistic, simple-minded, naive tunnelling program using tun/tap    *
 * interfaces and TCP. Handles (badly) IPv4 for tun, ARP and IPv4 for     *
 * tap. DO NOT USE THIS PROGRAM FOR SERIOUS PURPOSES.                     *
 *                                                                        *
 * You have been warned.                                                  *
 *                                                                        *
 * (C) 2009 Davide Brini.                                                 *
 *                                                                        *
 * DISCLAIMER AND WARNING: this is all work in progress. The code is      *
 * ugly, the algorithms are naive, error checking and input validation    *
 * are very basic, and of course there can be bugs. If that's not enough, *
 * the program has not been thoroughly tested, so it might even fail at   *
 * the few simple things it should be supposed to do right.               *
 * Needless to say, I take no responsibility whatsoever for what the      *
 * program might do. The program has been written mostly for learning     *
 * purposes, and can be used in the hope that is useful, but everything   *
 * is to be taken "as is" and without any kind of warranty, implicit or   *
 * explicit. See the file LICENSE for further details.                    *
 *************************************************************************/
#include "wang_a1ex_android_4over6_VpnDevices.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <linux/if.h>
#include <linux/if_tun.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <arpa/inet.h>
#include <sys/select.h>
#include <sys/time.h>
#include <errno.h>
#include <stdarg.h>
#include <sys/timerfd.h>
#include <android/log.h>

#define TAG "VpnDevices"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,TAG ,__VA_ARGS__)

int debug = 1;

#define IVI_MESSAGE_MAX_SIZE 4096

typedef struct tIVIMessage {
    int length;
    char type;
    char data[IVI_MESSAGE_MAX_SIZE];
} IVIMessage;
#define MSG_DHCP_REQUEST 100
#define MSG_DHCP_RESPOND 101
#define MSG_SEND_DATA 102
#define MSG_RECEIVE_DATA 103
#define MSG_HEARTBEAT 104

#define HEARTBEAT_INTERVAL_SECONDS 5
#define JAVA_JNI_PIPE_JTOC_PATH "/tmp/wang.a1ex.android_4over6.pipe.jtoc"
#define JAVA_JNI_PIPE_CTOJ_PATH "/tmp/wang.a1ex.android_4over6.pipe.ctoj"
#define JAVA_JNI_PIPE_BUFFER_MAX_SIZE 1024
#define JAVA_JNI_PIPE_OPCODE_SHUTDOWN 100

#define IVI_SERVER_IPV6_ADDRESS "2402:f000:1:4417::900"
#define IVI_SERVER_TCP_PORT 5678

/**************************************************************************
 * do_debug: prints debugging stuff (doh!)                                *
 **************************************************************************/
static char do_debug_buf[5000] = { 0 };
void do_debug(char *msg, ...){

    va_list argp;
    if(debug){
        va_start(argp, msg);
        vsprintf(do_debug_buf, msg, argp);
        va_end(argp);
    }
    LOGD("%s", do_debug_buf);
}

int get_tun_fd(int sock_fd, JNIEnv *env, jobject callbacks, jclass cls_callbacks) {
    IVIMessage dhcp_req;
    IVIMessage dhcp_res;
    memset(&dhcp_req, 0, sizeof(IVIMessage));
    memset(&dhcp_res, 0, sizeof(IVIMessage));

    dhcp_req.length = 5;
    dhcp_req.type = MSG_DHCP_REQUEST;

    //struct timeval tv;

    //tv.tv_sec = 20;  /* 30 Secs Timeout */
    //tv.tv_usec = 0;  // Not init'ing this can cause strange errors
    //setsockopt(sock_fd, SOL_SOCKET, SO_RCVTIMEO, (char *) &tv, sizeof(struct timeval));

    char *dhcp_str = NULL;
    int i = 0;
    for (i = 0; i < 100; ++i) {
        do_debug("start get_tun_fd");
        int count;
        count = write(sock_fd, &dhcp_req, dhcp_req.length);
        if (count < 0)
            return -1;
        count = read(sock_fd, &dhcp_res.length, sizeof(dhcp_res.length));
        if (count < 0)
            return -1;
        count = read(sock_fd, &dhcp_res.type, sizeof(dhcp_res) - sizeof(dhcp_res.length));
        if (count < 0)
            return -1;
        if (count >= 1 && dhcp_res.type == MSG_DHCP_RESPOND) {
            dhcp_str = dhcp_res.data;
           // do_debug("before crash?");
//            dhcp_res.data[count-1] = 0;
            //do_debug("dhcp string: %s", dhcp_str);
            break;
        }
        else {
            do_debug("receive non dhcp respond");
        }
    }

    if (dhcp_str == NULL) {
        return -1;
    }
    // call this.vpnCallbacks.onReceiveDhcpAndCreateTun(dhcp_str);
    jmethodID jmethod_create_tun = (*env)->GetMethodID(env,
                                                         cls_callbacks,
                                                         "onReceiveDhcpAndCreateTun",
                                                         "(Ljava/lang/String;)I");
    jstring jdhcp_str = (*env)->NewStringUTF(env, dhcp_str);
    do_debug("before call java method");
    jint jint_fd = (*env)->CallIntMethod(env, callbacks, jmethod_create_tun, jdhcp_str);
    (*env)->DeleteLocalRef(env, jdhcp_str);

    return jint_fd;
}

void on_statistics(int r_bytes, int r_packets, int s_bytes, int s_packets, JNIEnv *env, jobject callbacks, jclass cls_callbacks) {
    jmethodID method = (*env)->GetMethodID(env, cls_callbacks, "onStatistics", "(IIII)V");
    (*env)->CallVoidMethod(env, callbacks, method, r_bytes, r_packets, s_bytes, s_packets);
}

void on_heartbeat(JNIEnv *env, jobject callbacks, jclass cls_callbacks) {
    jmethodID method = (*env)->GetMethodID(env, cls_callbacks, "onHeartbeat", "()V");
    (*env)->CallVoidMethod(env, callbacks, method);
}

void on_received_packet(IVIMessage *message, JNIEnv *env, jobject callbacks, jclass cls_callbacks) {
    jint len = message->length;
    jbyte type = message->type;

    jbyteArray packet = (*env)->NewByteArray(env, message->length - sizeof(message->length) - sizeof(message->type));
    (*env)->SetByteArrayRegion(env, packet, 0, message->length - sizeof(message->length) - sizeof(message->type), (jbyte*)message->data);

    jmethodID method = (*env)->GetMethodID(env, cls_callbacks, "onPacketReceived", "(IB[B)V");
    (*env)->CallVoidMethod(env, callbacks, method, len, type, packet);
    (*env)->DeleteLocalRef(env, packet);
}

void on_sent_packet(IVIMessage *message, JNIEnv *env, jobject callbacks, jclass cls_callbacks) {
    jint len = message->length;
    jbyte type = message->type;

    jobjectArray packet = (*env)->NewByteArray(env, message->length - sizeof(message->length) - sizeof(message->type));
    (*env)->SetByteArrayRegion(env, packet, 0, message->length - sizeof(message->length) - sizeof(message->type), (jbyte*)message->data);

    jmethodID method = (*env)->GetMethodID(env, cls_callbacks, "onPacketSent", "(IB[B)V");
    (*env)->CallVoidMethod(env, callbacks, method, len, type, packet);
    (*env)->DeleteLocalRef(env, packet);
}

/**************************************************************************
 * read_n: ensures we read exactly n bytes, and puts those into "buf".    *
 *         (unless EOF, of course)                                        *
 **************************************************************************/
int read_n(int fd, char *buf, int n) {

     int nread, left = n;

     while(left > 0) {
          if ((nread = read(fd, buf, left))==0){
               return 0 ;
          }else {
               left -= nread;
               buf += nread;
          }
     }
     return n;
}
static char ip_str[1000] = { 0 };
int startVpn(JNIEnv *env, jobject this, jobject callbacks, jclass cls_callbacks, jshort jport) {

    int nread, nwrite;
    struct sockaddr_in6 remote;
    struct sockaddr_in java_addr;
    int net_fd, tun_fd, max_fd, timer_fd, jsock_fd;
    int tun2net = 0, net2tun = 0, tun2net_bytes = 0, net2tun_bytes = 0;
    int ret;

    do_debug("startVpn start");

    if ((jsock_fd = socket(AF_INET, SOCK_STREAM, 0)) < 0) {
        do_debug("socket()");
        return -1;
    }

    /* Client, try to connect to server */
    memset(&java_addr, 0, sizeof(java_addr));
    java_addr.sin_family = AF_INET;
    inet_pton(AF_INET, "127.0.0.1", &java_addr.sin_addr.s_addr);
    java_addr.sin_port = htons(jport);

    /* connection request */
    ret = connect(jsock_fd, (struct sockaddr *) &java_addr, sizeof(java_addr));
    if (ret < 0) {
        do_debug("connect() returns %d, errno, %d", ret, errno);
        close(jsock_fd);
        return -1;
    }

    inet_ntop(AF_INET, &java_addr.sin_addr, ip_str, sizeof(ip_str));
    do_debug("Connected to java %s\n", ip_str);

    do_debug("started creating ipv6 socket");
    if ((net_fd = socket(AF_INET6, SOCK_STREAM, 0)) < 0) {
        do_debug("socket()");
        //close(jsock_fd);
        return -1;
    }

    /* Client, try to connect to server */
    memset(&remote, 0, sizeof(remote));
    remote.sin6_family = AF_INET6;
    inet_pton(AF_INET6, IVI_SERVER_IPV6_ADDRESS, remote.sin6_addr.s6_addr);
    remote.sin6_port = htons(IVI_SERVER_TCP_PORT);

    /* connection request */
    ret = connect(net_fd, (struct sockaddr *) &remote, sizeof(remote));
    if (ret < 0) {
        do_debug("netfd connect() returns %d, errno, %d", ret, errno);
        close(net_fd);
        //close(jsock_fd);
        return -1;
    }

//    inet_ntop(AF_INET6, &remote.sin6_addr, ip_str, sizeof(ip_str));
//    do_debug("CLIENT: Connected to server %s\n", ip_str);

    /* initialize tun/tap interface */
    tun_fd = get_tun_fd(net_fd, env, callbacks, cls_callbacks);
    if (tun_fd < 0) {
        close(net_fd);
        close(jsock_fd);
        return -1;
    }
    do_debug("Successfully established tun device, fd %d", tun_fd);

    int flags = fcntl(net_fd, F_GETFL, 0);
    if (flags < 0)
        do_debug("change socket to non-blocking failed: fcntl failed, errno: %d", errno);
    flags |= O_NONBLOCK;
    if (fcntl(net_fd, F_SETFL, flags) < 0)
        do_debug("change socket to non-blocking failed: fcntl 2 failed, errno: %d", errno);
    do_debug("change socket to non-blocking done");

    /* initialize a timer */
    timer_fd = timerfd_create(CLOCK_REALTIME, NULL);
    struct itimerspec timer_p;
    timer_p.it_value.tv_nsec = 0;
    timer_p.it_value.tv_sec = 0;
    timer_p.it_interval.tv_nsec = 0;
    timer_p.it_interval.tv_sec = HEARTBEAT_INTERVAL_SECONDS;
    if (0 > timerfd_settime(timer_fd, TFD_TIMER_ABSTIME, &timer_p, NULL)) {
        do_debug("timerfd_settimer failed");
        close(tun_fd);
        close(net_fd);
        close(jsock_fd);
        return -1;
    }

    /* use select() to handle all descriptors at once */
    max_fd = net_fd;
    max_fd = (max_fd > tun_fd) ? max_fd : tun_fd;
    max_fd = (max_fd > timer_fd) ? max_fd : timer_fd;
    max_fd = (max_fd > jsock_fd) ? max_fd : jsock_fd;

    do_debug("startVpn 4");
    while (1) {
        fd_set rd_set;

        FD_ZERO(&rd_set);
        FD_SET(tun_fd, &rd_set);
        FD_SET(net_fd, &rd_set);
        FD_SET(timer_fd, &rd_set);
        FD_SET(jsock_fd, &rd_set);

        ret = select(max_fd + 1, &rd_set, NULL, NULL, NULL);
        if (ret < 0 && errno == EINTR) {
            continue;
        }

        if (ret < 0) {
            do_debug("select error, error %d", errno);
            break;
        }

        if (FD_SET(jsock_fd, &rd_set)) {
            size_t size;
            do_debug("start read pipe");
            if (read(jsock_fd, &size, sizeof(size)) && size < JAVA_JNI_PIPE_BUFFER_MAX_SIZE) {
                do_debug("read pipe failed");
            }

            char *buf = malloc(size);
            int rresult = read(jsock_fd, buf, size);
            if (rresult != size) {
                do_debug("java jni pipe packet wrong format");
                continue;
            }
            char *str = buf + 1;
            int shutdown = 0;
            switch(buf[0]) {
                case JAVA_JNI_PIPE_OPCODE_SHUTDOWN:
                    shutdown = 1;
                    break;
                default:
                    do_debug("java jni pipe unknown opcode");
            }

            free(buf);

            if (shutdown) {
                break;
            }
        }

        if (FD_ISSET(timer_fd, &rd_set)) {
            IVIMessage message;
            timerfd_gettime(timer_fd, NULL);
            message.length = 5;
            message.type = MSG_HEARTBEAT;
            nwrite = write(net_fd, (char*)&message, (size_t) message.length);
            if (nwrite != message.length) {
                do_debug("send heartbeat failed %d, errno", nwrite, errno);
            }
            else {
                do_debug("send heartbeat success!");
            }
        }

        if (FD_ISSET(tun_fd, &rd_set)) {
            IVIMessage message;
            /* data from tun/tap: just read it and write it to the network */
            nread = read(tun_fd, message.data, 4096);
            if (nread < 0 || nread > 4096) {
                do_debug("read from tun failed, error %d", errno);
                continue;
            }
            message.type = MSG_SEND_DATA;
            message.length = nread + sizeof(message.type) + sizeof(message.length);

            tun2net++;
            tun2net_bytes += nread;
//            do_debug("TUN2NET %d: Read %d bytes from the tun interface\n", tun2net, nread);

            /* write length + packet */
            on_sent_packet(&message, env, callbacks, cls_callbacks);
            nwrite = write(net_fd, (char *) &message, (size_t) message.length);
            if (nwrite < 0)
                do_debug("TUN2NET write error");
            //do_debug("TUN2NET %d: Written %d bytes to the network\n", tun2net, nwrite);
        }

        if (FD_ISSET(net_fd, &rd_set)) {
            /* data from the network: read it, and write it to the tun/tap interface.
             * We need to read the length first, and then the packet */

            int bytes_available;
            if (ioctl(net_fd, FIONREAD, &bytes_available) < 0 || bytes_available < 0) {
                do_debug("ioctl netfd FIONREAD failed errno %d", errno);
                continue;
            }

            char *temp_buf = malloc((size_t) bytes_available);
            nread = read_n(net_fd, temp_buf, bytes_available);

            if (nread < 5 || nread > sizeof(IVIMessage)) {
                do_debug("wrong packet size %d", nread);
                free(temp_buf);
                continue;
            }

            IVIMessage *pmessage = (IVIMessage*) temp_buf;
            if (pmessage->length != nread) {
                do_debug("illegal packet, message.length = %d, nread = %d", pmessage->length, nread);
                free(temp_buf);
                continue;
            }

//            do_debug("NET2TUN %d: Read %d bytes from the network\n", net2tun, nread);
            on_received_packet(pmessage, env, callbacks, cls_callbacks);

            switch (pmessage->type) {
                case MSG_RECEIVE_DATA:
                    net2tun++;
                    net2tun_bytes += nread;
                    nwrite = (uint32_t) write(tun_fd,
                                              (char*)pmessage->data,
                                              pmessage->length - sizeof(pmessage->length) - sizeof(pmessage->type));
                    if (nwrite < 0) {
                        do_debug("NET2TUN write error");
                    }
//                    do_debug("NET2TUN %d: Written %d bytes to the tun interface\n", net2tun, nwrite);
                    break;
                case MSG_HEARTBEAT:
                    on_heartbeat(env, callbacks, cls_callbacks);
                    do_debug("heartbeat received");
                    break;
                default:
                    do_debug("packet type unknown!");
            }
            free(temp_buf);
        }
        on_statistics(net2tun_bytes, net2tun, tun2net_bytes, tun2net, env, callbacks, cls_callbacks);
    }

    do_debug("quit");
    close(timer_fd);
    close(tun_fd);
    close(net_fd);
    close(jsock_fd);

    return 0;
}

JNIEXPORT jint JNICALL Java_wang_a1ex_android_14over6_VpnDevices_startVpn(JNIEnv *env, jobject this) {

    jclass this_class = (*env)->GetObjectClass(env, this);
    jfieldID fidNumber = (*env)->GetFieldID(env, this_class,
                                            "vpnCallbacks",
                                            "Lwang/a1ex/android_4over6/VpnCallbacks;");

    if (NULL == fidNumber) do_debug("GetFieldID failed");
    jobject callbacks = (*env)->GetObjectField(env, this, fidNumber);
    jclass cls_callbacks = (*env)->FindClass(env, "wang/a1ex/android_4over6/VpnCallbacks");

    // get remote port
    fidNumber = (*env)->GetFieldID(env, this_class, "jcPort", "S");
    (*env)->DeleteLocalRef(env, this_class);
    if (fidNumber == NULL)
        do_debug("GetFieldID 2 failed");
    jshort jport = (*env)->GetShortField(env, this, fidNumber);

    do_debug("jport %d", jport);

    jint ret = 0;
    ret = startVpn(env, this, callbacks, cls_callbacks, jport);
    do_debug("startVpn() returns %d", ret);

    (*env)->DeleteLocalRef(env, callbacks);
    (*env)->DeleteLocalRef(env, cls_callbacks);
    return ret;
}