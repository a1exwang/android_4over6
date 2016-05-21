

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
#include <android/log.h>

#define TAG "VpnDevices"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,TAG ,__VA_ARGS__)

int debug = 1;

typedef struct tIVIMessage {
    uint32_t length;
    char type;
    char data[4096];
} IVIMessage;
#define MSG_DHCP_REQUEST 100
#define MSG_DHCP_RESPOND 101
#define MSG_SEND_DATA 102
#define MSG_RECEIVE_DATA 103
#define MSG_HEARTBEAT 104

#define IVI_SERVER_IPV6_ADDRESS "2402:f000:1:4417::900"
#define IVI_SERVER_TCP_PORT 5678

static JNIEnv *s_env;
static jobject s_this;
static jobject s_callbacks;
static jclass s_cls_callbacks;

/**************************************************************************
 * do_debug: prints debugging stuff (doh!)                                *
 **************************************************************************/
void do_debug(char *msg, ...){

    va_list argp;
    char buf[5000] = { 0 };
    if(debug){
        va_start(argp, msg);
        vsprintf(buf, msg, argp);
        va_end(argp);
    }
    LOGD("%s", buf);
}

int get_tun_fd(int sock_fd) {
    IVIMessage dhcp_req;
    IVIMessage dhcp_res;
    memset(&dhcp_req, 0, sizeof(IVIMessage));
    memset(&dhcp_res, 0, sizeof(IVIMessage));

    dhcp_req.length = 5;
    dhcp_req.type = MSG_DHCP_REQUEST;

    struct timeval tv;

    tv.tv_sec = 20;  /* 30 Secs Timeout */
    tv.tv_usec = 0;  // Not init'ing this can cause strange errors
    setsockopt(sock_fd, SOL_SOCKET, SO_RCVTIMEO, (char *) &tv, sizeof(struct timeval));

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
            do_debug("before crash?");
            //((char *) &dhcp_res)[count] = 0;
            do_debug("dhcp string: %s", dhcp_str);
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
    jmethodID jmethod_create_tun = (*s_env)->GetMethodID(s_env,
                                                         s_cls_callbacks,
                                                         "onReceiveDhcpAndCreateTun",
                                                         "(Ljava/lang/String;)I");
    jstring jdhcp_str = (*s_env)->NewStringUTF(s_env, dhcp_str);
    jint jint_fd = (*s_env)->CallIntMethod(s_env, s_callbacks, jmethod_create_tun, jdhcp_str);
    (*s_env)->DeleteLocalRef(s_env, jdhcp_str);

    return jint_fd;
}

void on_statistics(int r_bytes, int r_packets, int s_bytes, int s_packets) {
    jmethodID method = (*s_env)->GetMethodID(s_env, s_cls_callbacks, "onStatistics", "(IIII)V");
    (*s_env)->CallVoidMethod(s_env, s_callbacks, method, r_bytes, r_packets, s_bytes, s_packets);
}

void on_heartbeat() {
    jmethodID method = (*s_env)->GetMethodID(s_env, s_cls_callbacks, "onHeartbeat", "()V");
    (*s_env)->CallVoidMethod(s_env, s_callbacks, method);
}

void on_received_packet(IVIMessage *message) {
    jint len = message->length;
    jbyte type = message->type;

    jbyteArray packet = (*s_env)->NewByteArray(s_env, message->length - sizeof(message->length) - sizeof(message->type));
    (*s_env)->SetByteArrayRegion(s_env, packet, 0, message->length - sizeof(message->length) - sizeof(message->type), (jbyte*)message->data);

    jmethodID method = (*s_env)->GetMethodID(s_env, s_cls_callbacks, "onPacketReceived", "(IB[B)V");
    (*s_env)->CallVoidMethod(s_env, s_callbacks, method, len, type, packet);
    (*s_env)->DeleteLocalRef(s_env, packet);
}

void on_sent_packet(IVIMessage *message) {
    jint len = message->length;
    jbyte type = message->type;

    jobjectArray packet = (*s_env)->NewByteArray(s_env, message->length - sizeof(message->length) - sizeof(message->type));
    (*s_env)->SetByteArrayRegion(s_env, packet, 0, message->length - sizeof(message->length) - sizeof(message->type), (jbyte*)message->data);

    jmethodID method = (*s_env)->GetMethodID(s_env, s_cls_callbacks, "onPacketSent", "(IB[B)V");
    (*s_env)->CallVoidMethod(s_env, s_callbacks, method, len, type, packet);
    (*s_env)->DeleteLocalRef(s_env, packet);
}

/**************************************************************************
 * cread: read routine that checks for errors and exits if an error is    *
 *        returned.                                                       *
 **************************************************************************/
int cread(int fd, char *buf, int n){

     int nread;

     if((nread=read(fd, buf, n))<0){
          perror("Reading data");
          exit(1);
     }
     return nread;
}

/**************************************************************************
 * cwrite: write routine that checks for errors and exits if an error is  *
 *         returned.                                                      *
 **************************************************************************/
int cwrite(int fd, char *buf, int n){

     int nwrite;

     if((nwrite=write(fd, buf, n))<0){
          perror("Writing data");
          exit(1);
     }
     return nwrite;
}

/**************************************************************************
 * read_n: ensures we read exactly n bytes, and puts those into "buf".    *
 *         (unless EOF, of course)                                        *
 **************************************************************************/
