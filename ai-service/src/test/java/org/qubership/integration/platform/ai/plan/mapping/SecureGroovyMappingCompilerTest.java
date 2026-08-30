package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecureGroovyMappingCompilerTest {

  @Test
  void validJsonSlurperCompiles() {
    assertDoesNotThrow(
        () ->
            SecureGroovyMappingCompiler.compile(
                """
                def source = new groovy.json.JsonSlurper().parseText(exchange.in.body as String)
                def target = [:]
                target['orderId'] = source['orderId']
                exchange.in.body = new groovy.json.JsonBuilder(target).toString()
                """));
  }

  @Test
  void missingBraceFails() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SecureGroovyMappingCompiler.compile("def x = {"));
  }

  @Test
  void grabIsRejected() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> SecureGroovyMappingCompiler.compile("@Grab('foo:bar:1')\ndef x = 1\n"));
    assertTrue(thrown.getMessage().startsWith("Groovy mapping:"));
  }

  @Test
  void fileApiIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SecureGroovyMappingCompiler.compile("new File('/tmp/x').text\n"));
  }

  @Test
  void unknownFieldStillCompiles() {
    assertDoesNotThrow(() -> SecureGroovyMappingCompiler.compile("def x = source.notAField\n"));
  }
}
