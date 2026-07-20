package org.qubership.integration.platform.camelk.configuration;

import org.qubership.integration.platform.camelk.naming.generator.StringGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Random;
import java.util.function.Supplier;

@AutoConfiguration
public class SuffixGeneratorConfiguration {
    @Value("${qip.cr.naming.chain.suffix-length:7}")
    private int suffixLength;

    @Bean("suffixGenerator")
    public Supplier<String> suffixGenerator() {
        StringGenerator generator = new StringGenerator(new Random());
        return () -> generator.generate(suffixLength);
    }
}
