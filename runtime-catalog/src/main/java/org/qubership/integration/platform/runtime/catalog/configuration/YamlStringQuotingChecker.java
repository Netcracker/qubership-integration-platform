package org.qubership.integration.platform.runtime.catalog.configuration;

import com.fasterxml.jackson.dataformat.yaml.util.StringQuotingChecker;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.resolver.Resolver;

import java.util.regex.Pattern;

public class YamlStringQuotingChecker extends StringQuotingChecker.Default {
    private static final Resolver RESOLVER = new Resolver();
    private static final Pattern YAML_1_2_OCTAL = Pattern.compile("[-+]?0o[0-7_]+");

    @Override
    public boolean needToQuoteValue(String value) {
        return super.needToQuoteValue(value)
                || !Tag.STR.equals(RESOLVER.resolve(NodeId.scalar, value, true))
                // The VS Code extension reads exports with js-yaml and yaml, which parse 0o17 as a number.
                || YAML_1_2_OCTAL.matcher(value).matches();
    }
}
