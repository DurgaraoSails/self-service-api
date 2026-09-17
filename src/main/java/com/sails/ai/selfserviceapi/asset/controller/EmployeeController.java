package com.sails.ai.selfserviceapi.asset.controller;

import com.sails.ai.selfserviceapi.asset.service.EmployeeRoleService;
import com.sails.ai.selfserviceapi.generated.api.EmployeeApi;
import com.sails.ai.selfserviceapi.generated.model.EmployeePageResponse;
import com.sails.ai.selfserviceapi.generated.model.EmployeeResponse;
import com.sails.ai.selfserviceapi.generated.model.UpdateManagedRolesRequest;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "asset-hub", name = "enabled", havingValue = "true")
public class EmployeeController implements EmployeeApi {

    private final EmployeeRoleService employeeRoleService;

    public EmployeeController(EmployeeRoleService employeeRoleService) {
        this.employeeRoleService = employeeRoleService;
    }

    @Override
    public ResponseEntity<EmployeePageResponse> listEmployees(String search, Integer page, Integer size) {
        CurrentUser.requireInternal();
        return ResponseEntity.ok(employeeRoleService.listEmployees(search, page, size));
    }

    @Override
    public ResponseEntity<EmployeeResponse> updateEmployeeManagedRoles(
            String userId, UpdateManagedRolesRequest updateManagedRolesRequest) {
        CurrentUser.requireInternal();
        CurrentUser.requireSuperAdmin();
        return ResponseEntity.ok(employeeRoleService.updateManagedRoles(
                CurrentUser.id(), userId, updateManagedRolesRequest));
    }
}
