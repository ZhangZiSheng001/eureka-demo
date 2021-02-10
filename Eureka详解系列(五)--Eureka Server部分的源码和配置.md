# 简介

按照原定的计划，我将分三个部分来分析 Eureka 的源码：

1. Eureka 的配置体系（已经写完，见[Eureka详解系列(三)--探索Eureka强大的配置体系](https://www.cnblogs.com/ZhangZiSheng001/p/14374005.html)）；
2. Eureka Client 的交互行为（已经写完，见[Eureka详解系列(四)--Eureka Client部分的源码和配置](https://www.cnblogs.com/ZhangZiSheng001/p/14381169.html) ）；
3. Eureka Server 的交互行为。

今天，我们来研究第三部分的源码。

分析的思路和第二部分的一样，先明确 Eureka Server 需要具备哪些功能，再从源码层面分析如何实现这些功能，最后补充 Eureka Server 的配置解读。

# 项目环境

os：win 10

jdk：1.8.0_231

eureka：1.10.11

tomcat：9.0.21

# Eureka Server 的功能

还是来回顾 Eureka 的整个交互过程。

<img src="https://img2020.cnblogs.com/blog/1731892/202102/1731892-20210210101139289-664280025.png" alt="zzs_eureka_21" style="zoom:80%;" />

首先，Eureka Server 需要和 Eureka Client 交互，所以它需要能够处理 Eureka Client 的各种请求，这些请求包括：

1. **获取注册表**（Application Client 的请求）；
2. **注册、续约、注销实例**（Application Service 的请求）；

除此之外，在集群中，它需要和对等节点交互，交互内容主要包括：

2. **将自己的注册表变更操作同步到其他节点**；
3. **处理其他节点同步注册表的请求**。

其实，一个完整的 Eureka Server 项目本身也包含了 Eureka Client 的部分，也就是说，它可以注册自己和消费包括自己在内的服务，可以在 eureka-client.properties 增加以下配置来关闭掉这两个部分的功能（不建议这么做）：

```properties
# 当前实例是否注册到Eureka Server。默认true
eureka.registration.enabled=false
# 当前实例是否需要从Eureka Server获取服务注册表
eureka.shouldFetchRegistry=false
```

# 如何实现这些功能

知道了 Eureka Server 需要具备哪些功能，接下来我们就从源码的角度来看看怎样实现这些功能。

和之前一样，我更多的会从设计的层面来分析，而不会顺序地去看每个过程的代码，即重设计、轻实现。

那么，还是从一个 UML 图开始吧。有了它，相信大家看源码时会更轻松一些。

<img src="https://img2020.cnblogs.com/blog/1731892/202102/1731892-20210210101214155-1270066021.png" alt="zzs_eureka_20" style="zoom:67%;" />

`AbstractInstanceRegistry`里放了一张注册表，用来存放所有的实例对象，通过它可以处理 Eureka Client 或者其他 Eureka Server 的请求，包括注册、续约、注销实例以及获取注册表等。

它的子类`PeerAwareInstanceRegistryImpl`提供了多节点的支持，这里以续约实例的方法为例，相同的操作还会被同步到其他节点（对等节点的请求除外）。

```java
    public boolean renew(final String appName, final String id, final boolean isReplication) {
        // 先调用父类AbstractInstanceRegistry的方法
        if (super.renew(appName, id, isReplication)) {
            // 再将操作同步到其他节点，最终是调用PeerEurekaNode的方法进行同步
            replicateToPeers(Action.Heartbeat, appName, id, null, null, isReplication);
            return true;
        }
        return false;
    }
```

除此之外，`PeerAwareInstanceRegistryImpl`还启动了三个定时任务：

1. **更新`PeerEurekaNode`列表**。例如，当我们使用 DNS 配合 serviceUrl 时，对等节点的地址可能会变化，所以需要及时更新。这个定时任务用于支持集群的故障转移和扩容。
2. **更新参数 numberOfRenewsPerMinThreshold--每分钟至少要有多少实例续约**。当每分钟续约实例少于这个值时（eureka 认为是灾难性的网络故障导致的），Eureka Server 将进入自我保护模式，此时，它不会再主动淘汰实例，直到我们主动关闭该模式，或者续约实例达到了阈值。我们一般可以通过以下参数来控制。而每分钟至少要有多少实例续约，这个数值受到实例总数的影响，所以需要定时更新。

```properties
# 期望实例多久续约一次
eureka.expectedClientRenewalIntervalSeconds=30
# 续约实例的阈值，未达到将开启自我保护模式
eureka.renewalPercentThreshold=0.85
# 是否启用保护模式
eureka.enableSelfPreservation=true
```

3. **丢弃未能及时续约的实例**。默认情况下，实例超过 90s 未能续约的话，Eureka Server 会将其丢弃掉。

# 从哪里开始看源码

Eureka Server 是作为一个 Web 应用运行的，要看源码比较难找到入口。打开[Eureka详解系列(二)--如何使用Eureka(原生API，无Spring)](https://www.cnblogs.com/ZhangZiSheng001/p/14337985.html) 例子里的 web.xml，可以看到配置了一个监听器，这个类就是 Eureka Server 初始化的入口。

```xml
  <listener>
    <listener-class>com.netflix.eureka.EurekaBootStrap</listener-class>
  </listener>
```

在这个类里面，我们主要关注这一段代码（代码有删减）。

```java
    protected void initEurekaServerContext() throws Exception {
        // 下面这一段是为了初始化Eureka Client所需要的对象，上一篇博客讲过了
        EurekaInstanceConfig instanceConfig = new MyDataCenterInstanceConfig();
        ApplicationInfoManager applicationInfoManager = new ApplicationInfoManager(
            instanceConfig, new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get());
        EurekaClientConfig eurekaClientConfig = new DefaultEurekaClientConfig();
        eurekaClient = new DiscoveryClient(applicationInfoManager, eurekaClientConfig);
		
        // 加载eureka-server.properties的配置
        EurekaServerConfig eurekaServerConfig = new DefaultEurekaServerConfig();
        ServerCodecs serverCodecs = new DefaultServerCodecs(eurekaServerConfig);
        // 初始化注册表对象（支持多节点）
        PeerAwareInstanceRegistry registry = new PeerAwareInstanceRegistryImpl(
            eurekaServerConfig,
            eurekaClient.getEurekaClientConfig(),
            serverCodecs,
            eurekaClient
        );
		// 初始化PeerEurekaNodes对象
        PeerEurekaNodes peerEurekaNodes = getPeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClient.getEurekaClientConfig(),
                serverCodecs,
                applicationInfoManager
        );
        // 1. 初始化PeerEurekaNode列表，
        // 2. 启动定时任务：更新PeerEurekaNode列表
        peerEurekaNodes.start();
        
		// 1. 将PeerEurekaNode列表的指针给到PeerEurekaNodes对象对象
        // 2. 启动定时任务：更新参数numberOfRenewsPerMinThreshold--每分钟至少要有多少实例续约，它是判断是否开启自我保护模式的依据
        registry.init(peerEurekaNodes);


        // 从其他节点获取实例列表并注册到本地的注册表
        int registryCount = registry.syncUp();
        // 1. 初始化参数numberOfRenewsPerMinThreshold--每分钟要求多少实例续约
        // 2. 开启定时任务：淘汰未能正常续约的实例
        registry.openForTraffic(applicationInfoManager, registryCount);
    }
```

完成初始化后，Eureka Server 就可以处理 Eureka Client 的请求了。因为 Eureka Server 使用 jersey 作 Web 框架（jersey 和 struts2、springMVC 作用差不多，没接触过也不碍事），所以，只要找到添加了`javax.ws.rs.Path`注解的类，就能找到这部分代码的入口。

# Eureka Server 的配置解读

回顾下[Eureka详解系列(三)--探索Eureka强大的配置体系](https://www.cnblogs.com/ZhangZiSheng001/p/14374005.html)的内容，在 Eureka 里，配置分成了三种：

1. **EurekaInstanceConfig**：当前实例身份的配置信息，即**我是谁？**
2. **EurekaServerConfig**：一些影响当前Eureka Server和客户端或对等节点交互行为的配置信息，即**怎么交互？**
3. **EurekaClientConfig**：一些影响当前实例和Eureka Server交互行为的配置信息，即**和谁交互？怎么交互？**

 这里我们来讲讲`EurekaServerConfig`的配置参数，对应的是 eureka-server.properties 里的配置。

```properties
# 期望实例多久续约一次
eureka.expectedClientRenewalIntervalSeconds=30
# 续约实例的阈值，未达到将开启自我保护模式
eureka.renewalPercentThreshold=0.85
# 是否启用保护模式
eureka.enableSelfPreservation=true

# 更新参数numberOfRenewsPerMinThreshold的定时任务多久执行一次
renewalThresholdUpdateIntervalM=900000
# 更新PeerEurekaNode列表的定时任务多久执行一次
peerEurekaNodesUpdateIntervalMs=600000
# 淘汰未能正常续约实例的定时任务多久执行一次
evictionIntervalTimerInMs=60000

# 这几个一般不用，我就不展开了。有需要的话可以
#awsAccessId=
#awsSecretKey=
eipBindRebindRetries=3
eipBindRebindRetryIntervalMsWhenUnbound=60000
eipBindRebindRetryIntervalMs=300000
waitTimeInMsWhenSyncEmpty=300000
shouldBatchReplication=false
disableDelta=false
numberRegistrySyncRetries=5
registrySyncRetryWaitMs=30000
enableReplicatedRequestCompression=false
minAvailableInstancesForPeerReplication=-1
peerEurekaStatusRefreshTimeIntervalMs=30000
peerNodeConnectTimeoutMs=1000
peerNodeReadTimeoutMs=5000
peerNodeTotalConnections=1000
peerNodeTotalConnectionsPerHost=500
numberOfReplicationRetries=5
maxElementsInPeerReplicationPool=10000
maxIdleThreadAgeInMinutesForPeerReplication=15
minThreadsForPeerReplication=5
maxThreadsForPeerReplication=20
maxTimeForReplication=30000
primeAwsReplicaConnections=true
maxIdleThreadAgeInMinutesForStatusReplication=10
minThreadsForStatusReplication=1
maxThreadsForStatusReplication=1
maxElementsInStatusReplicationPool=10000
disableDeltaForRemoteRegions=false
remoteRegionConnectTimeoutMs=2000
remoteRegionReadTimeoutMs=5000
remoteRegionTotalConnections=1000
remoteRegionTotalConnectionsPerHost=500
remoteRegionConnectionIdleTimeoutSeconds=30
remoteRegion.gzipContent=true
#remoteRegionUrlsWithName=
#remoteRegion.appWhiteList=
remoteRegion.registryFetchIntervalInSeconds=30
remoteRegion.fetchThreadPoolSize=20
#remoteRegion.trustStoreFileName=
remoteRegion.trustStorePassword=changeit
remoteRegion.disable.transparent.fallback=false
shouldUseAwsAsgApi=true
asgQueryTimeoutMs=300
asgUpdateIntervalMs=300000
asgCacheExpiryTimeoutMs=600000
retentionTimeInMSInDeltaQueue=180000
deltaRetentionTimerIntervalInMs=30000
responseCacheAutoExpirationInSeconds=180
responseCacheUpdateIntervalMs=30000
shouldUseReadOnlyResponseCache=true
syncWhenTimestampDiffers=true
auth.shouldLogIdentityHeaders=true
route53BindRebindRetries=3
route53BindRebindRetryIntervalMs=300000
route53DomainTTL=30
initialCapacityOfResponseCache=1000
jsonCodecName=com.netflix.discovery.converters.wrappers.CodecWrappers.LegacyJacksonJson
xmlCodecName=com.netflix.discovery.converters.wrappers.CodecWrappers.XStreamXml
```

以上比较宏观地讲完了 Eureka Server 的源码和配置，具体的细节欢迎私信交流。

最后，感谢您的阅读。 

# 参考资料

https://github.com/Netflix/eureka/wiki/Eureka-at-a-glance

> 相关源码请移步：[https://github.com/ZhangZiSheng001/eureka-demo](https://github.com/ZhangZiSheng001/eureka-demo)

>本文为原创文章，转载请附上原文出处链接：[https://www.cnblogs.com/ZhangZiSheng001/p/14395079.html](https://www.cnblogs.com/ZhangZiSheng001/p/14395079.html) 
