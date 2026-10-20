package org.qubership.integration.platform.engine.service;

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
import org.qubership.integration.platform.engine.consul.ConsulKeyValidator;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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

    private SchedulerLeaderElectionService service;

    @Mock
    ConsulClient consulClient;
    @Mock
    QuartzSchedulerService quartzSchedulerService;

    @BeforeEach
    void setUp() {
        service = new SchedulerLeaderElectionService();
        service.consulClientSupplier = () -> consulClient;
        service.consulKeyValidator = new ConsulKeyValidator();
        service.quartzSchedulerService = quartzSchedulerService;
        service.engineInfo = EngineInfo.builder().host(HOST).domain("orders").build();
        service.keyPrefix = "config/qip-e2e";
        service.keyEngineConfigRoot = "/cip-engine-configurations";
        service.keySchedulerLeaders = "/scheduler-leaders";
        service.bgVersion = "v1";
        service.clock = Clock.fixed(START, ZoneOffset.UTC);
    }

    @Test
    void shouldTakeLeadershipWhenItAcquiresTheDomainKey() {
        sessionCreated();
        acquireReturns(true);

        service.elect();

        ArgumentCaptor<SessionOptions> options = ArgumentCaptor.forClass(SessionOptions.class);
        verify(consulClient).createSessionWithOptions(options.capture());
        assertEquals(10L, options.getValue().getTtl());
        assertEquals(15L, options.getValue().getLockDelay());
        assertEquals(SessionBehavior.DELETE, options.getValue().getBehavior());
        verify(consulClient).putValueWithOptions(eq(KEY), eq(HOST),
                argThat(kv -> SESSION_ID.equals(kv.getAcquireSession())));
        verify(quartzSchedulerService).takeLeadership();
    }

    @Test
    void shouldStayInStandbyWhileAnotherPodHoldsTheKey() {
        sessionCreated();
        acquireReturns(false);
        keyHeldBy(OTHER_SESSION_ID);

        service.elect();

        verify(quartzSchedulerService, never()).takeLeadership();
        verify(quartzSchedulerService, never()).releaseLeadership();
    }

    @Test
    void shouldRenewItsSessionAndKeepLeadershipOnLaterRounds() {
        sessionCreated();
        sessionRenewed();
        acquireReturns(true);

        service.elect();
        service.elect();

        verify(consulClient, times(1)).createSessionWithOptions(any());
        verify(consulClient).renewSession(SESSION_ID);
        verify(quartzSchedulerService, times(1)).takeLeadership();
        verify(quartzSchedulerService, never()).releaseLeadership();
    }

    @Test
    void shouldKeepLeadershipWhenConsulRefusesItsOwnKeyDuringTheLockDelay() {
        sessionCreated();
        sessionRenewed();
        acquireReturns(true, false);
        keyHeldBy(SESSION_ID);

        service.elect();
        service.elect();

        verify(quartzSchedulerService).takeLeadership();
        verify(quartzSchedulerService, never()).releaseLeadership();
    }

    @Test
    void shouldStepDownWhenAnotherPodHoldsTheKey() {
        sessionCreated();
        sessionRenewed();
        acquireReturns(true, false);
        keyHeldBy(OTHER_SESSION_ID);

        service.elect();
        service.elect();

        verify(quartzSchedulerService).releaseLeadership();
    }

    @Test
    void shouldStepDownAndCreateANewSessionWhenItsSessionIsGone() {
        sessionCreated();
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
        sessionCreated();
        acquireReturns(true);
        service.elect();
        consulUnreachable();

        service.clock = Clock.fixed(START.plusSeconds(9), ZoneOffset.UTC);
        service.elect();

        verify(quartzSchedulerService, never()).releaseLeadership();
    }

    @Test
    void shouldStepDownWhenConsulStaysUnreachableForLongerThanTheTtl() {
        sessionCreated();
        acquireReturns(true);
        service.elect();
        consulUnreachable();

        service.clock = Clock.fixed(START.plusSeconds(11), ZoneOffset.UTC);
        service.elect();

        verify(quartzSchedulerService).releaseLeadership();
    }

    @Test
    void shouldCountTheTtlFromBeforeItsLastSuccessfulRenewal() {
        sessionCreated();
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
    void shouldStandByBeforeDeletingTheKeyOnShutdownWhenLeading() {
        sessionCreated();
        acquireReturns(true);
        when(consulClient.deleteValue(KEY)).thenReturn(Uni.createFrom().voidItem());
        when(consulClient.destroySession(SESSION_ID)).thenReturn(Uni.createFrom().voidItem());
        service.elect();

        service.releaseOnShutdown(new ShutdownEvent());

        InOrder inOrder = inOrder(quartzSchedulerService, consulClient);
        inOrder.verify(quartzSchedulerService).releaseLeadership();
        inOrder.verify(consulClient).deleteValue(KEY);
        inOrder.verify(consulClient).destroySession(SESSION_ID);
    }

    @Test
    void shouldLeaveTheLeadersKeyAloneOnShutdownWhenFollowing() {
        sessionCreated();
        acquireReturns(false);
        keyHeldBy(OTHER_SESSION_ID);
        when(consulClient.destroySession(SESSION_ID)).thenReturn(Uni.createFrom().voidItem());
        service.elect();

        service.releaseOnShutdown(new ShutdownEvent());

        verify(consulClient, never()).deleteValue(any());
        verify(consulClient).destroySession(SESSION_ID);
    }

    @Test
    void shouldNotTakeTheKeyAgainAfterShutdown() {
        sessionCreated();
        acquireReturns(true);
        when(consulClient.deleteValue(KEY)).thenReturn(Uni.createFrom().voidItem());
        when(consulClient.destroySession(SESSION_ID)).thenReturn(Uni.createFrom().voidItem());
        service.elect();
        service.releaseOnShutdown(new ShutdownEvent());

        service.elect();

        verify(consulClient, times(1)).createSessionWithOptions(any());
        verify(quartzSchedulerService, times(1)).takeLeadership();
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

    private void keyHeldBy(String session) {
        when(consulClient.getValue(KEY)).thenReturn(Uni.createFrom().item(new KeyValue().setKey(KEY).setSession(session)));
    }

    private void acquireReturns(boolean acquired) {
        when(consulClient.putValueWithOptions(eq(KEY), eq(HOST), any(KeyValueOptions.class)))
                .thenReturn(Uni.createFrom().item(acquired));
    }

    private void acquireReturns(boolean first, boolean second) {
        when(consulClient.putValueWithOptions(eq(KEY), eq(HOST), any(KeyValueOptions.class)))
                .thenReturn(Uni.createFrom().item(first), Uni.createFrom().item(second));
    }
}
