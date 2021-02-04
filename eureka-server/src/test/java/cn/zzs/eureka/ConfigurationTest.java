package cn.zzs.eureka;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.event.ConfigurationEvent;
import org.apache.commons.configuration.event.ConfigurationListener;
import org.junit.Test;

import com.netflix.config.ConcurrentCompositeConfiguration;
import com.netflix.config.ConcurrentMapConfiguration;
import com.netflix.config.ConfigurationManager;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.config.DynamicStringProperty;

/**
 * 测试eureka的配置体系
 * @author zzs
 * @date 2021年2月4日 上午9:22:09
 */
public class ConfigurationTest {
    
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
    
    @Test
    public void test05() {
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

}
