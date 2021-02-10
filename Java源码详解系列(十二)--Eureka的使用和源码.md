eureka 是由 Netflix 团队开发的针对中间层服务的负载均衡器，在微服务项目中被广泛使用。相比 SLB、ALB 等负载均衡器，eureka 的服务注册是无状态的，扩展起来非常方便。

在[这个系列](https://www.cnblogs.com/ZhangZiSheng001/category/1920698.html)中，我将深入研究 eureka，包括它的使用、源码等，总计分成五篇博客讲完，后面发现有趣的东西也会继续补充。

 [Eureka详解系列(一)--先谈谈负载均衡器](https://www.cnblogs.com/ZhangZiSheng001/p/14313051.html) 

 [Eureka详解系列(二)--如何使用Eureka(原生API，无Spring)](https://www.cnblogs.com/ZhangZiSheng001/p/14337985.html) 

 [Eureka详解系列(三)--探索Eureka强大的配置体系](https://www.cnblogs.com/ZhangZiSheng001/p/14374005.html) 

 [Eureka详解系列(四)--Eureka Client部分的源码和配置](https://www.cnblogs.com/ZhangZiSheng001/p/14381169.html) 

 [Eureka详解系列(五)--Eureka Server部分的源码和配置](https://www.cnblogs.com/ZhangZiSheng001/p/14395079.html) 

> 相关源码请移步：[https://github.com/ZhangZiSheng001/eureka-demo](https://github.com/ZhangZiSheng001/eureka-demo)

>本文为原创文章，转载请附上原文出处链接：[https://www.cnblogs.com/ZhangZiSheng001/p/14395203.html](https://www.cnblogs.com/ZhangZiSheng001/p/14395203.html)
