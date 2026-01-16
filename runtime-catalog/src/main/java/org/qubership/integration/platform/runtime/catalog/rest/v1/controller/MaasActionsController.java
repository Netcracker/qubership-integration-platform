package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.cloudcore.maas.MaasService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping(value = "/v1/maas-actions", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Validated
@Tag(name = "maas-actions-controller", description = "Maas Actions Controller")
public class MaasActionsController {

    private static final String MAAS_DECLARATIVE_FILENAME = "maas-configuration.yaml";

    private final MaasService maasService;

    @Autowired
    public MaasActionsController(MaasService maasService) {
        this.maasService = maasService;
    }

    @PostMapping("/kafka")
    @Operation(description = "Create kafka topic")
    public ResponseEntity<Void> createKafka(@RequestParam @Valid @NotBlank @Parameter(description = "Namespace") String namespace,
                                            @RequestParam @Valid @NotBlank @Parameter(description = "Topic classifier") String topicClassifierName) {
        log.info("Request to create kafka topic classifier {} for namespace {}", topicClassifierName, namespace);
        maasService.getOrCreateKafkaTopic(namespace, topicClassifierName);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/rabbitmq")
    @Operation(description = "Create rabbitmq entity(s)")
    public ResponseEntity<Void> createRabbitMq(@RequestParam @Valid @NotBlank @Parameter(description = "Namespace") String namespace,
                                               @RequestParam @Valid @NotBlank @Parameter(description = "VHost") String vhost,
                                               @RequestParam @Parameter(description = "Exchange name") String exchange,
                                               @RequestParam @Parameter(description = "Queue") String queue,
                                               @RequestParam(required = false) @Parameter(description = "Routing key") String routingKey) {
        log.info("Request to create create RabbitMq entities [vhost={}][exchange={}][queue={}][routingKey={}] for namespace {}",
                vhost, exchange, queue, routingKey, namespace);
        maasService.createRabbitmqEntities(namespace, vhost, exchange, queue, routingKey);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/kafka/declarative")
    @Operation(description = "Get declarative template for kafka")
    public ResponseEntity<Object> getKafkaDeclarativeFile(@RequestParam @Valid @NotBlank @Parameter(description = "Topic classifier") String topicClassifierName) {
        log.info("Request to get kafka declarative file for topic classifier {}", topicClassifierName);
        return asResponse(MAAS_DECLARATIVE_FILENAME, maasService.getMaasDeclarativeFileKafka(topicClassifierName));
    }

    @PostMapping("/rabbitmq/declarative")
    @Operation(description = "Get declarative template for rabbitmq")
    public ResponseEntity<Object> getRabbitMqDeclarativeFile(@RequestParam @Valid @NotBlank @Parameter(description = "VHost") String vhost,
                                                             @RequestParam @Parameter(description = "Exchange name") String exchange,
                                                             @RequestParam @Parameter(description = "Queue") String queue,
                                                             @RequestParam(required = false) @Parameter(description = "Routing key") String routingKey) {
        log.info("Request to get RabbitMq declarative file entities [vhost={}][exchange={}][queue={}][routingKey={}]",
                vhost, exchange, queue, routingKey);
        return asResponse(MAAS_DECLARATIVE_FILENAME, maasService.getMaasDeclarativeFileRabbitMq(vhost, exchange, queue, routingKey));
    }

    private ResponseEntity<Object> asResponse(String filename, byte[] data) {
        HttpHeaders header = new HttpHeaders();
        header.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename);
        header.add(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION);
        ByteArrayResource resource = new ByteArrayResource(data);
        return ResponseEntity.ok()
                .headers(header)
                .contentLength(resource.contentLength())
                .body(resource);
    }
}
