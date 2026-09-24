package com.sprintmodus.workitem_service.infrastructure.config;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.sprintmodus.common_lib.security.TenantSecurityConfiguration;
import com.sprintmodus.common_lib.web.CommonWebConfiguration;
import com.sprintmodus.workitem_service.adapter.feign.client.ProjectServiceClient;

/**
 * Multi-tenancy for this service: every request under {@code /api/**} needs a valid JWT and is routed to the caller's
 * tenant database (see {@link TenantSecurityConfiguration}), and errors use the shared response format.
 * <p>
 * JPA runs on the routing DataSource through Spring Boot's own auto-configuration. There is no database to inspect when
 * the application starts (the tenant is only known per request), so {@code application.properties} tells Hibernate
 * everything it would otherwise ask the connection. Every query is a native SQL query (see the persistence rules in the
 * Clean Architecture spec): entities only map results, and repositories live in {@code infrastructure.persistence}.
 */
@Configuration(proxyBeanMethods = false)
@Import({ TenantSecurityConfiguration.class, CommonWebConfiguration.class })
@EnableFeignClients(basePackageClasses = ProjectServiceClient.class)
class TenancyConfig {

}
