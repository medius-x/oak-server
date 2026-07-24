package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.CreateRoleRequest;
import oak.server.dto.ReorderRolesRequest;
import oak.server.dto.RoleResponse;
import oak.server.dto.UpdateRoleRequest;
import oak.server.service.RoleService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Role management. List is open to any member; create/update/delete/reorder require
 * {@code MANAGE_ROLES} (+ hierarchy), enforced in {@link RoleService}. All endpoints require a JWT.
 */
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    public List<RoleResponse> list() {
        return roleService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoleResponse create(@RequestBody @Valid CreateRoleRequest request,
                               @AuthenticationPrincipal UserDetails principal) {
        return roleService.create(request, principal.getUsername());
    }

    @PatchMapping("/{id}")
    public RoleResponse update(@PathVariable Long id,
                               @RequestBody @Valid UpdateRoleRequest request,
                               @AuthenticationPrincipal UserDetails principal) {
        return roleService.update(id, request, principal.getUsername());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id,
                      @AuthenticationPrincipal UserDetails principal) {
        roleService.delete(id, principal.getUsername());
    }

    @PutMapping("/order")
    public void reorder(@RequestBody @Valid ReorderRolesRequest request,
                        @AuthenticationPrincipal UserDetails principal) {
        roleService.reorder(request, principal.getUsername());
    }
}
