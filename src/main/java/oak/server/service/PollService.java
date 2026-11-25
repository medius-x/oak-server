package oak.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.dto.CreatePollRequest;
import oak.server.dto.MyPollVotesDto;
import oak.server.dto.PollEvent;
import oak.server.dto.PollInfo;
import oak.server.dto.PollResults;
import oak.server.dto.VotePollRequest;
import oak.server.entity.Channel;
import oak.server.entity.Message;
import oak.server.entity.Permission;
import oak.server.entity.Poll;
import oak.server.entity.PollOption;
import oak.server.entity.PollVote;
import oak.server.entity.User;
import oak.server.repository.PollOptionRepository;
import oak.server.repository.PollRepository;
import oak.server.repository.PollVoteRepository;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Poll voting lifecycle (2026-11-25): vote / results / close / deadline sweep. Creation lives in
 * {@link MessageService#createPoll} — a poll message is a message first (the SEND_MESSAGES +
 * CREATE_POLLS gates and the base-topic broadcast are the message machinery's job); everything
 * that happens AFTER the message exists lives here.
 *
 * <p>Live updates ride {@code /topic/channel.{id}.poll} (the reactions-suffix pattern) and carry
 * HEADLINE counters only — never per-option tallies. A spectator cannot eavesdrop percentages:
 * tallies travel solely in {@link PollResults} answers to a client's own action (its vote or an
 * explicit "show results" — the user's card design: results stay hidden until a button is
 * pressed, and viewing never closes the ability to vote).
 *
 * <p>Deadline handling is LAZY first: every read that observes {@code endsAt} in the past closes
 * the poll on the spot (and pushes the finish), and {@link PollSweeper} mops up whatever no read
 * touched — the clients' cards flip to final results either way.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PollService {

    private final PollRepository pollRepository;
    private final PollOptionRepository pollOptionRepository;
    private final PollVoteRepository pollVoteRepository;
    private final oak.server.repository.ChannelRepository channelRepository;
    private final AuthorizationService auth;
    private final SimpMessagingTemplate messagingTemplate;

    /** Casts (or, when the poll allows it, REPLACES) the voter's whole selection. */
    @Transactional
    public PollResults vote(Long pollId, VotePollRequest request, String voterUsername) {
        User voter = auth.requireUser(voterUsername);
        Poll poll = requirePoll(pollId);
        Channel channel = poll.getMessage().getChannel();
        auth.requireInChannel(voter, channel, Permission.VOTE_POLLS);
        finishIfExpired(poll);
        if (poll.getClosedAt() != null) {
            throw conflict("This poll has already finished");
        }
        // Dedupe the ids, then resolve them strictly inside THIS poll — a foreign option id is
        // a plain 400, not a leak (the findByIdAndChannelId scoping idiom).
        Set<Long> optionIds = new HashSet<>(request.optionIds());
        if (optionIds.isEmpty()) {
            throw badRequest("A vote needs at least one option");
        }
        if (!poll.isAllowMultiple() && optionIds.size() > 1) {
            throw badRequest("This poll allows exactly one choice");
        }
        List<PollOption> options = pollOptionRepository.findAllById(optionIds).stream()
                .filter(option -> option.getPoll().getId().equals(pollId))
                .toList();
        if (options.size() != optionIds.size()) {
            throw badRequest("Unknown poll option");
        }
        if (!pollVoteRepository.findByPollIdAndUsername(pollId, voter.getUsername()).isEmpty()
                && !poll.isAllowVoteChange()) {
            throw conflict("This poll does not allow changing your vote");
        }
        pollVoteRepository.deleteByPollIdAndUsername(pollId, voter.getUsername());
        Instant votedAt = Instant.now();
        for (PollOption option : options) {
            pollVoteRepository.save(new PollVote(poll, option, voter.getUsername(), votedAt));
        }
        log.info("[poll] {} voted in poll {} ({} option(s))", voter.getUsername(), pollId, options.size());
        return resultsAndBroadcast(poll);
    }

    /** The tallied results — available to everyone who can read the channel; viewing never
     *  affects the viewer's ability to vote (user decision). */
    @Transactional
    public PollResults results(Long pollId, String viewerUsername) {
        User viewer = auth.requireUser(viewerUsername);
        Poll poll = requirePoll(pollId);
        auth.requireInChannel(viewer, poll.getMessage().getChannel(), Permission.READ_MESSAGE_HISTORY);
        finishIfExpired(poll);
        return tally(poll);
    }

    /**
     * Early finish: the poll's author, the OWNER/ADMINISTRATOR line, or MANAGE_MESSAGES
     * (channel-scoped — user decision 2026-11-25). Idempotent: closing an already-finished poll
     * is a quiet no-op, so a double click or a race with the sweeper stays harmless.
     */
    @Transactional
    public void close(Long pollId, String actorUsername) {
        User actor = auth.requireUser(actorUsername);
        Poll poll = requirePoll(pollId);
        Channel channel = poll.getMessage().getChannel();
        boolean author = poll.getMessage().getAuthor().getUsername().equals(actor.getUsername());
        if (!author && !auth.isOwner(actor) && !auth.hasServer(actor, Permission.ADMINISTRATOR)) {
            auth.requireInChannel(actor, channel, Permission.MANAGE_MESSAGES);
        }
        if (poll.getClosedAt() != null) {
            return;
        }
        finishIfExpired(poll);
        if (poll.getClosedAt() == null) {
            closeAndBroadcast(poll);
        }
        log.info("[poll] poll {} closed early by {}", pollId, actor.getUsername());
    }

    /** The deadline sweep ({@link PollSweeper}): closes everything whose window has passed and
     *  no lazy read happened to touch first. */
    @Transactional
    public void sweepExpired() {
        for (Poll poll : pollRepository.findByClosedAtIsNullAndEndsAtLessThanEqual(Instant.now())) {
            closeAndBroadcast(poll);
            log.info("[poll] poll {} finished (deadline)", poll.getId());
        }
    }

    /** The message-page batch: PollInfo by message id for toResponse (one + one query). */
    @Transactional(readOnly = true)
    public Map<Long, PollInfo> pollsByMessageId(List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        List<Poll> polls = pollRepository.findByMessageIdIn(messageIds);
        if (polls.isEmpty()) {
            return Map.of();
        }
        List<Long> pollIds = polls.stream().map(Poll::getId).toList();
        Map<Long, List<PollOption>> optionsByPoll = new LinkedHashMap<>();
        for (PollOption option : pollOptionRepository.findByPollIdInOrderByPositionAsc(pollIds)) {
            optionsByPoll.computeIfAbsent(option.getPoll().getId(), id -> new ArrayList<>()).add(option);
        }
        Map<Long, PollInfo> byMessage = new LinkedHashMap<>();
        for (Poll poll : polls) {
            byMessage.put(poll.getMessage().getId(), pollInfo(poll,
                    optionsByPoll.getOrDefault(poll.getId(), List.of())));
        }
        return byMessage;
    }

    /** The wire shape of one poll (options in creation order). */
    public PollInfo pollInfo(Poll poll, List<PollOption> options) {
        return new PollInfo(poll.getId(), poll.getQuestion(), poll.isAllowMultiple(), poll.isAnonymous(),
                poll.isAllowVoteChange(), poll.getEndsAt(), poll.getClosedAt(),
                options.stream()
                        .map(option -> new PollInfo.PollOptionInfo(option.getId(), option.getText()))
                        .toList());
    }

    /**
     * The creation step of {@link MessageService#createPoll}: persists the poll and its options
     * for the ALREADY-SAVED message (the poll needs the message id) and returns the wire shape.
     * Runs inside the caller's transaction — a validation failure rolls the message out too.
     */
    @Transactional
    public PollInfo attachPoll(Message message, CreatePollRequest request) {
        Poll poll = new Poll();
        poll.setMessage(message);
        poll.setQuestion(request.question());
        // Boolean wrappers + TRUE.equals: Jackson 3 rejects a null mapped onto a primitive
        // (FAIL_ON_NULL_FOR_PRIMITIVES), so an absent flag must read as false here — the
        // dialog always sends all three, a hand-rolled client may not.
        poll.setAllowMultiple(Boolean.TRUE.equals(request.allowMultiple()));
        poll.setAnonymous(Boolean.TRUE.equals(request.anonymous()));
        poll.setAllowVoteChange(Boolean.TRUE.equals(request.allowVoteChange()));
        poll.setEndsAt(request.durationMinutes() == null
                ? null
                : Instant.now().plus(java.time.Duration.ofMinutes(request.durationMinutes())));
        poll = pollRepository.save(poll);
        List<PollOption> options = new ArrayList<>();
        int position = 0;
        for (String text : request.options()) {
            PollOption option = new PollOption();
            option.setPoll(poll);
            option.setPosition(position++);
            option.setText(text);
            options.add(pollOptionRepository.save(option));
        }
        log.info("[poll] poll {} created by {} ({} options, multiple={}, anonymous={})",
                poll.getId(), message.getAuthor().getUsername(), options.size(),
                poll.isAllowMultiple(), poll.isAnonymous());
        return pollInfo(poll, options);
    }

    /**
     * The caller's "already voted" snapshot for a channel (2026-11-25, fix round 4):
     * the client seeds its poll cards from this at channel open, so a client restart
     * no longer re-offers votes the server would 409 — the honest fix for the
     * restart-amnesia loop. Gate: READ_MESSAGE_HISTORY (the cards are visible to
     * anyone who can read the channel; the votes returned are the CALLER's own).
     */
    @Transactional(readOnly = true)
    public List<MyPollVotesDto> myVotes(Long channelId, String viewerUsername) {
        User viewer = auth.requireUser(viewerUsername);
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        auth.requireInChannel(viewer, channel, Permission.READ_MESSAGE_HISTORY);
        Map<Long, List<Long>> optionsByPoll = new LinkedHashMap<>();
        Map<Long, Long> messageByPoll = new HashMap<>();
        for (PollVote vote : pollVoteRepository
                .findByPollMessageChannelIdAndUsername(channelId, viewer.getUsername())) {
            optionsByPoll.computeIfAbsent(vote.getPoll().getId(), pollId -> new ArrayList<>())
                    .add(vote.getOption().getId());
            messageByPoll.putIfAbsent(vote.getPoll().getId(), vote.getPoll().getMessage().getId());
        }
        return optionsByPoll.entrySet().stream()
                .map(entry -> new MyPollVotesDto(messageByPoll.get(entry.getKey()), entry.getKey(), entry.getValue()))
                .toList();
    }

    // --- internals ---------------------------------------------------------

    private PollResults resultsAndBroadcast(Poll poll) {
        PollResults results = tally(poll);
        publishEvent(poll, results.totalVoters(), false);
        return results;
    }

    /** Tallies votes; for non-anonymous polls each option carries its voters' usernames. */
    private PollResults tally(Poll poll) {
        List<PollOption> options = pollOptionRepository.findByPollIdOrderByPositionAsc(poll.getId());
        List<PollVote> votes = pollVoteRepository.findByPollId(poll.getId());
        Map<Long, List<String>> votersByOption = new LinkedHashMap<>();
        for (PollVote vote : votes) {
            votersByOption.computeIfAbsent(vote.getOption().getId(), id -> new ArrayList<>())
                    .add(vote.getUsername());
        }
        List<PollResults.PollOptionResult> rows = options.stream()
                .map(option -> {
                    List<String> voters = votersByOption.getOrDefault(option.getId(), List.of());
                    return new PollResults.PollOptionResult(option.getId(), voters.size(),
                            poll.isAnonymous() ? List.of() : voters);
                })
                .toList();
        int totalVoters = (int) votes.stream().map(PollVote::getUsername).distinct().count();
        return new PollResults(pollInfo(poll, options), totalVoters, rows);
    }

    /** Lazily finishes a poll whose deadline has passed (the sweeper mops up the rest). */
    private void finishIfExpired(Poll poll) {
        if (poll.getClosedAt() == null && poll.getEndsAt() != null
                && !poll.getEndsAt().isAfter(Instant.now())) {
            closeAndBroadcast(poll);
        }
    }

    /** Closes + broadcasts the finish event (idempotent guard inside). */
    private void closeAndBroadcast(Poll poll) {
        if (poll.getClosedAt() != null) {
            return;
        }
        poll.setClosedAt(Instant.now());
        pollRepository.save(poll);
        publishEvent(poll, countVoters(poll.getId()), true);
    }

    private int countVoters(Long pollId) {
        return pollVoteRepository.countVoters(pollId);
    }

    private void publishEvent(Poll poll, int totalVoters, boolean closed) {
        Message message = poll.getMessage();
        PollEvent event = new PollEvent(message.getChannel().getId(), message.getId(),
                poll.getId(), totalVoters, closed);
        messagingTemplate.convertAndSend(
                MessageService.CHANNEL_TOPIC_PREFIX + message.getChannel().getId()
                        + MessageService.POLLS_TOPIC_SUFFIX,
                event);
    }

    private Poll requirePoll(Long pollId) {
        return pollRepository.findById(pollId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Poll not found"));
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
