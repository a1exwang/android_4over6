

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
#include "wang_a1ex_android_4over6_VpnDevices_VpnCallbacks.h"

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

/* buffer for reading from tun/tap interface, must be >= 1500 */
#define BUFSIZE 2000
#define CLIENT 0
#define SERVER 1
#define PORT 55555

/* some common lengths */
#define IP_HDR_LEN 20
#define ETH_HDR_LEN 14
#define ARP_PKT_LEN 28

int debug = 1;

typedef struct tIVIMessage {
    uint32_t length;
    char type;
    char data[4096];
};
#define MSG_DHCP_REQUEST 100
#define MSG_DHCP_RESPOND 101
#define MSG_SEND_DATA 102
#define MSG_RECEIVE_DATA 103
#define MSG_HEARTBEAT 104

#define IVI_SERVER_IPV6_ADDRESS ""
#define IVI_SERVER_TCP_PORT 5678

static JNIEnv *s_env;
static jobject s_this;

/**************************************************************************
 * tun_alloc: allocates or reconnects to a tun/tap device. The caller     *
 *            needs to reserve enough space in *dev.                      *
 **************************************************************************/
int get_tun_fd(int sock_fd) {
    return 0;
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


void startVpn() {

     int nread, nwrite, plength;
     struct sockaddr_in6 local, remote;
     unsigned short int port = PORT;
     int sock_fd, net_fd,tun_fd, max_fd;
     socklen_t remotelen;
     int cliserv = -1;    /* must be specified on cmd line */
     unsigned long int tap2net = 0, net2tap = 0;

     progname = argv[0];

     /* Check command line options */
     while((option = getopt(argc, argv, "i:sc:p:uahd")) > 0){
          switch(option) {
               case 'd':
                    debug = 1;
                  break;
               case 'h':
                    usage();
                  break;
               case 'i':
                    strncpy(if_name,optarg,IFNAMSIZ-1);
                  break;
               case 's':
                    cliserv = SERVER;
                  break;
               case 'c':
                    cliserv = CLIENT;
                  strncpy(remote_ip,optarg,15);
                  break;
               case 'p':
                    port = atoi(optarg);
                  break;
               case 'u':
                    flags = IFF_TUN;
                  break;
               case 'a':
                    flags = IFF_TAP;
                  header_len = ETH_HDR_LEN;
                  break;
               default:
                    my_err("Unknown option %c\n", option);
                  usage();
          }
     }

     argv += optind;
     argc -= optind;

     if(argc > 0){
          my_err("Too many options!\n");
          usage();
     }

     if(*if_name == '\0'){
          my_err("Must specify interface name!\n");
          usage();
     }else if(cliserv < 0){
          my_err("Must specify client or server mode!\n");
          usage();
     }else if((cliserv == CLIENT)&&(*remote_ip == '\0')){
          my_err("Must specify server address!\n");
          usage();
     }

     /* initialize tun/tap interface */
     if ((tun_fd = get_tun_fd(if_name, flags | IFF_NO_PI)) < 0 ) {
          my_err("Error connecting to tun/tap interface %s!\n", if_name);
          exit(1);
     }

     do_debug("Successfully connected to interface %s\n", if_name);

     if ( (sock_fd = socket(AF_INET, SOCK_STREAM, 0)) < 0) {
          perror("socket()");
          exit(1);
     }

     if(cliserv==CLIENT){
          /* Client, try to connect to server */

          /* assign the destination address */
          memset(&remote, 0, sizeof(remote));
          remote.sin_family = AF_INET;
          remote.sin_addr.s_addr = inet_addr(remote_ip);
          remote.sin_port = htons(port);

          /* connection request */
          if (connect(sock_fd, (struct sockaddr*) &remote, sizeof(remote)) < 0){
               perror("connect()");
               exit(1);
          }

          net_fd = sock_fd;
          do_debug("CLIENT: Connected to server %s\n", inet_ntoa(remote.sin_addr));

     } else {
          /* Server, wait for connections */

          /* avoid EADDRINUSE error on bind() */
          if(setsockopt(sock_fd, SOL_SOCKET, SO_REUSEADDR, (char *)&optval, sizeof(optval)) < 0){
               perror("setsockopt()");
               exit(1);
          }

          memset(&local, 0, sizeof(local));
          local.sin_family = AF_INET;
          local.sin_addr.s_addr = htonl(INADDR_ANY);
          local.sin_port = htons(port);
          if (bind(sock_fd, (struct sockaddr*) &local, sizeof(local)) < 0){
               perror("bind()");
               exit(1);
          }

          if (listen(sock_fd, 5) < 0){
               perror("listen()");
               exit(1);
          }

          /* wait for connection request */
          remotelen = sizeof(remote);
          memset(&remote, 0, remotelen);
          if ((net_fd = accept(sock_fd, (struct sockaddr*)&remote, &remotelen)) < 0){
               perror("accept()");
               exit(1);
          }

          do_debug("SERVER: Client connected from %s\n", inet_ntoa(remote.sin_addr));
     }

     /* use select() to handle two descriptors at once */
     max_fd = (tun_fd > net_fd) ? tun_fd : net_fd;

     while(1) {
          int ret;
          fd_set rd_set;

          FD_ZERO(&rd_set);
          FD_SET(tun_fd, &rd_set); FD_SET(net_fd, &rd_set);

          ret = select(max_fd + 1, &rd_set, NULL, NULL, NULL);

          if (ret < 0 && errno == EINTR){
               continue;
          }

          if (ret < 0) {
               perror("select()");
               exit(1);
          }

          if(FD_ISSET(tun_fd, &rd_set)){
               /* data from tun/tap: just read it and write it to the network */

               nread = cread(tun_fd, buffer, BUFSIZE);

               tap2net++;
               do_debug("TAP2NET %lu: Read %d bytes from the tap interface\n", tap2net, nread);

               /* write length + packet */
               plength = htons(nread);
               nwrite = cwrite(net_fd, (char *)&plength, sizeof(plength));
               nwrite = cwrite(net_fd, buffer, nread);

               do_debug("TAP2NET %lu: Written %d bytes to the network\n", tap2net, nwrite);
          }

          if(FD_ISSET(net_fd, &rd_set)){
               /* data from the network: read it, and write it to the tun/tap interface.
                * We need to read the length first, and then the packet */

               /* Read length */
               nread = read_n(net_fd, (char *)&plength, sizeof(plength));
               if(nread == 0) {
                    /* ctrl-c at the other end */
                    break;
               }

               net2tap++;

               /* read packet */
               nread = read_n(net_fd, buffer, ntohs(plength));
               do_debug("NET2TAP %lu: Read %d bytes from the network\n", net2tap, nread);

               /* now buffer[] contains a full packet or frame, write it into the tun/tap interface */
               nwrite = cwrite(tun_fd, buffer, nread);
               do_debug("NET2TAP %lu: Written %d bytes to the tap interface\n", net2tap, nwrite);
          }
     }

     return(0);
}


JNIEXPORT void JNICALL Java_wang_a1ex_android_4over6_VpnDevices_startVpn(JNIEnv *env, jobject object) {
    s_env = env;
    s_object = object;
    startVpn();
}