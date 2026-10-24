package com.aegisos.boot.config;

import com.aegisos.storage.audit.AuditStorageConfiguration;
import com.aegisos.web.baseline.WebBaselineConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** 显式Web基础source，无组件扫描/Agent/Provider装配；与旧无参数Web入口分离。 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ConditionalOnProperty(name = "aegisos.serve.enabled", havingValue = "true")
@Import({WebBaselineConfiguration.class, AuditStorageConfiguration.class})
public class ServeApplicationConfiguration {}
