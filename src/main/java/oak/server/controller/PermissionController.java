package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.dto.PermissionCatalogEntry;
import oak.server.entity.Permission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * The permission catalog — name + group for every {@link Permission}, in enum order. The client
 * renders the role-editor checkboxes grouped (General / Text / Voice) from this. Read-only, JWT only.
 */
@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    @GetMapping
    public List<PermissionCatalogEntry> catalog() {
        return Arrays.stream(Permission.values())
                .map(permission -> new PermissionCatalogEntry(permission.name(), permission.getGroup().name()))
                .toList();
    }
}
