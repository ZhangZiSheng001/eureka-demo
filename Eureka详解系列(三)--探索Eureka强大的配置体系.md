# 简介

通过前面的两篇[博客](https://www.cnblogs.com/ZhangZiSheng001/category/1920698.html)，我们知道了：什么是 Eureka？为什么使用 Eureka？如何适用 Eureka？今天，我们开始来研究 Eureka 的源码，先从配置部分的源码开始看，其他部分后面再补充。

补充一点，我更多地会从设计层面分析源码，而不会顺序地剖析每个过程的代码。一方面是因为篇幅有限，另一方面是因为我认为这样做更有意义一些。

# 项目环境

os：win 10

jdk：1.8.0_231

eureka：1.10.11

maven：3.6.3

# 从一个例子开始

**`ConcurrentCompositeConfiguration`这个类是 Eureka 配置体系的核心**。在这个例子中，我们使用它**对 property 进行增删改查**，并**注册了自定义监听器来监听 property 的改变**。

```java
    @Test
    public void test01() {
        // 创建配置对象
        final ConcurrentCompositeConfiguration config = new ConcurrentCompositeConfiguration(); 
        // 注册监听器监听property的改变
        config.addConfigurationListener(new ConfigurationListener() {
            
            public void configurationChanged(ConfigurationEvent event) {
                // 增加property
                if(AbstractConfiguration.EVENT_ADD_PROPERTY == event.getType() 
                        && !event.isBeforeUpdate()) {
                    System.err.println("add property：" + event.getPropertyName() + "=" + event.getPropertyValue());
                    return;
                }
                // 删除property
                if(AbstractConfiguration.EVENT_CLEAR_PROPERTY == event.getType()) {
                    System.err.println("clear property：" + event.getPropertyName());
                    return;
                }
                // 更新property
                if(AbstractConfiguration.EVENT_SET_PROPERTY == event.getType() 
                        && event.isBeforeUpdate()
                        && !config.getString(event.getPropertyName()).equals(event.getPropertyValue())) {
                    System.err.println("update property：" 
                    + event.getPropertyName() 
                    + ":" 
                    + config.getString(event.getPropertyName())
                    + "==>"
                    + event.getPropertyValue()
                    );
                    return;
                }
            }
        });
        // 添加property
        config.addProperty("author", "zzs");
        // 获取property
        System.err.println(config.getString("author"));
        // 更改property
        config.setProperty("author", "zzf");
        // 删除property
        config.clearProperty("author");
    }
//    运行以上方法，控制台打印内容：
//    add property：author=zzs
//    zzs
//    update property：author:zzs==>zzf
//    clear property：author
```

可以看到，当我们更改了 property 时，监听器中的方法被触发了，利用这一点，我们可以实现动态配置。

后面就会发现，**Eureka 底层使用`ConcurrentCompositeConfiguration`来对配置参数进行增删改查，并基于事件监听的机制来支持动态配置**。

# 另一个有意思的地方

我们再来看看一个 UML 图。上面例子中说到`ConcurrentCompositeConfiguration`的两个功能，是通过实现`Configuration`和继承`EventSource`来获得的，这一点没什么特别的，之所以深究它，是因为我发现了其他有趣的地方。

<img src="https://img2020.cnblogs.com/blog/1731892/202102/1731892-20210204173518563-167523473.png" alt="zzs_eureka_15" style="zoom:67%;" />

我们主要来关注下它的三个成员属性（它们都是`AbstractConfiguration`类型）：

1. **configList**：持有的配置对象集合。**这个集合的配置对象存在优先级**，举个例子，如果我添加了 Configuration1 和 Configuration2，当我们`getProperty(String)`时，会优先从 Configuration1 获取，实在找不到才会去 Configuration2 获取。
2. **overrideProperties**：**最高优先级的配置对象**。当我们`getProperty(String)`时，会先从这里获取，实在没有才会去 configList 里找。
3. **containerConfiguration**：**保底的配置对象**。一般是 configList 的最后一个（注意，不一定是最后一个1），我们往`ConcurrentCompositeConfiguration`里增删改 property，实际操作的就是这个对象。

为了更好理解它们的作用，我写了个测试例子。

```java
    @Test
    public void test02() {
        // 创建配置对象
        ConcurrentCompositeConfiguration config = new ConcurrentCompositeConfiguration(); 
        // 添加配置1
        ConcurrentMapConfiguration config1 = new ConcurrentMapConfiguration();
        config1.addProperty("author", "zzs");
        config.addConfiguration(config1, "CONFIG_01");
        
        // 添加配置2
        ConcurrentMapConfiguration config2 = new ConcurrentMapConfiguration();
        config2.addProperty("author", "zzf");
        config.addConfiguration(config2, "CONFIG_02");
        
        // 在默认的containerConfiguration中添加property
        config.addProperty("author", "zhw");
        
        // ============以下测试configList的优先级============
        System.err.println(config.getString("author"));
        // 删除config1中的property
        config1.clearProperty("author");
        System.err.println(config.getString("author"));
        // 删除config2中的property
        config2.clearProperty("author");
        System.err.println(config.getString("author"));
        
        // ============以下测试overrideProperties的优先级============
        // 添加overrideProperties的property
        config.setOverrideProperty("author", "lt");
        System.err.println(config.getString("author"));
    }
//    运行以上方法，控制台打印内容：
//    zzs
//    zzf
//    zhw
//    lt
```

这里补充一点，当我们创建`ConcurrentCompositeConfiguration`时，就会生成一个 containerConfiguration，默认情况下，它会一直在集合最后面，每次添加新的配置对象，都是往 containerConfiguration 前面插入。

# 谁来加载配置

通过上面的例子可以知道，`ConcurrentCompositeConfiguration`并不会主动地去加载配置，所以，Eureka 需要自己往`ConcurrentCompositeConfiguration`里添加配置，而完成这件事的是另外一个类--`ConfigurationManager`。

<img src="https://img2020.cnblogs.com/blog/1731892/202102/1731892-20210204173546818-1587156597.png" alt="zzs_eureka_16" style="zoom:67%;" />

**`ConfigurationManager`作为一个单例对象使用，用来初始化配置对象，以及提供加载配置文件的方法**（后面的`DefaultEurekaClientConfig`、`DefaultEurekaServerConfig`会来调用这些方法）。

下面我们看看配置对象的初始化。在`ConfigurationManager`被加载时就会初始化配置对象，进入到它的静态代码块就可以找到。我截取的是最关键部分的代码。

```java
    private static AbstractConfiguration createDefaultConfigInstance() {
        ConcurrentCompositeConfiguration config = new ConcurrentCompositeConfiguration();  
        try {
            // 加载指定url的配置
            // 通过archaius.configurationSource.additionalUrls启动参数设置url，多个逗号隔开
            DynamicURLConfiguration defaultURLConfig = new DynamicURLConfiguration();
            config.addConfiguration(defaultURLConfig, URL_CONFIG_NAME);
        } catch (Throwable e) {
            logger.warn("Failed to create default dynamic configuration", e);
        }
        if (!Boolean.getBoolean(DISABLE_DEFAULT_SYS_CONFIG)) {
            // 加载System.getProperties()的配置
            // 通过archaius.dynamicProperty.disableSystemConfig启动参数可以控制是否添加
            SystemConfiguration sysConfig = new SystemConfiguration();
            config.addConfiguration(sysConfig, SYS_CONFIG_NAME);
        }
        if (!Boolean.getBoolean(DISABLE_DEFAULT_ENV_CONFIG)) {
            // 加载System.getenv()的配置
            // 通过archaius.dynamicProperty.disableEnvironmentConfig启动参数可以控制是否添加
            EnvironmentConfiguration envConfig = new EnvironmentConfiguration();
            config.addConfiguration(envConfig, ENV_CONFIG_NAME);
        }
        // 这个是自定义的保底配置
        ConcurrentCompositeConfiguration appOverrideConfig = new ConcurrentCompositeConfiguration();
        config.addConfiguration(appOverrideConfig, APPLICATION_PROPERTIES);
        config.setContainerConfigurationIndex(config.getIndexOfConfiguration(appOverrideConfig));// 这里可以更改保底配置
        return config;
    }
```

可以看到，**Eureka 支持通过 url 来指定配置文件，只要指定启动参数就行**，这一点将有利于我们更灵活地对项目进行配置。默认情况下，它还会去加载所有的系统参数和环境参数。

另外，当我们设置以下启动参数，就可以通过 JMX 的方式来更改配置。

```properties
-Darchaius.dynamicPropertyFactory.registerConfigWithJMX=true
```

配置对象初始化后，`ConfigurationManager`提供了方法供我们加载配置文件（本地或远程），如下。

```java
// 这两个的区别在于：前者会生成一个新的配置添加到configList;后者直接将property都加入到appOverrideConfig
public static void loadCascadedPropertiesFromResources(String configName) throws IOException;
public static void loadAppOverrideProperties(String appConfigName);
```

# 怎么拿到最新的参数

动态配置的内容直接看源码不大好理解，我们先通过一个再简单不过的例子开始来一步步实现我们自己的动态配置。在下面的方法中，我更改了 property，但是拿不到更新的值。原因嘛，我相信大家都知道。

```java
    @Test
    public void test03() {
        // 获取配置对象
        AbstractConfiguration config = ConfigurationManager.getConfigInstance();
        // 添加一个property
        config.addProperty("author", "zzs");
        
        String author = config.getString("author", "");
        
        System.err.println(author);
        
        // 更改property
        config.setProperty("author", "zzf");
        
        System.err.println(author);
    }
//    运行以上方法，控制台打印内容：
//    zzs
//    zzs
```

为了拿到更新的值，我把代码改成这样。我不定义变量来存放 property 的值，每次都重新获取。显然，这样做可以成功。

```java
    @Test
    public void test04() {
        // 获取配置对象
        AbstractConfiguration config = ConfigurationManager.getConfigInstance();
        // 添加一个property
        config.addProperty("author", "zzs");
        
        System.err.println(config.getString("author", ""));
        
        // 更改property
        config.setProperty("author", "zzf");
        
        System.err.println(config.getString("author", ""));
    }
//    运行以上方法，控制台打印内容：
//    zzs
//    zzf
```

但是上面的做法有个问题，我们都知道从`ConcurrentCompositeConfiguration`中获取 property 是比较麻烦的，因为我需要去遍历 configList，以及进行参数的转换等。每次都这样拿，不大合理。

于是，我增加了缓存来减少这部分的开销，当然，property 更改时我必须刷新缓存。

```java
    @Test
    public void test05() {
        // 缓存
        Map<String, String> cache = new ConcurrentHashMap<String, String>();
        // 获取配置对象
        AbstractConfiguration config = ConfigurationManager.getConfigInstance();
        // 添加一个property
        config.addProperty("author", "zzs");
        
        String value = cache.computeIfAbsent("author", x -> config.getString(x, ""));
        System.err.println(value);
        
        // 添加监听器监听property的更改
        config.addConfigurationListener(new ConfigurationListener() {
            public void configurationChanged(ConfigurationEvent event) {
                // 删除property
                if(AbstractConfiguration.EVENT_CLEAR_PROPERTY == event.getType()) {
                    cache.remove(event.getPropertyName());
                    return;
                }
                // 更新property
                if(AbstractConfiguration.EVENT_SET_PROPERTY == event.getType() 
                        && !event.isBeforeUpdate()) {
                    cache.put(event.getPropertyName(), String.valueOf(event.getPropertyValue()));
                    return;
                }
            }
        });
        
        // 更改property
        config.setProperty("author", "zzf");
        
        System.err.println(cache.get("author"));
    }
//    运行以上方法，控制台打印内容：
//    zzs
//    zzf
```

通过上面的例子，我们实现了动态配置。

现在我们再来看看 Eureka 是怎么实现的。这里用到了`DynamicPropertyFactory`和`DynamicStringProperty`两个类，通过它们，也实现了动态配置。

```java
    @Test
    public void test06() {
        // 获取配置对象
        AbstractConfiguration config = ConfigurationManager.getConfigInstance();
        // 添加一个property
        config.addProperty("author", "zzs");
        
        // 通过DynamicPropertyFactory获取property
        DynamicPropertyFactory dynamicPropertyFactory = DynamicPropertyFactory.getInstance();
        DynamicStringProperty stringProperty = dynamicPropertyFactory.getStringProperty("author", "");
        
        System.err.println(stringProperty.get());
        
        // 更改property
        config.setProperty("author", "zzf");
        
        System.err.println(stringProperty.get());
    }
//    运行以上方法，控制台打印内容：
//    zzs
//    zzf
```

至于原理，其实和我们上面的例子是差不多的。通过 UML 图可以知道，`DynamicProperty`中就放了一张缓存表，每次获取 property 时，会优先从这里拿。

<img src="https://img2020.cnblogs.com/blog/1731892/202102/1731892-20210204173624969-2136390795.png" alt="zzs_eureka_17" style="zoom:67%;" />

既然有缓存，就应该有监听器，没错，在`DynamicProperty.initialize(DynamicPropertySupport)`方法中就可以看到。

```java
    static synchronized void initialize(DynamicPropertySupport config) {
        dynamicPropertySupportImpl = config;
        // 注册监听器
        config.addConfigurationListener(new DynamicPropertyListener());
        updateAllProperties();
    }
```

# Eureka有那几类配置

在上面的分析中，我们用`ConfigurationManager`来初始化配置对象，并使用`DynamicPropertyFactory`来实现动态配置，这些东西构成了 Eureka 的配置体系的基础，比较通用。基础之上，是 Eureka 更具体的一些配置对象。

在 Eureka 里，配置分成了三种（理解这一点非常重要）：

1. **EurekaInstanceConfig**：当前实例身份的配置信息，即<font color='red'>**我是谁？**</font>
2. **EurekaServerConfig**：一些影响当前Eureka Server和客户端或对等节点交互行为的配置信息，即<font color='red'>**怎么交互？**</font>
3. **EurekaClientConfig**：一些影响当前实例和Eureka Server交互行为的配置信息，即<font color='red'>**和谁交互？怎么交互？**</font>

这三个对象都持有了`DynamicPropertyFactory`的引用，所以支持动态配置，另外，它们还是用`ConfigurationManager`来加载自己想要的配置文件。例如，`EurekaInstanceConfig`、`EurekaClientConfig`负责加载`eureka-client.properties`，而`EurekaServerConfig`则负责加载`eureka-server.properties`。

<img src="https://img2020.cnblogs.com/blog/1731892/202102/1731892-20210204173644999-210494679.png" alt="zzs_eureka_18" style="zoom:67%;" />

以上基本讲完 Eureka 配置体系的源码，可以看到，这是一套非常优秀的配置体系，实际项目中可以参考借鉴。

最后，感谢阅读。

# 参考资料

> 相关源码请移步：[https://github.com/ZhangZiSheng001/eureka-demo](https://github.com/ZhangZiSheng001/eureka-demo)

>本文为原创文章，转载请附上原文出处链接：[https://www.cnblogs.com/ZhangZiSheng001/p/14374005.html](https://www.cnblogs.com/ZhangZiSheng001/p/14374005.html) 
