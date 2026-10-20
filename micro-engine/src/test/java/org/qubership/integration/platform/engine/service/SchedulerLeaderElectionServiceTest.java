package org.qubership.integration.platform.engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.consul.KeyValue;
import io.vertx.ext.consul.KeyValueOptions;
import io.vertx.ext.consul.Session;
import io.vertx.ext.consul.SessionBehavior;
import io.vertx.ext.consul.SessionOptions;
import io.vertx.mutiny.ext.consul.ConsulClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.TriggerKey;
import org.qubership.integration.platform.engine.consul.ConsulKeyValidator;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SchedulerLeaderElectionServiceTest {

    private static final String SESSION_ID = "8d4d10b6-9b8c-4ad0-a133-fb85cf9d2865";
    private static final String OTHER_SESSION_ID = "4f0f5e9a-0d8e-4b5e-9a57-6f3c2f3d1e11";
    private static final String KEY = "config/qip-e2e/cip-engine-configurations/scheduler-leaders/orders-v1";
    private static final String HOST = "10.244.0.26";
    private static final Instant START = Instant.parse("2026-10-20T09:00:00Z");
    private static final TriggerKey TRIGGER = TriggerKey.triggerKey("d33d6c3d-ea43a3d4", "default-group");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SchedulerLeaderElectionService service;

    @Mock
    ConsulClient consulClient;
    @Mock
    QuartzSchedulerService quartzSchedulerService;

    @BeforeEach
    void setUp() throws Exception {
        service = new SchedulerLeaderElectionService();
        service.consulClientSupplier = () -> consulClient;
        service.consulKeyValidator = new ConsulKeyValidator();
        service.quartzSchedulerService = quartzSchedulerService;
        service.engineInfo = EngineInfo.builder().host(HOST).domain("orders").build();
        service.objectMapper = objectMapper;
        service.keyPrefix = "config/qip-e2e";
        service.keyEngineConfigRoot = "/cip-engine-configurations";
        service.keySchedulerLeaders = "/scheduler-leaders";
        service.bgVersion = "v1";
        service.clock = Clock.fixed(START, ZoneOffset.UTC);
        lenient().when(quartzSchedulerService.getTriggerKeys()).thenReturn(Set.of(TRIGGER));
    }

    @Test
    void shouldTakeLeadershipAndRecordItsTriggersWhenItAcquiresAFreeKey() throws Exception {
        sessionCreated();
        keyAbsent();
        acquireReturns(true);

        service.elect();

        ArgumentCaptor<SessionOptions> options = ArgumentCaptor.forClass(SessionOptions.class);
        verify(consulClient).createSessionWithOptions(options.capture());
        assertEquals(10L, options.getValue().getTtl());
        assertEquals(15L, options.getValue().getLockDelay());
        assertEquals(SessionBehavior.RELEASE, options.getValue().getBehavior());
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(consulClient).putValueWithOptions(eq(KEY), value.capture(),
                argThat(kv -> SESSION_ID.equals(kv.getAcquireSession())));
        assertEquals(record(START, TRIGGER), objectMapper.readTree(value.getValue()));
        verify(quartzSchedulerService).takeLeadership(null, Set.of());
    }

    @Test
    void shouldPassThePreviousLeadersRecordWhenItTakesAReleasedKey() throws Exception {
        sessionCreated();
        keyHeldBy(null, recordText(START.minusSeconds(40), TRIGGER));
        acquireReturns(true);

        service.elect();

        verify(quartzSchedulerService).takeLeadership(START.minusSeconds(40), Set.of(TRIGGER));
    }

    @Test
    void shouldTakeAKeyWithAnUnreadableValueAsAFreeOne() {
        sessionCreated();
        keyHeldBy(null, HOST);
        acquireReturns(true);

        service.elect();

        verify(quartzSchedulerService).takeLeadership(null, Set.of());
    }

    @Test
    void shouldStayInStandbyWithoutAcquiringWhileAnotherPodHoldsTheKey() {
        sessionCreated();
        keyHeldBy(OTHER_SESSION_ID, recordText(START, TRIGGER));

        service.elect();

        verify(consulClient, never()).putValueWithOptions(anyString(), anyString(), any());
        verify(quartzSchedulerService, never()).takeLeadership(any(), any());
    }

    @Test
    void shouldRenewItsSessionAndKeepLeadershipOnLaterRounds() {
        sessionCreated();
        sessionRenewed();
        keyAbsent();
        acquireReturns(true);

        service.elect();
        service.elect();

        verify(consulClient, times(1)).createSessionWithOptions(any());
        verify(consulClient).renewSession(SESSION_ID);
        verify(quartzSchedulerService, times(1)).takeLeadership(any(), any());
        verify(quartzSchedulerService, never()).releaseLeadership();
    }

    @Test
    void shouldKeepLeadershipWhenConsulRefusesItsOwnKeyDuringTheLockDelay() {
        sessionCreated();
        sessionRenewed();
        acquireReturns(true, false);
        when(consulClient.getValue(KEY)).thenReturn(
                Uni.createFrom().item(new KeyValue().setKey(KEY)),
                Uni.createFrom().item(new KeyValue().setKey(KEY).setSession(SESSION_ID)));

        service.elect();
        service.elect();

        verify(quartzSchedulerService).takeLeadership(null, Set.of());
        verify(quartzSchedulerService, never()).releaseLeadership();
    }

    @Test
    void shouldStepDownWhenAnotherPodHoldsTheKey() {
        sessionCreated();
        sessionRenewed();
        acquireReturns(true, false);
        when(consulClient.getValue(KEY)).thenReturn(
                Uni.createFrom().item(new KeyValue().setKey(KEY)),
                Uni.createFrom().item(new KeyValue().setKey(KEY).setSession(OTHER_SESSION_ID)));

        service.elect();
        service.elect();

        verify(quartzSchedulerService).releaseLeadership();
    }

    @Test
    void shouldStepDownAndCreateANewSessionWhenItsSessionIsGone() {
        sessionCreated();
        keyAbsent();
        acquireReturns(true);
        service.elect();
        when(consulClient.renewSession(SESSION_ID))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Session id '" + SESSION_ID + "' not found")));

        service.elect();
        service.elect();

        verify(quartzSchedulerService).releaseLeadership();
        verify(consulClient, times(2)).createSessionWithOptions(any());
    }

    @Test
    void shouldKeepLeadershipThroughAConsulOutageShorterThanTheTtl() {
        leading();
        consulUnreachable();

        service.clock = Clock.fixed(START.plusSeconds(9), ZoneOffset.UTC);
        service.elect();

        verify(quartzSchedulerService, never()).releaseLeadership();
    }

    @Test
    void shouldStepDownWhenConsulStaysUnreachableForLongerThanTheTtl() {
        leading();
        consulUnreachable();

        service.clock = Clock.fixed(START.plusSeconds(11), ZoneOffset.UTC);
        service.elect();

        verify(quartzSchedulerService).releaseLeadership();
    }

    @Test
    void shouldCountTheTtlFromBeforeItsLastSuccessfulRenewal() {
        sessionCreated();
        keyAbsent();
        acquireReturns(true);
        // The first reading is taken before the renewal, any later one after six seconds of slow Consul calls.
        service.clock = new Clock() {
            private Instant next = START;

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                Instant instant = next;
                next = START.plusSeconds(6);
                return instant;
            }
        };
        service.elect();
        consulUnreachable();

        service.clock = Clock.fixed(START.plusSeconds(11), ZoneOffset.UTC);
        service.elect();

        verify(quartzSchedulerService).releaseLeadership();
    }

    @Test
    void shouldStandByBeforeReleasingTheKeyWithItsRecordOnShutdownWhenLeading() throws Exception {
        leading();
        when(consulClient.destroySession(SESSION_ID)).thenReturn(Uni.createFrom().voidItem());
        service.clock = Clock.fixed(START.plusSeconds(30), ZoneOffset.UTC);

        service.releaseOnShutdown(new ShutdownEvent());

        InOrder inOrder = inOrder(quartzSchedulerService, consulClient);
        inOrder.verify(quartzSchedulerService).releaseLeadership();
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        inOrder.verify(consulClient).putValueWithOptions(eq(KEY), value.capture(),
                argThat(kv -> SESSION_ID.equals(kv.getReleaseSession())));
        inOrder.verify(consulClient).destroySession(SESSION_ID);
        assertEquals(record(START.plusSeconds(30), TRIGGER), objectMapper.readTree(value.getValue()));
    }

    @Test
    void shouldLeaveTheLeadersKeyAloneOnShutdownWhenFollowing() {
        sessionCreated();
        keyHeldBy(OTHER_SESSION_ID, recordText(START, TRIGGER));
        when(consulClient.destroySession(SESSION_ID)).thenReturn(Uni.createFrom().voidItem());
        service.elect();

        service.releaseOnShutdown(new ShutdownEvent());

        verify(consulClient, never()).putValueWithOptions(anyString(), anyString(), any());
        verify(consulClient).destroySession(SESSION_ID);
    }

    @Test
    void shouldNotTakeTheKeyAgainAfterShutdown() {
        leading();
        when(consulClient.destroySession(SESSION_ID)).thenReturn(Uni.createFrom().voidItem());
        service.releaseOnShutdown(new ShutdownEvent());

        service.elect();

        verify(consulClient, times(1)).createSessionWithOptions(any());
        verify(quartzSchedulerService, times(1)).takeLeadership(any(), any());
    }

    private void leading() {
        sessionCreated();
        keyAbsent();
        acquireReturns(true);
        service.elect();
    }

    private void sessionCreated() {
        when(consulClient.createSessionWithOptions(any())).thenReturn(Uni.createFrom().item(SESSION_ID));
    }

    private void sessionRenewed() {
        when(consulClient.renewSession(SESSION_ID)).thenReturn(Uni.createFrom().item(new Session()));
    }

    private void consulUnreachable() {
        when(consulClient.renewSession(SESSION_ID)).thenReturn(Uni.createFrom().failure(new RuntimeException("Connection refused")));
    }

    private void keyAbsent() {
        when(consulClient.getValue(KEY)).thenReturn(Uni.createFrom().item(new KeyValue().setKey(KEY)));
    }

    private void keyHeldBy(String session, String value) {
        when(consulClient.getValue(KEY)).thenReturn(Uni.createFrom().item(
                new KeyValue().setKey(KEY).setSession(session).setValue(value)));
    }

    private void acquireReturns(boolean acquired) {
        when(consulClient.putValueWithOptions(eq(KEY), anyString(), any(KeyValueOptions.class)))
                .thenReturn(Uni.createFrom().item(acquired));
    }

    private void acquireReturns(boolean first, boolean second) {
        when(consulClient.putValueWithOptions(eq(KEY), anyString(), any(KeyValueOptions.class)))
                .thenReturn(Uni.createFrom().item(first), Uni.createFrom().item(second));
    }

    private String recordText(Instant seenAt, TriggerKey trigger) {
        return record(seenAt, trigger).toString();
    }

    private JsonNode record(Instant seenAt, TriggerKey trigger) {
        return objectMapper.valueToTree(new SchedulerLeaderElectionService.LeaderRecord(HOST, seenAt.toEpochMilli(),
                List.of(new SchedulerLeaderElectionService.TriggerName(trigger.getGroup(), trigger.getName()))));
    }
}
