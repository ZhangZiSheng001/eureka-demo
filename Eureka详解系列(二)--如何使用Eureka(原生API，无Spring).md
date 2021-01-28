# 简介

通过上一篇博客 [Eureka详解系列(一)--先谈谈负载均衡器](https://www.cnblogs.com/ZhangZiSheng001/p/14313051.html) ，我们知道了 Eureka 是什么以及为什么要使用它，今天，我们开始研究如何使用 Eureka。

在此之前，先说明一点。网上几乎所有关于 Eureka 的文章都是基于 Spring 的，但**本文的例子不会有任何 Spring 的代码，我尽量使用 Eureka 原生的 API**，后面的源码分析也是如此。因为 Spring 把 Eureka 藏得越好，我们研究起来就会越困难，毕竟我写这个系列不是只为了学会怎么使用 Eureka，我们还要分析它的源码。当然，实际项目中就没必要这么搞了。

另外，这只是一个简单的例子，只配置了几个必备的参数，下篇博客会展开分析。

# 项目环境

os：win 10

jdk：1.8.0_231

eureka：1.10.11

tomcat：9.0.21

maven：3.6.3

# 使用例子

## 如何设计例子

通过 Eureka 的结构图可以知道，我们需要分别搭建 Eureka Server、Eureka Client for application service、Eureka Client for application client 三个模块。

<img src="https://img2020.cnblogs.com/blog/1731892/202101/1731892-20210128090308705-1562847497.png" alt="zzs_eureka_10" style="zoom:67%;" />

我参考了[官网的例子](https://github.com/Netflix/eureka/tree/master/eureka-examples)，并做了一点小改动，代码和配置就不一一列出了，具体源码见文末链接。

![zzs_eureka_13](https://img2020.cnblogs.com/blog/1731892/202101/1731892-20210128090350396-141344343.png)

1. eureka-server：作为 Eureka Server，它是一个独立的 Web 服务，能够处理 Eureka Client 的 rest 请求：注册、续约、注销服务，以及获取服务的信息。它需要打包成 war 包运行在 tomcat 上。
2. eureka-service：作为 application service，它需要向 Eureka Server 注册自己，并监听 Eureka Client 的消费请求。
3. eureka-client：作为 application client，它需要从 Eureka Server 获取 application service 的地址，然后访问 application service。

## 如何运行例子

我们需要按照顺序运行它们。

### eureka-server

eureka-server 使用 jersey 作 Web 框架（jersey 和 struts2、springMVC 作用差不多，没接触过也不碍事），项目最终要打包成 war 包运行在 tomcat 上。项目的运行方法如下：

1. 构建项目。使用`mvn clean package`将项目打包成 eureka.war。
2. 将 eureka.war 拷贝到 ${CATALINA_HOME}/webapps 目录下。
3. 启动 tomcat。通过 http://127.0.0.1:8080/eureka/ 可以访问成功，这个时候，我们就可以使用 Eureka Client 往上面注册服务了。另外，我们可以看到，Eureka Server 将自己注册了上去（30s 内）。

<img src="https://img2020.cnblogs.com/blog/1731892/202101/1731892-20210128090413344-624331497.png" alt="zzs_eureka_11" style="zoom:67%;" />

补充一点，官方提供了基于 java 实现的 Eureka Client 来与 Eureka Server 进行交互，其实，我们也可以直接使用 rest 请求，例如，通过 http://127.0.0.1:8080/eureka/v2/apps 可以获取所有的服务列表。当我们的客户端不支持 java 时，这些接口将非常有用，具体接口可以参考[官网](https://github.com/Netflix/eureka/wiki/Eureka-REST-operations)。在项目中，我们查找包含`javax.ws.rs.Path`注解的类，也可以找到这些接口。

### eureka-service

eureka-service 的实现比较简单，它先把自己注册到 Eureka Server，然后一直监听 application client 的消费，监听到后，只进行简单的交互后就直接关闭客户端。代码简化如下：

```java
// 创建ApplicationInfoManager对象（用来注册、注销当前实例）
ApplicationInfoManager applicationInfoManager = new ApplicationInfoManager(
    new MyDataCenterInstanceConfig(), new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get());
// 创建EurekaClient对象（用来获取其他服务以及提供内部入口用来注册、续约、和注销当前实例）
EurekaClient eurekaClient = new DiscoveryClient(applicationInfoManager, new DefaultEurekaClientConfig());
// 设置当前实例状态为STARTING
applicationInfoManager.setInstanceStatus(InstanceInfo.InstanceStatus.STARTING);
// 设置当前实例状态为UP----》这个时候会向Eureka Server注册自己
applicationInfoManager.setInstanceStatus(InstanceInfo.InstanceStatus.UP);
// 和application client交互
// ······
// 关闭客户端，同时也会注销当前实例
eurekaClient.shutdown();
```

操作方法很简单，只要运行`ExampleEurekaService.main`方法就行。当控制台出现"_Service started and ready to process requests.._"时，说明当前实例已经注册到 Eureka Server，并且准备被消费。

<img src="https://img2020.cnblogs.com/blog/1731892/202101/1731892-20210128090432088-306251751.png" alt="zzs_eureka_12" style="zoom:67%;" />

### eureka-client

eureka-client 项目的具体逻辑为：从 Eureka Server 上获取到注册表，然后和 application service 交互，得到响应后直接关闭客户端。代码简化如下：

```java
// 创建ApplicationInfoManager对象（用来注册、注销当前实例）
ApplicationInfoManager applicationInfoManager = new ApplicationInfoManager(
    new MyDataCenterInstanceConfig(), new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get());
// 创建EurekaClient对象（用来获取其他服务以及提供内部入口用来注册、续约、和注销当前实例）
EurekaClient eurekaClient = new DiscoveryClient(applicationInfoManager, new DefaultEurekaClientConfig());
// 获取指定服务的实例对象
InstanceInfo nextServerInfo = eurekaClient.getNextServerFromEureka(vipAddress, false);
// 和application service交互
// ······
// 关闭客户端
eurekaClient.shutdown();
```

直接运行`ExampleEurekaClient.main`就行。我们可以从控制台看到整个过程。

<img src="https://img2020.cnblogs.com/blog/1731892/202101/1731892-20210128090450915-700687721.png" alt="zzs_eureka_14" style="zoom:67%;" />

以上，通过一个简单的例子，我们实现了将服务注册到 Eureka Server 以及正常地消费它。这是非常“入门级”的例子，下篇博客我们再深入研究各种配置参数的作用。

最后，感谢阅读。

# 参考资料

https://github.com/Netflix/eureka/wiki/Eureka-at-a-glance

> 相关源码请移步：[https://github.com/ZhangZiSheng001/eureka-demo](https://github.com/ZhangZiSheng001/eureka-demo)

>本文为原创文章，转载请附上原文出处链接：[https://www.cnblogs.com/ZhangZiSheng001/p/14337985.html](https://www.cnblogs.com/ZhangZiSheng001/p/14337985.html) 
