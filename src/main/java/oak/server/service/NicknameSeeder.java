package oak.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.entity.User;
import oak.server.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time nickname migration: every user created before the nickname field existed has
 * {@code nickname = null}; the nickname is the mandatory display name (the only @mentionable name),
 * so at startup each such row is back-filled from its login handle. New accounts get the nickname in
 * the {@link User} constructor. Idempotent — rows with a nickname are never touched, so a user who
 * reset the nickname to something else is not clobbered on restart. Collisions cannot occur: logins
 * are unique and no other nicknames exist yet at migration time.
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class NicknameSeeder implements ApplicationRunner {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int migrated = 0;
        for (User user : userRepository.findAllByOrderByIdAsc()) {
            if (user.getNickname() == null || user.getNickname().isBlank()) {
                user.setNickname(user.getUsername());
                userRepository.save(user);
                migrated += 1;
            }
        }
        if (migrated > 0) {
            log.info("[nickname-seeder] back-filled {} users from their login handles", migrated);
        }
    }
}
