package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElementSearchCriteria;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainRedeployRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainRolesResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.UpdateRolesRequest;
import org.qubership.integration.platform.runtime.catalog.service.ChainRolesService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChainRolesControllerTest {

    @Mock
    private ChainRolesService chainRolesService;

    private ChainRolesController controller() {
        return new ChainRolesController(chainRolesService);
    }

    @Test
    void findBySearchRequestReturnsTheSearchPage() {
        ChainElementSearchCriteria criteria = new ChainElementSearchCriteria();
        ChainRolesResponse page = new ChainRolesResponse(0, Collections.emptyList());
        when(chainRolesService.findAllChainByHttpTrigger(criteria, false)).thenReturn(page);

        ResponseEntity<ChainRolesResponse> response = controller().findBySearchRequest(criteria, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(page);
    }

    @Test
    void updateRolesAnswersNoContent() {
        List<UpdateRolesRequest> request = List.of(new UpdateRolesRequest());

        ResponseEntity<Void> response = controller().updateRoles(request);

        verify(chainRolesService).updateRoles(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void bulkRedeployAnswersNoContent() {
        List<ChainRedeployRequest> request = List.of(new ChainRedeployRequest());

        ResponseEntity<Void> response = controller().bulkRedeploy(request);

        verify(chainRolesService).redeploy(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }
}
