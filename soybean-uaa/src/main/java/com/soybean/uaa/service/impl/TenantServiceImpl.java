package com.soybean.uaa.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.soybean.framework.commons.exception.CheckedException;
import com.soybean.framework.commons.util.StringUtils;
import com.soybean.framework.db.configuration.dynamic.TenantDynamicDataSourceProcess;
import com.soybean.framework.db.configuration.dynamic.event.body.EventAction;
import com.soybean.framework.db.mybatis.SuperServiceImpl;
import com.soybean.framework.db.mybatis.conditions.Wraps;
import com.soybean.framework.db.properties.DatabaseProperties;
import com.soybean.framework.db.properties.MultiTenantType;
import com.soybean.uaa.domain.entity.baseinfo.Role;
import com.soybean.uaa.domain.entity.baseinfo.User;
import com.soybean.uaa.domain.entity.baseinfo.UserRole;
import com.soybean.uaa.domain.entity.tenant.Tenant;
import com.soybean.uaa.domain.entity.tenant.TenantConfig;
import com.soybean.uaa.repository.*;
import com.soybean.uaa.service.DynamicDatasourceService;
import com.soybean.uaa.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @author wenxina
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantServiceImpl extends SuperServiceImpl<TenantMapper, Tenant> implements TenantService {

    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final TenantConfigMapper tenantConfigMapper;
    private final DynamicDatasourceService dynamicDatasourceService;
    private final DatabaseProperties properties;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void saveOrUpdateTenant(Tenant tenant) {
        if (tenant.getId() != null) {
            baseMapper.updateById(tenant);
            return;
        }
        baseMapper.insert(tenant);
    }

    @Override
    public void tenantConfig(TenantConfig tenantConfig) {
        final Tenant tenant = Optional.ofNullable(this.baseMapper.selectById(tenantConfig.getTenantId()))
                .orElseThrow(() -> CheckedException.notFound("租户不存在"));
        if (tenant.getLocked()) {
            throw CheckedException.badRequest("租户已被禁用");
        }
        if (StringUtils.equals(tenant.getCode(), properties.getMultiTenant().getSuperTenantCode())) {
            throw CheckedException.badRequest("超级租户,禁止操作");
        }
        if (tenantConfig.getId() == null) {
            tenantConfigMapper.delete(Wraps.<TenantConfig>lbQ().eq(TenantConfig::getTenantId, tenantConfig.getTenantId()));
            tenantConfigMapper.insert(tenantConfig);
        } else {
            tenantConfigMapper.updateById(tenantConfig);
        }
        // 先创建
        dynamicDatasourceService.publishEvent(EventAction.INIT, tenantConfig.getTenantId());
    }

    @Override
    @DSTransactional
    public void initSqlScript(Long id) {
        final Tenant tenant = Optional.ofNullable(this.baseMapper.selectById(id)).orElseThrow(() -> CheckedException.notFound("租户信息不存在"));
        if (tenant.getLocked()) {
            throw CheckedException.badRequest("租户已被禁用");
        }
        final DatabaseProperties.MultiTenant multiTenant = properties.getMultiTenant();
        if (StringUtils.equals(tenant.getCode(), multiTenant.getSuperTenantCode())) {
            throw CheckedException.badRequest("超级租户,禁止操作");
        }
        if (multiTenant.getType() == MultiTenantType.COLUMN) {
            final Role role = Optional.ofNullable(roleMapper.selectOne(Wraps.<Role>lbQ()
                    .eq(Role::getCode, "TENANT-ADMIN"))).orElseThrow(() -> CheckedException.notFound("内置租户管理员角色不存在"));
            final List<User> users = this.userMapper.selectByTenantId(tenant.getId());
            if (CollUtil.isNotEmpty(users)) {
                final List<Long> userIdList = users.stream().map(User::getId).distinct().collect(Collectors.toList());
                log.warn("开始清除租户 - {} 的系统数据,危险动作", tenant.getName());
                this.userRoleMapper.delete(Wraps.<UserRole>lbQ().in(UserRole::getUserId, userIdList));
                this.userMapper.deleteByTenantId(tenant.getId());
                this.roleMapper.deleteByTenantId(tenant.getId());
                log.warn("开始初始化租户 - {} 的系统数据,危险动作", tenant.getName());
            }
            User record = new User();
            record.setUsername("admin");
            record.setPassword(passwordEncoder.encode("123456"));
            record.setTenantId(id);
            record.setNickName(tenant.getContactPerson());
            record.setMobile(tenant.getContactPhone());
            record.setStatus(true);
            this.userMapper.insert(record);
            this.userRoleMapper.insert(UserRole.builder().userId(record.getId()).roleId(role.getId()).build());

        } else if (multiTenant.getType() == MultiTenantType.DATASOURCE) {
            TenantDynamicDataSourceProcess tenantDynamicDataSourceProcess = SpringUtil.getBean(TenantDynamicDataSourceProcess.class);
            tenantDynamicDataSourceProcess.initSqlScript(tenant.getId(), tenant.getCode());
        }
    }
}
