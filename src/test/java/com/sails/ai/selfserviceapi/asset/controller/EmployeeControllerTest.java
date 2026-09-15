package com.sails.ai.selfserviceapi.asset.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sails.ai.selfserviceapi.asset.service.EmployeeRoleService;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.common.exception.GlobalExceptionHandler;
import com.sails.ai.selfserviceapi.generated.model.AccountType;
import com.sails.ai.selfserviceapi.generated.model.EmployeePageResponse;
import com.sails.ai.selfserviceapi.generated.model.EmployeeResponse;
import com.sails.ai.selfserviceapi.generated.model.UpdateManagedRolesRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = EmployeeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@TestPropertySource(properties = "asset-hub.enabled=true")
class EmployeeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmployeeRoleService employeeRoleService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listEmployeesReturns200ForAnyInternalCaller() throws Exception {
        authenticate("employee-1", List.of());
        when(employeeRoleService.listEmployees(any(), anyInt(), anyInt()))
                .thenReturn(new EmployeePageResponse(List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get("/employees"))
                .andExpect(status().isOk());
    }

    @Test
    void listEmployeesReturns403WhenTheCallerIsNotInternal() throws Exception {
        authenticateExternal("customer-1");

        mockMvc.perform(get("/employees"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTERNAL_ACCOUNT_REQUIRED"));
    }

    @Test
    void updateEmployeeManagedRolesReturns403WithoutSuperadmin() throws Exception {
        authenticate("admin-1", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        UpdateManagedRolesRequest request = new UpdateManagedRolesRequest(
                List.of(UpdateManagedRolesRequest.RolesEnum.ASSET_REVIEWER));

        mockMvc.perform(put("/employees/{userId}/roles", "target-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SUPERADMIN_REQUIRED"));
    }

    @Test
    void updateEmployeeManagedRolesReturns200ForASuperadmin() throws Exception {
        authenticate("superadmin-1", List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
        UpdateManagedRolesRequest request = new UpdateManagedRolesRequest(
                List.of(UpdateManagedRolesRequest.RolesEnum.ASSET_REVIEWER));
        when(employeeRoleService.updateManagedRoles(eq("superadmin-1"), eq("target-1"), any()))
                .thenReturn(new EmployeeResponse("target-1", "target@sailssoftware.com", "Target", "Employee",
                        List.of("USER", "ASSET_REVIEWER"), AccountType.INTERNAL));

        mockMvc.perform(put("/employees/{userId}/roles", "target-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("target-1"));
    }

    @Test
    void updateEmployeeManagedRolesPropagatesServiceRejectionAsForbidden() throws Exception {
        authenticate("superadmin-1", List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
        UpdateManagedRolesRequest request = new UpdateManagedRolesRequest(List.of());
        when(employeeRoleService.updateManagedRoles(eq("superadmin-1"), eq("superadmin-1"), any()))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "ROLE_SELF_MANAGEMENT_FORBIDDEN",
                        "You cannot change your own managed roles."));

        mockMvc.perform(put("/employees/{userId}/roles", "superadmin-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_SELF_MANAGEMENT_FORBIDDEN"));
    }

    private static void authenticate(String userId, List<? extends GrantedAuthority> authorities) {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), Map.of("sub", userId, "accountType", "INTERNAL"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }

    private static void authenticateExternal(String userId) {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), Map.of("sub", userId, "accountType", "EXTERNAL"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }
}
