# 简介

按照原定的计划，我将分三个部分来分析 Eureka 的源码：

1. Eureka 的配置体系（已经写完，见[Eureka详解系列(三)--探索Eureka强大的配置体系](https://www.cnblogs.com/ZhangZiSheng001/p/14374005.html)）；
2. Eureka Client 的交互行为；
3. Eureka Server 的交互行为。

今天，我们来研究第二部分的源码。

我的思路是这样子的：先明确 Eureka Client 拥有哪些功能，然后从源码角度分析如何实现，最后，我会补充 Eureka Client 的配置解读。

# Eureka Client的功能

首先来回顾下 Eureka 的整个交互过程。

<img src="https://img2020.cnblogs.com/blog/1731892/202102/1731892-20210206123628785-1816994910.png" alt="zzs_eureka_19" style="zoom:67%;" />

从用户的角度来讲，Eureka Client 要能够向 Eureka Server 注册当前实例以及获取注册表。

至于其他的功能，我们需要再思考下。

当我们把当前实例注册到了 Eureka Server 后，并非一劳永逸，如果当前实例故障了，Eureka Server 需要及时将它从注册表中剔除，那么，Eureka Server 怎么知道哪些实例故障了呢？做法比较简单，Application Service 需要定期向 Eureka Server 报告自己的健康状态，如果一直不报告，就认为是故障了。

考虑到性能和可靠性，Application Client 本地会缓存一份服务注册表，并不需要每次用到就从 Eureka Server 重新获取。但是，Application Service “来来去去”，Eureka Server 的注册表并非一成不变，所以，Application Client 还需要定期同步注册表。

最后还有一点，我们注册到 Eureka Server 的实例信息，除了实例 IP、端口、服务名等，还有实例 id、附带的元数据等，这些是可更改的，Application Service 需要及时地将这些更改同步到 Eureka Server。

通过上面的分析，我们知道**一个 Eureka Client 需要具备以下功能**：

1. **注册当前实例到 Eureka Server**；
2. **获取 Eureka Server 的服务注册表**；
3. **定期向 Eureka Server 发送心跳**；
4. **定期向 Eureka Server 同步当前实例信息**；
5. **定期刷新本地服务注册表**

# 如何实现这些功能

知道了 Eureka Client 需要具备哪些功能，接下来我们就从源码的角度来看看怎样实现这些功能。

和之前一样，我更多的会从设计的层面来分析，而不会顺序地去看每个过程的代码，即重设计、轻实现。如果对源码细节有疑问的，可以交流学习下。

那么，还是从一个 UML 图开始吧。有了它，相信大家看源码时会更轻松一些。

<img src="https://img2020.cnblogs.com/blog/1731892/202102/1731892-20210206123655883-186137267.png" alt="zzs_eureka_09" style="zoom:67%;" />

通过这个图，我们再来看 Eureka Client 的几个功能：

1. 注册当前实例到 Eureka Server；--初始化`DiscoveryClient`时就会注册上去。
2. 获取 Eureka Server 的服务注册表；--通过`DiscoveryClient`获取。
3. 定期向 Eureka Server 发送心跳；--通过`HeartbeatThread`任务实现。
4. 定期向 Eureka Server 同步当前实例信息；--通过`InstanceInfoReplicator`任务实现。
5. 定期刷新本地服务注册表；--通过`CacheRefreshThread`任务实现。

我们拿[Eureka详解系列(二)--如何使用Eureka(原生API，无Spring)](https://www.cnblogs.com/ZhangZiSheng001/p/14337985.html) 中的例子来分析下整个过程。

```java
// 创建ApplicationInfoManager对象
ApplicationInfoManager applicationInfoManager = new ApplicationInfoManager(
    new MyDataCenterInstanceConfig(), new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get());
// 创建EurekaClient对象，这个时候完成了几件事：
// 1. 注册当前实例到Eureka Server（实例的初始状态一般是STARTING）；
// 2. 开启心跳、刷缓存、同步实例信息的定时任务；
// 3. 注册状态监听器到ApplicationInfoManager（不然后面的setInstanceStatus不会生效的）
EurekaClient eurekaClient = new DiscoveryClient(applicationInfoManager, new DefaultEurekaClientConfig());
// 设置当前实例状态为STARTING（原状态也是STARTING，所以这一句没什么用）
applicationInfoManager.setInstanceStatus(InstanceInfo.InstanceStatus.STARTING);
// 设置当前实例状态为UP触发（监听器触发，执行InstanceInfoReplicator的任务）
applicationInfoManager.setInstanceStatus(InstanceInfo.InstanceStatus.UP);
// 和application client交互
// ······
// 关闭客户端，同时也会注销当前实例
eurekaClient.shutdown();
```

我们会发现，`DiscoveryClient`初始化化时做了非常多的事情，核心的源码都在它的构造方法里，大家感兴趣的可以自行阅读。

这里提醒下，Eureka 的定时任务有点奇怪，它不是完全交给`ScheduledExecutorService`来调度，举个例子，`ScheduledExecutorService`只会按设定的延迟执行一次心跳任务，然后就不执行了，之所以能够实现定时调度，是因为心跳任务里又提交了一次任务，代码如下：

```java
    public void run() {
        try {
            // ······
        } finally {
            // ······
            if (!scheduler.isShutdown()) {
                scheduler.schedule(this, delay.get(), TimeUnit.MILLISECONDS);
            }
        }
    }
```

# Eureka Client的配置详解

回顾下[Eureka详解系列(三)--探索Eureka强大的配置体系](https://www.cnblogs.com/ZhangZiSheng001/p/14374005.html)的内容，在 Eureka 里，配置分成了三种：

1. **EurekaInstanceConfig**：当前实例身份的配置信息，即**我是谁？**
2. **EurekaServerConfig**：一些影响当前Eureka Server和客户端或对等节点交互行为的配置信息，即**怎么交互？**
3. **EurekaClientConfig**：一些影响当前实例和Eureka Server交互行为的配置信息，即**和谁交互？怎么交互？**

<img src="https://img2020.cnblogs.com/blog/1731892/202102/1731892-20210206123718286-509241166.png" alt="zzs_eureka_18" style="zoom:67%;" />

这里我们来讲讲`EurekaInstanceConfig`和`EurekaClientConfig`的配置参数。

## EurekaInstanceConfig--我是谁？

这些参数大部分用来向 Eureka Server 表明当前实例的身份，但我们会发现，这里混进了两个“异类”--lease.renewalInterval 和 lease.duration，这个不应该放在`EurekaClientConfig`里吗？

我一开始也不明白，后来发现很重要的一点，**`EurekaClientConfig`的参数只能影响当前实例，而不能影响 Eureka Server**，它的信息不能向 Eureka Server 传递，而`EurekaInstanceConfig`的就可以，所以，除了表明实例的身份，`EurekaInstanceConfig`还有另外一个功能，就是向 Eureka Server 传递某些重要的交互参数。

```properties
# 同一个服务下存在多个实例，这个可以作为唯一标识区分它们。默认为当前实例的主机名
eureka.instanceId=zzs

# 服务名。默认unknown
eureka.name=SampleService

# 当前实例开放服务的端口，默认80
eureka.port=8001

# 当前实例多久向Eureka Server发送一次心跳，单位秒。默认30s
eureka.lease.renewalInterval=30
# 如果没收到心跳，Eureka Server隔多久将当前实例剔除，单位秒。默认90s
eureka.lease.duration=90

# 当前实例的虚拟主机名，通过这个可以直接访问到当前实例。默认：当前主机名+port
eureka.vipAddress=sampleservice.zzs.cn

# 绑定在当前实例的一些自定义信息，它们会被放在一个map里，其他Eureka Client可以拿来用。默认是一个空map
eureka.metadata.name=zzs
eureka.metadata.age=18

# 这几个一般不用，我就不展开了
eureka.appGroup=unknown
#eureka.asgName=
eureka.traffic.enabled=false
eureka.port.enabled=true
eureka.securePort=443
eureka.securePort.enabled=false
eureka.secureVipAddress=zzs:443
eureka.statusPageUrlPath=/Status
eureka.statusPageUrl=http://zzs:8001/Status
eureka.homePageUrlPath=/
eureka.homePageUr=http://zzs:8001/
eureka.healthCheckUrlPath=/healthcheck
eureka.healthCheckUrl=http://zzs:8001/healthcheck
eureka.secureHealthCheckUrl=https://zzs:443/healthcheck
```

## EurekaClientConfig--和谁交互？怎么交互？

关于 Eureka Server 集群的配置，有三种方法：

1. 在 serviceUrl 中写死 Eureka Server 的 IP，缺点就是每次增加、删除、更改机器都要更改配置；
2. 在 serviceUrl 中配置 Eureka Server 对应的 EIP，更改机器时不需要更改，但是增加、删除机器都要更改配置；
3. **采用 DNS 配置 Eureka Server 的 IP**，增加、删除、更改机器都不需要更改配置。

这里还涉及到 region、zone 的概念，可以理解为：region 表示机器部署在不同的城市，zone 表示机器部署在同一个城市的不同机房里。默认情况下，Eureka Client 会优先选择自己所属 region 的 Eureka Server 来访问。

```properties
# 当前实例多久同步一次本地注册表，单位秒。默认30s
eureka.client.refresh.interval=30
# 当前实例多久同步一次实例信息，单位秒。默认30s
eureka.appinfo.replicate.interval=30

# 当前实例是否注册到Eureka Server。默认true
eureka.registration.enabled=true
# 当前实例是否需要从Eureka Server获取服务注册表
eureka.shouldFetchRegistry=true

# 当前实例可以和哪些region的Eureka Server交互
eureka.fetchRemoteRegionsRegistry=beijing,shanghai
# 当前实例所在的region
eureka.region=beijing
# region下有哪些zone
eureka.beijing.availabilityZones=zone-1,zone-2
eureka.shanghai.availabilityZones=zone-3
# zone下有哪些Eureka Server（这种配置可以通过EIP来避免写死IP，但扩展时还是要改，推荐使用DNS的方式）
eureka.serviceUrl.zone-1=http://ec2-552-627-568-165.compute-1.amazonaws.com:7001/eureka/v2/,http://ec2-368-101-182-134.compute-1.amazonaws.com:7001/eureka/v2/
eureka.serviceUrl.zone-2=http://ec2-552-627-568-170.compute-1.amazonaws.com:7001/eureka/v2/
eureka.serviceUrl.zone-3=http://ec2-500-179-285-592.compute-1.amazonaws.com:7001/eureka/v2/

# 当我们使用DNS配置serviceUrl时需要用到的配置（非常推荐使用，可以避免写死IP，且方便扩展）
eureka.shouldUseDns=true
eureka.eurekaServer.domainName=sampleservice.zzs.cn
eureka.eurekaServer.port=8001
eureka.eurekaServer.context=eureka/v2

# 这几个一般不用，我就不展开了
eureka.preferSameZone=true
eureka.appinfo.initial.replicate.time=40
eureka.serviceUrlPollIntervalMs=300
eureka.client.heartbeat.threadPoolSize=5
eureka.client.heartbeat.exponentialBackOffBound=10
eureka.client.cacheRefresh.threadPoolSize=5
eureka.client.cacheRefresh.exponentialBackOffBound=10
#eureka.eurekaServer.proxyHost=
#eureka.eurekaServer.proxyPort=
#eureka.eurekaServer.proxyUserName=
#eureka.eurekaServer.proxyPassword=
eureka.eurekaServer.gzipContent=true
eureka.eurekaServer.readTimeout=8
eureka.eurekaServer.connectTimeout=5
eureka.eurekaServer.maxTotalConnections=200
eureka.eurekaServer.maxConnectionsPerHost=50
eureka.eurekaserver.connectionIdleTimeoutInSeconds=45
#eureka.backupregistry=
eureka.shouldEnforceRegistrationAtInit=false
eureka.shouldEnforceFetchRegistryAtInit=false
eureka.shouldUnregisterOnShutdown=true
eureka.shouldFilterOnlyUpInstances=true
eureka.shouldOnDemandUpdateStatusChange=true
eureka.allowRedirects=true
eureka.printDeltaFullDiff=true
eureka.disableDelta=false
eureka.registryRefreshSingleVipAddress=false
eureka.dollarReplacement=_-
eureka.escapeCharReplacement=__
#eureka.encoderName=
#eureka.decoderName=
eureka.clientDataAccept=full
eureka.experimental.clientTransportFailFastOnInit=true
```

以上比较宏观地讲完了 Eureka Client 的源码和配置，感谢您的阅读。

# 参考资料

https://github.com/Netflix/eureka/wiki/Eureka-at-a-glance

> 相关源码请移步：[https://github.com/ZhangZiSheng001/eureka-demo](https://github.com/ZhangZiSheng001/eureka-demo)

>本文为原创文章，转载请附上原文出处链接：[https://www.cnblogs.com/ZhangZiSheng001/p/14381169.html](https://www.cnblogs.com/ZhangZiSheng001/p/14381169.html) 