int read_n(int fd, char *buf, int n) {

     int nread, left = n;

     while(left > 0) {
          if ((nread = cread(fd, buf, left))==0){
               return 0 ;
          }else {
               left -= nread;
               buf += nread;
          }
     }
     return n;
}

int startVpn() {

    uint32_t nread, nwrite, plength;
    struct sockaddr_in6 remote;
    int sock_fd, net_fd, tun_fd, max_fd;
    int tun2net = 0, net2tun = 0, tun2net_bytes = 0, net2tun_bytes = 0;
    char ip_str[100] = { 0 };
    int ret;

    do_debug("startVpn start");
    if ((sock_fd = socket(AF_INET6, SOCK_STREAM, 0)) < 0) {
        perror("socket()");
        exit(1);
    }

    /* Client, try to connect to server */
    memset(&remote, 0, sizeof(remote));
    remote.sin6_family = AF_INET6;
    inet_pton(AF_INET6, IVI_SERVER_IPV6_ADDRESS, &remote.sin6_addr.s6_addr);
    remote.sin6_port = htons(IVI_SERVER_TCP_PORT);

    /* connection request */
    ret = connect(sock_fd, (struct sockaddr *) &remote, sizeof(remote));
    if (ret < 0) {
        do_debug("connect() returns %d, errno, %d", ret, errno);
        close(sock_fd);
        return -1;
    }
    net_fd = sock_fd;
    inet_ntop(AF_INET6, &remote.sin6_addr, ip_str, sizeof(ip_str));
    do_debug("CLIENT: Connected to server %s\n", ip_str);

    /* initialize tun/tap interface */
    tun_fd = get_tun_fd(sock_fd);
    if (tun_fd <= 0) {
        close(net_fd);
        return -1;
    }

    do_debug("Successfully established tun device, fd %d", tun_fd);

    /* use select() to handle two descriptors at once */
    max_fd = (tun_fd > net_fd) ? tun_fd : net_fd;

    do_debug("startVpn 4");
    while (1) {
        fd_set rd_set;

        FD_ZERO(&rd_set);
        FD_SET(tun_fd, &rd_set);
        FD_SET(net_fd, &rd_set);

        ret = select(max_fd + 1, &rd_set, NULL, NULL, NULL);

        if (ret < 0 && errno == EINTR) {
            continue;
        }

        if (ret < 0) {
            perror("select()");
            close(tun_fd);
            close(net_fd);
            return -1;
        }

        IVIMessage message;
        if (FD_ISSET(tun_fd, &rd_set)) {
            /* data from tun/tap: just read it and write it to the network */
            nread = (uint32_t) cread(tun_fd, message.data, 4096);
            message.type = MSG_SEND_DATA;
            message.length = nread + sizeof(message.type) + sizeof(message.length);

            tun2net++;
            tun2net_bytes += nread;
            do_debug("TUN2NET %d: Read %d bytes from the tun interface\n", tun2net, nread);

            /* write length + packet */
            on_sent_packet(&message);
            nwrite = (uint32_t) cwrite(net_fd, (char *) &message, message.length);
            //do_debug("TUN2NET %d: Written %d bytes to the network\n", tun2net, nwrite);
        }

        if (FD_ISSET(net_fd, &rd_set)) {
            /* data from the network: read it, and write it to the tun/tap interface.
             * We need to read the length first, and then the packet */

            /* Read length */
            nread = (uint32_t) read_n(net_fd, (char *) &message.length, sizeof(message.length));
            do_debug("NET2TUN %d: Read %d bytes from the network\n", net2tun, nread);
            if (nread == 0) {
                /* ctrl-c at the other end */
                break;
            }

            /* read packet */
            nread = (uint32_t) read_n(net_fd, &message.type, message.length - sizeof(message.length));
            if (message.length >= 5)
                on_received_packet(&message);

            switch (message.type) {
                case MSG_RECEIVE_DATA:
                    net2tun++;
                    net2tun_bytes += nread;
                    nwrite = (uint32_t) cwrite(tun_fd, (char*)message.data, message.length - sizeof(message.length) - sizeof(message.type));
                    //do_debug("NET2TUN %d: Written %d bytes to the tun interface\n", net2tun, nwrite);
                    break;
                case MSG_HEARTBEAT:
                    on_heartbeat();
                    cwrite(sock_fd, (char*)message.data, message.length);
                    do_debug("heartbeat received");
                    break;
                default:
                    do_debug("packet type unknown!");
            }
        }
        on_statistics(net2tun_bytes, net2tun, tun2net_bytes, tun2net);
    }
    do_debug("quit");
    close(tun_fd);
    close(net_fd);
    return 0;
}

JNIEXPORT jint JNICALL Java_wang_a1ex_android_14over6_VpnDevices_startVpn(JNIEnv *env, jobject object) {
    s_env = env;
    s_this = object;

    jclass this_class = (*s_env)->GetObjectClass(s_env, s_this);

    jfieldID fidNumber = (*s_env)->GetFieldID(s_env, this_class, "vpnCallbacks", "Lwang/a1ex/android_4over6/VpnCallbacks;");
    (*s_env)->DeleteLocalRef(s_env, this_class);

    if (NULL == fidNumber) do_debug("GetFieldID failed");
    s_callbacks = (*s_env)->GetObjectField(s_env, s_this, fidNumber);
    s_cls_callbacks = (*s_env)->FindClass(s_env, "wang/a1ex/android_4over6/VpnCallbacks");

    return startVpn();
}