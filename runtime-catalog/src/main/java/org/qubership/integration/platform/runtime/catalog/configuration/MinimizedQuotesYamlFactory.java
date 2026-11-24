package org.qubership.integration.platform.runtime.catalog.configuration;

import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.common.ScalarStyle;
import tools.jackson.core.JacksonException;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.io.IOContext;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLFactoryBuilder;
import tools.jackson.dataformat.yaml.YAMLGenerator;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;
import tools.jackson.dataformat.yaml.util.StringQuotingChecker;

import java.io.Writer;


/**
 * Writes a multi-line string double-quoted instead of as a literal block when it contains U+0085, U+2028, or
 * U+2029, because a YAML reader turns those characters into line feeds inside a literal block.
 */
public class MinimizedQuotesYamlFactory extends YAMLFactory {
    public MinimizedQuotesYamlFactory(YAMLFactoryBuilder builder) {
        super(builder);
    }

    private MinimizedQuotesYamlFactory(MinimizedQuotesYamlFactory src) {
        super(src);
    }

    @Override
    public YAMLFactory copy() {
        return new MinimizedQuotesYamlFactory(this);
    }

    @Override
    protected YAMLGenerator _createGenerator(ObjectWriteContext context, IOContext ctxt, Writer out)
            throws JacksonException {
        return new Generator(context, ctxt, context.getStreamWriteFeatures(_streamWriteFeatures),
                context.getFormatWriteFeatures(_formatWriteFeatures), _quotingChecker, out, _version, _dumpSettings);
    }

    private static final class Generator extends YAMLGenerator {
        Generator(ObjectWriteContext context, IOContext ctxt, int jsonFeatures, int yamlFeatures,
                StringQuotingChecker quotingChecker, Writer out, org.snakeyaml.engine.v2.common.SpecVersion version,
                DumpSettings dumpSettings) {
            super(context, ctxt, jsonFeatures, yamlFeatures, quotingChecker, out, version, dumpSettings);
        }

        @Override
        public tools.jackson.core.JsonGenerator writeString(String text) throws JacksonException {
            if (text != null && isEnabled(YAMLWriteFeature.MINIMIZE_QUOTES) && text.indexOf('\n') >= 0
                    && YamlStringQuotingChecker.containsUnicodeLineBreak(text)) {
                _verifyValueWrite("write String value");
                _writeScalar(text, "string", ScalarStyle.DOUBLE_QUOTED);
                return this;
            }
            return super.writeString(text);
        }
    }
}
