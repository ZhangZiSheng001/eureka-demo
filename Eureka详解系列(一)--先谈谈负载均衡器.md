这个系列开始研究 Eureka，在此之前，先来谈谈负载均衡器。

本质上，Eureka 就是一个负载均衡器，可能有的人会说，它是一个服务注册中心，用来注册服务的，这种说法不能说错，只是有点片面。

在这篇博客里，我将尽可能循序渐进、图文并茂地回答下面的几个问题。至于 Eureka 的使用、配置、源码分析、集群配置等等，这些后续博客再补充。

1. 为什么要用负载均衡器？
2. 一个合格的负载均衡器是怎样的？
2. mid-tier services 的负载均衡器？
3. 为什么使用 Eureka？

# 为什么要用负载均衡器

那么，先从一个例子开始。

假设我有一个网上商城的项目，在项目初期，它是一个传统的单体应用，没有集群，没有微服务。显然，这个时候我不需要考虑所谓的负载均衡。

<img src="https://img2020.cnblogs.com/blog/1731892/202101/1731892-20210122141348749-1508081663.png" alt="zzs_eureka_01" style="zoom:67%;" />

随着应用的推广，我的用户越来越多，当达到流量高峰时，服务器经常会扛不住。这样下去可不行，于是，我试着加了两台机器。现在，有了三台服务器，我不就能处理原来三倍的流量了吗？

想法是挺好的，但这需要一个前提：**要让请求平摊到多台服务器上面，即实现简单的负载均衡**。

那么，要怎么做才能将请求平摊到三台服务器呢？我尝试在 DNS 服务器加上两台新服务器的地址，不出所料，真的可以这么做。因为 DNS 会基于轮循负载算法返回地址，只要用户拿到每个地址的机会是均衡的，请求到我服务器也会是均衡的。于是，我的目的间接达到了。

<img src="https://img2020.cnblogs.com/blog/1731892/202101/1731892-20210122141413332-717818977.png" alt="zzs_eureka_02" style="zoom:67%;" />

# 一个合格的负载均衡器是怎样的

上面的方案看起来挺好的，我自己不需要增加多余的机器，就轻易实现了负载均衡。但是，我还是遇到了问题。

有一天，用户访问商城服务出现大量报错，原因是第三台服务器的服务突然挂掉了，但是 1/3 的请求还是落到这一台。因为一时排查不出问题的根源，而且重启没多久又会马上挂掉，我试着把这台服务器的地址从 DNS 上剔除出来。然而，另一个问题出现了，DNS 的更新并没有生效，我剔除了故障机器的地址，请求还是会落到这一台······

经历了这一回，我总算明白，单纯使用 DNS 做负载均衡还是不靠谱。**一个合格的负载均衡器至少要做到：当部分服务出现故障时，自动将其屏蔽，当服务恢复后，再将屏蔽放开**。显然，DNS 不能很好地满足。经过研究，我发现了 nginx、SLB、ALB 等等负载均衡器，它们都可以做到这一点。最后，我选择了 SLB 作为负载均衡器。

<img src="https://img2020.cnblogs.com/blog/1731892/202101/1731892-20210122141431092-1003994673.png" alt="zzs_eureka_03" style="zoom:67%;" />

SLB 配置上要比传统的 nginx 简单很多，只要配置好监听就行。SLB 会检查后端服务器的健康状态，当后端某台服务器出现异常时，SLB 会自动将它隔离。 除此之外，它还有很多其他功能，这里就不再扩展了。

有人可能会问，既然要用负载均衡器，为什么不用 Eureka？其实，还真的不能用，原因后面会说到。我希望能够说明一点，一个工具再怎么优秀，它也有不适用的场合。

# mid-tier services的负载均衡器

这里涉及到两个名词，有必要解释一下：

1. edge services：向终端用户开放的服务。例如，用户通过浏览器直接访问到的接口都属于 edge services。
2. mid-tier services：向其他后端服务开放的服务。有时，我们会说这种服务的调用是内部调用。

还是接着上面的例子。

我的商城业务变得越来越复杂，用户规模也越来越大，传统单体应用的缺点开始暴露出来：开发维护难以及数据库瓶颈。于是，我重构了整个项目，做法比较简单。显然，我开始搞微服务了。

