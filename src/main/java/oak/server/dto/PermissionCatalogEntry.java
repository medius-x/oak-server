package oak.server.dto;

/** One entry of the permission catalog ({@code GET /api/permissions}): a name + its group, so the
 *  client can render the permission checkboxes grouped (General / Text / Voice). */
public record PermissionCatalogEntry(String name, String group) {
}
