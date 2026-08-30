package com.arte.core.factory;

import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;

import java.io.IOException;
import java.util.List;

/**
 * 加载 Yaml 配置文件
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/2/24 19:20 ✾
 */
public class YamlPropertySourceFactory implements PropertySourceFactory {

    @Override
    public PropertySource<?> createPropertySource(String name, EncodedResource encodedResource) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> propertySources = loader.load(
                encodedResource.getResource().getFilename(),
                encodedResource.getResource()
        );

        if (propertySources.isEmpty()) {
            throw new IllegalArgumentException("No property sources found");
        }

        return propertySources.get(0);
    }
}
