# 安卓4over6 VPN

1. 实现步骤
    - java端负责所有ui相关的操作
    - c负责处理所有通信方面的操作
    - c和java之间通过jni和socket通信
    - jni: 
        - 启动vpn时, java端创建一个线程, 在其中调用阻塞c函数startVpn, 将控制权转移到c
        - c建立和ipv6服务器的连接, 用jni回调java创建tun设备, 创建计时器, 再创建一个socket绑定在localhost, 负责接受java端的消息
        - 当c需要回调java端来更新ui时, 用jni调用直接调用java端, 而不是使用socket给java端发送数据, 这样的好处有
            - c和java之间的socket就可以做成单向的, 不需要考虑同步的问题
            - c直接jni调用java实现起来简单, 因为只是简单的函数调用
        - 当java需要通知c停止连接时, 用local socket, c接收到该消息后, 退出循环, 这个线程退出
 
2. 实现细节
    - 由于android的ui操作不是线程安全的, 所以android规定所有ui操作只能在主线程中进行, 所以这里在主线程中
    使用handler接受其他线程发送的更新ui消息, 并且更新ui, 其他线程不直接更新ui, 而是向该handler发送消息
    
    - c这一部分使用了单线程IO多路复用, 用select等待多个fd, 当有一个fd可写/可读时, select返回, 所以用linux系统调用
    将timer也做成fd就可以同时用select处理. 用select提高了效率, 而且是单线程不用考虑多线程同步的问题.
    
3. 遇到的问题和解决
    1. 用builder创建tun设备之后, ipv6 socket的log显示有很多错误的数据包.
        - 我们首先到手机连接的ap上用tcpdump抓包, 发现建立连接之后就没有发送任何数据了,
        但是log显示我们发送了数据, 这是为什么呢?
        - 说明我们写到socket的数据并没有发送出去, 我们首先应该检查路由表, iptables -L需要root权限, android模拟器可以拿到root权限,
        然而android模拟器不支持ipv6, 而且在手机上又没有root权限, 没办法检查路由表.
        - 我们adb shell连接到手机上, ping服务器的ipv6地址, 发现居然能ping通, 这说明路由表应该是好的.
        - 然后我们再次打开vpn(adb那边还在一直ping着), vpn连接建立之后, ping突然就不通了. 这说明就是vpn链接的建立导致了这个错误.
        - 由于ipv6socket建立成功了, 说明这时候ipv6连接还是好的. 所以只能是建立tun设备的时候影响了ipv6socket.
        - 我们检查了一下建立tun设备的builder, 发现路由我们已经设置0.0.0.0/32了.但是还是不知道怎么回事.
        - 我们在github上搜索了一下OpenVPN的源码
            ```
            219     public boolean socket_protect(int socket) {
            220			boolean b= mService.protect(socket);
            221			return b;
            222	
            223		}
            ```
          我们可以发现, OpenVPN在c中建立tun设备, 然后jni回调java, 用protect来"保护"socket,
          我们查阅了android的文档, 也说需要在socket上调用protect, 阻止该socket通过vpn
        - 加上protect就好了
    
    2. 发现ping和dns查询基本都能成功, 但是网页打不开, 微信偶尔能发出去.
        - 为了找出这是为什么, 研究了一下pcap数据文件的格式.
        - 在建立好tun设备之后, 将tun设备的所有流量转换成wireshark能识别的pcap格式, 保存到sd卡, 
        然后复制到电脑上用wireshark打开查看, 发现http请求一般是发到一半
        tcp连接就断开了, 并且发送了很多retransmission的包.
        - 不明所以
        - 到课上问老师, 说可能是用java建立socket的原因.
        - 后来改成了c建立socket, http请求就能成功了, 但是还是有点慢, 而且log显示socket发了很多不合法(length不等于包长)
        的数据包. 
        - 以为是服务器为了测试客户端鲁棒性故意发的
        - 后来课上跟老师讨论了mtu的问题, 将mtu设为800, 还是没有根本解决问题
        - 又改成了接收到不合法数据包后继续读数据, 还是没有彻底解决这个问题    
