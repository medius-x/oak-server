package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.MentionResponse;
import oak.server.entity.Channel;
import oak.server.entity.Permission;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.entity.User;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import oak.server.websocket.PresenceTracker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses plain-text {@code @token} mentions in a message body into structured {@link MentionResponse}s
 * and expands them to the concrete set of pinged user ids.
 *
 * <p><b>Grammar</b> (v1): a bare {@code @word} where {@code word} is {@code [A-Za-z0-9_]+}. Resolution
 * order per token (first match wins): {@code @everyone} / {@code @here} literals; a mentionable custom
 * role by name; a user by username. Permission gating matches Discord:
 * <ul>
 *   <li>{@code @everyone} / {@code @here} require the author's {@link Permission#MENTION_EVERYONE}
 *       in the channel (override-aware); without it the literal stays plain text and no mention is
 *       recorded.</li>
 *   <li>{@code @role} requires {@code role.mentionable == true} (built-ins are never role-mentionable:
 *       {@code @everyone} has its own literal, {@code OWNER} is never mentionable).</li>
 *   <li>{@code @user} is always allowed.</li>
 * </ul>
 *
 * <p>OWNER / {@code ADMINISTRATOR} short-circuit to "can mention anything" via
 * {@link AuthorizationService#hasInChannel} — no special case here. Parsing runs on every read
 * (no DB column on {@code Message}); renaming a user/role therefore leaves old {@code @mentions} as
 * plain text.
 */
@Component
@RequiredArgsConstructor
public class MentionParser {

    private static final Pattern MENTION_PATTERN = Pattern.compile("@(\\w+)");

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final AuthorizationService auth;
    private final PresenceTracker presenceTracker;

    /** Parses {@code @token}s in {@code content}, gating per the rules above. Offsets index into
     *  {@code content} so the client can highlight exact substrings. */
    public List<MentionResponse> parse(String content, User author, Channel channel) {
        List<MentionResponse> mentions = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return mentions;
        }
        // @everyone/@here gating is constant for a single message — compute once.
        boolean canMentionEveryone = auth.hasInChannel(author, channel, Permission.MENTION_EVERYONE);
        Matcher matcher = MENTION_PATTERN.matcher(content);
        while (matcher.find()) {
            String token = matcher.group(1);
            MentionResponse resolved = resolve(token, canMentionEveryone);
            if (resolved != null) {
                mentions.add(new MentionResponse(
                        resolved.type(), resolved.id(), resolved.name(),
                        matcher.start(), matcher.end()));
            }
        }
        return mentions;
    }

    private MentionResponse resolve(String token, boolean canMentionEveryone) {
        // @everyone — matched as a literal, not by role name (the seeder stores the literal "@everyone").
        if ("everyone".equalsIgnoreCase(token)) {
            return canMentionEveryone ? everyoneMention() : null;
        }
        // @here
        if ("here".equalsIgnoreCase(token)) {
            return canMentionEveryone ? new MentionResponse("HERE", null, null, 0, 0) : null;
        }
        // @role — only mentionable custom roles; a non-mentionable role does not consume the token,
        // so a user who happens to share the name can still be @mentioned.
        Optional<Role> roleOpt = roleRepository.findByName(token);
        if (roleOpt.isPresent()) {
            Role role = roleOpt.get();
            if (role.getKind() == RoleKind.CUSTOM && role.isMentionable()) {
                return new MentionResponse("ROLE", role.getId(), role.getName(), 0, 0);
            }
        }
        // @user — always allowed.
        Optional<User> userOpt = userRepository.findByUsername(token);
        if (userOpt.isPresent()) {
            User target = userOpt.get();
            return new MentionResponse("USER", target.getId(), target.getUsername(), 0, 0);
        }
        return null;
    }

    private MentionResponse everyoneMention() {
        Long everyoneId = roleRepository.findByKind(RoleKind.EVERYONE).map(Role::getId).orElse(null);
        return new MentionResponse("EVERYONE", everyoneId, null, 0, 0);
    }

    /** Expands structured mentions to the concrete set of pinged user ids — used by read-state
     *  mention-count and notification routing. {@code @here} resolves to currently-online users
     *  (a user who receives the live push is, by definition, online). */
    public Set<Long> expandToUserIds(List<MentionResponse> mentions) {
        Set<Long> ids = new HashSet<>();
        if (mentions == null) {
            return ids;
        }
        for (MentionResponse mention : mentions) {
            switch (mention.type()) {
                case "USER" -> {
                    if (mention.id() != null) {
                        ids.add(mention.id());
                    }
                }
                case "ROLE" -> {
                    if (mention.id() != null) {
                        for (User member : userRepository.findUsersByRoleId(mention.id())) {
                            ids.add(member.getId());
                        }
                    }
                }
                case "EVERYONE" -> {
                    for (User member : userRepository.findAll()) {
                        ids.add(member.getId());
                    }
                }
                case "HERE" -> {
                    for (String username : presenceTracker.onlineUsernames()) {
                        userRepository.findByUsername(username).ifPresent(user -> ids.add(user.getId()));
                    }
                }
                default -> { /* unknown type — ignore for forward-compat */ }
            }
        }
        return ids;
    }
}
