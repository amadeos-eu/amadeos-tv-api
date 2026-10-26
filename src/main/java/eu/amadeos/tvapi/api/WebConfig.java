package eu.amadeos.tvapi.api;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/** ETags for the API: TV asks with If-None-Match and gets a short 304 when nothing changed. */
@Configuration(proxyBeanMethods = false)
class WebConfig {
    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> etags() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> filter = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        filter.addUrlPatterns("/tv/v1/*");
        return filter;
    }
}
