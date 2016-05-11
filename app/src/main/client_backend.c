#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/socket.h>
#include <sys/fcntl.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <netdb.h>
#include <arpa/inet.h>
#include <dirent.h>
#include <unistd.h>
#include <linux/if.h>
#include <linux/if_tun.h>

#define MAX_DATA_SIZE 4096

#define FTP_SERVER_CONNECTION_PORT 21
#define FTP_CLIENT_CONNECTION_PORT 5678

typedef struct Msg {
    int length;
    char type;
    char data[MAX_DATA_SIZE];
} Msg_t;

int clientSocket;
int tmpRes;

int initConnection()
{
	struct sockaddr_in6 clientAddr;
	bzero(&clientAddr, sizeof(clientAddr));
	clientAddr.sin6_family = AF_INET6;
	clientAddr.sin6_port = htons(FTP_CLIENT_CONNECTION_PORT);
	inet_pton(AF_INET6, "1:1:1:1:1:1", &clientAddr.sin_addr);

	clientSocket = socket(AF_INET6, SOCK_STREAM, 0);
	if (clientSocket < 0) {
	    //TODO
	    return clientSocket;
	} else
	    //TODO

	tmpRes = bind(clientSocket, (struct sockaddr*) &clientAddr, sizeof(clientAddr));
	if (tmpRes) {
	    //TODO
	    return tmpRes;
	} else
	    //TODO

	struct sockaddr_in6 serverAddr;
	bzero(&serverAddr, sizeof(serverAddr));
	serverAddr.sin6_family = AF_INET6;
	serverAddr.sin6_port = htons(FTP_SERVER_CONNECTION_PORT);
	inet_pton(AF_INET6, "2402:f000:1:4417::900", &serverAddr.sin_addr);

	tmpRes = connect(clientSocket, (struct sockaddr*) &serverAddr, sizeof(serverAddr));
	if (tmpRes) {
	    //TODO
	    return tmpRes;
	} else
	    //TODO

	return 0;    
}

void proc_timer(

int handleMsg(Msg_t *msg)
{
    switch (msg->type) {
        case 101: {
            break;
        }
        case 103: {
            break;
        }
        case 104: {
            break;
        }
    }
}

void interactMain()
{
    Msg_t *msg;
    msg->length = sizeof(msg);
    msg->type = 100;
    memset(msg->data, 0, sizeof(msg->data));
    send(clientSocket, msg, sizeof(msg), 0);
    
    while (1) {
        memset(msg, 0, sizeof(msg));
        tmpRes = recv(clientSocket, msg, sizeof(msg), 0);
        if (tmpRes < 0) {
            //TODO
            break;
        }
        
        handleMsg(msg);
    }
}

int main()
{
    return 0;
}