<img src="https://img2020.cnblogs.com/blog/1731892/202101/1731892-20210122141506373-474906774.png" alt="zzs_eureka_04" style="zoom:67%;" />

但是，不管我怎么拆分，后端服务都不能做到完全独立，例如，处理订单业务时需要查询客户信息，促销活动有时也需要查询客户信息。这个时候，每个服务都需要开放出对应的 mid-tier services 供其他后端服务调用，另外，为了安全以及方便管理，这部分的服务需要和 edge services  区分开（不在同一个应用）。

<img src="https://img2020.cnblogs.com/blog/1731892/202101/1731892-20210122141513526-1748942478.png" alt="zzs_eureka_05" style="zoom:67%;" />

这个时候，我需要一个针对 mid-tier services 的负载均衡器。

## 使用SLB做负载均衡器

理所当然地，我首先想到的还是 SLB。我可以再加一台 SLB，用于后端服务器请求 mid-tier services。当然，mid-tier services 之间也可以相互调用，只是图中不好表示出来。

<img src="https://img2020.cnblogs.com/blog/1731892/202101/1731892-20210122141551635-1487707615.png" alt="zzs_eureka_06" style="zoom:67%;" />

## 使用Eureka做负载均衡器

后来，我发现了一个更好的方案，那就是 Eureka。和 SLB 不同，**Eureka 是专门针对 mid-tier services 的负载均衡器**。它主要包含三个部分：

1. Eureka Server：存放服务名和服务对应地址的映射表，这就是我们常说的服务注册中心。开篇的时候我说过，“Eureka 是一个服务注册中心”这种说法是片面的，这里就能知道原因了吧。
2. Eureka Client for application service：服务提供方，向 Eureka Server 注册自己的地址。例如，mid-tier services 所在应用就属于这一类。
3. Eureka Client for application client：服务消费方，从 Eureka Server 获取 application service 的地址，并消费对应的服务，它包含内置的负载均衡器。例如，订单服务调用客户服务的 mid-tier services，那么订单服务就是一个 application client。

当然，这三个部分都可以进行横向的扩展。

下图只画出了订单服务调用客户服务的示例，其他的是一样的。

<img src="https://img2020.cnblogs.com/blog/1731892/202101/1731892-20210122141616294-823444330.png" alt="zzs_eureka_07" style="zoom:67%;" />

通过 Eureka 的结构可以知道，它并不适合作为 edge services  的负载均衡器，Eureka Client 需要具备和 Eureka Server 进行通信的能力，而终端用户并不具备这一点。

# 为什么使用Eureka

Eureka 作为一个专门针对 mid-tier services 的负载均衡器，相比 SLB 等，还是存在很多优点。

<img src="https://img2020.cnblogs.com/blog/1731892/202101/1731892-20210126102216106-1435991969.png" alt="zzs_eureka_08" style="zoom:67%;" />

1. **Eureka 的服务注册是无状态**。如果我新增了一百个新的服务，SLB 需要配置一百个对应的监听，而 Eureka Server 什么都不需要做，你只要注册上来就行，扩展起来非常方便。说的直白一点，SLB 知道自己将处理哪些服务，而 Eureka Server 不会事先知道。
2. **Eureka Server 挂了，Eureka Client 还可以正常消费服务**。Eureka Client 本地会缓存服务地址，即使 Eureka Server 挂了，它还是能够正常消费服务。

以上基本讲完负载均衡器的内容，作为开篇，它让我们思考：一个工具的本质是什么？为什么我们要用它？不用它行不行？

最后，感谢阅读。

# 参考资料

[Eureka github文档](https://github.com/Netflix/eureka/wiki/Eureka-at-a-glance)

> 相关源码请移步：[https://github.com/ZhangZiSheng001/eureka-demo](https://github.com/ZhangZiSheng001/eureka-demo)

>本文为原创文章，转载请附上原文出处链接：[https://www.cnblogs.com/ZhangZiSheng001/p/14313051.html](https://www.cnblogs.com/ZhangZiSheng001/p/14313051.html) 
