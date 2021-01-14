

# 什么是SPI机制



# 怎么使用SPI

## 需求

利用`SPI`机制加载用户服务接口的实现类并测试。

## 工程环境

`JDK`：1.8.0_201

`maven`：3.6.1

`IDE`：eclipse 4.12

## 主要步骤

1. 编写用户服务类接口和实现类；
2. 在`classpath`路径下的`META-INF/services`文件夹下配置好接口的实现类；
3. 利用`SPI`机制加载接口实现类并测试。

## 创建项目

项目类型Maven Project，打包方式`jar`

## 引入依赖

```xml
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.12</version>
            <scope>test</scope>
        </dependency>
```


## 编写测试方法

路径：test下的`cn.zzs.spi`。如果实际项目中配置了比较多的接口文件，可以考虑抽取工具类。

```java

```

## 测试结果

```

```

# 源码分析


# 参考资料

[深入理解SPI机制]: https://www.jianshu.com/p/3a3edbcd8f24

>本文为原创文章，转载请附上原文出处链接：https://github.com/ZhangZiSheng001/01-spi-demo
